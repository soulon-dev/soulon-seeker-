/**
 * Auth API - 钱包签名登录
 */

import { Env, jsonResponse } from '../index';
import { verifySignature, generateChallenge, base58Decode, base58Encode, generateToken, verifyToken } from '../utils/crypto';

// 登录 Challenge 有效期 (5 分钟)
const LOGIN_CHALLENGE_EXPIRY_MS = 5 * 60 * 1000;

// 会话有效期 (7 天)
const SESSION_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000;

type StoredChallenge = { challenge: string; message: string; timestamp: number }

export async function handleAuth(
  request: Request,
  env: Env,
  path: string
): Promise<Response> {
  
  if (path === '/api/v1/auth/challenge' && request.method === 'POST') {
    return handleLoginChallenge(request, env);
  }
  
  if (path === '/api/v1/auth/login' && request.method === 'POST') {
    return handleLogin(request, env);
  }

  if (path === '/api/v1/auth/login-session-key' && request.method === 'POST') {
    return handleLoginWithSessionKey(request, env)
  }
  
  if (path === '/api/v1/auth/verify' && request.method === 'POST') {
    return handleVerifySession(request, env);
  }
  
  if (path === '/api/v1/auth/logout' && request.method === 'POST') {
    return handleLogout(request, env);
  }
  
  return jsonResponse({ error: 'Not Found' }, 404);
}

function getReplayKv(env: Env): KVNamespace | null {
  return env.SESSIONS || env.KV || null
}

function parseSessionAuthMessage(message: string): { sessionPubKeyHex: string; expiresAtMs: number } | null {
  if (!message.includes('MemoryAI Session Authorization')) return null
  const pubMatch = message.match(/Session Public Key:\s*([0-9a-fA-F]{64})/)
  const expMatch = message.match(/Expires:\s*(\d{10,})/)
  if (!pubMatch || !expMatch) return null
  const expiresAtMs = Number(expMatch[1])
  if (!Number.isFinite(expiresAtMs) || expiresAtMs <= 0) return null
  return { sessionPubKeyHex: pubMatch[1].toLowerCase(), expiresAtMs }
}

async function handleLoginWithSessionKey(request: Request, env: Env): Promise<Response> {
  try {
    const body = await request.json() as {
      wallet_address?: string
      signature?: string
      public_key?: string
      message?: string
    }

    if (!body.wallet_address || !body.signature || !body.public_key || !body.message) {
      return jsonResponse(
        { success: false, error: 'Missing required fields', required: ['wallet_address', 'signature', 'public_key', 'message'] },
        400
      )
    }

    const { wallet_address, signature, public_key, message } = body

    const signatureBytes = base58Decode(signature)
    const publicKeyBytes = base58Decode(public_key)
    const walletBytes = base58Decode(wallet_address)
    if (walletBytes.length !== 32 || publicKeyBytes.length !== 32) {
      return jsonResponse({ success: false, error: 'Invalid public key' }, 400)
    }
    for (let i = 0; i < 32; i++) {
      if (walletBytes[i] !== publicKeyBytes[i]) {
        return jsonResponse({ success: false, error: 'wallet_address does not match public_key' }, 401)
      }
    }

    const parsed = parseSessionAuthMessage(message)
    if (!parsed) {
      return jsonResponse({ success: false, error: 'Invalid message' }, 400)
    }

    const now = Date.now()
    if (parsed.expiresAtMs <= now) {
      return jsonResponse({ success: false, error: 'Authorization expired' }, 401)
    }
    const maxAllowedMs = now + SESSION_EXPIRY_MS + 10 * 60 * 1000
    if (parsed.expiresAtMs > maxAllowedMs) {
      return jsonResponse({ success: false, error: 'Authorization expiry too far' }, 400)
    }

    const kv = getReplayKv(env)
    const replayKey = `auth:session_auth:${wallet_address}:${parsed.sessionPubKeyHex}`
    if (kv) {
      const exists = await kv.get(replayKey)
      if (exists) {
        return jsonResponse({ success: false, error: 'Authorization already used' }, 401)
      }
      const ttlSeconds = Math.max(60, Math.floor((parsed.expiresAtMs - now) / 1000))
      await kv.put(replayKey, '1', { expirationTtl: ttlSeconds })
    }

    const messageBytes = new TextEncoder().encode(message)
    const isValid = verifySignature(messageBytes, signatureBytes, publicKeyBytes)
    if (!isValid) {
      if (kv) await kv.delete(replayKey)
      return jsonResponse({ success: false, error: 'Invalid signature' }, 401)
    }

    const jwtSecret = (env.JWT_SECRET || '').trim()
    if (!jwtSecret) {
      return jsonResponse({ success: false, error: 'Server not configured' }, 500)
    }

    const expiresInSeconds = Math.floor((parsed.expiresAtMs - now) / 1000)
    const sessionToken = await generateToken(
      { token_use: 'user_session', wallet_address, public_key, session_pubkey: parsed.sessionPubKeyHex },
      jwtSecret,
      expiresInSeconds
    )

    return jsonResponse({
      success: true,
      session_token: sessionToken,
      access_token: sessionToken,
      wallet_address,
      expires_at: parsed.expiresAtMs,
    })
  } catch (error) {
    console.error('Login with session key error:', error)
    return jsonResponse({ success: false, error: 'Login failed: ' + (error as Error).message }, 500)
  }
}

function getChallengeKv(env: Env): KVNamespace | null {
  return env.CHALLENGES || env.KV || null
}

async function putChallenge(env: Env, walletAddress: string, record: StoredChallenge): Promise<void> {
  const kv = getChallengeKv(env)
  const key = `auth:challenge:${walletAddress}`
  if (kv) {
    await kv.put(key, JSON.stringify(record), { expirationTtl: Math.floor(LOGIN_CHALLENGE_EXPIRY_MS / 1000) })
    return
  }
  if (!env.DB) throw new Error('Database not available')
  await env.DB.prepare(
    `INSERT INTO auth_challenges (wallet_address, challenge, message, issued_at)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(wallet_address) DO UPDATE SET challenge = excluded.challenge, message = excluded.message, issued_at = excluded.issued_at`
  ).bind(walletAddress, record.challenge, record.message, Math.floor(record.timestamp / 1000)).run()
}

async function getChallenge(env: Env, walletAddress: string): Promise<StoredChallenge | null> {
  const kv = getChallengeKv(env)
  const key = `auth:challenge:${walletAddress}`
  if (kv) {
    const raw = await kv.get(key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as StoredChallenge
    return parsed
  }
  if (!env.DB) throw new Error('Database not available')
  const row = await env.DB.prepare(
    'SELECT challenge, message, issued_at FROM auth_challenges WHERE wallet_address = ?'
  ).bind(walletAddress).first() as any
  if (!row) return null
  return {
    challenge: row.challenge,
    message: row.message,
    timestamp: Number(row.issued_at) * 1000,
  }
}

async function deleteChallenge(env: Env, walletAddress: string): Promise<void> {
  const kv = getChallengeKv(env)
  const key = `auth:challenge:${walletAddress}`
  if (kv) {
    await kv.delete(key)
    return
  }
  if (!env.DB) return
  await env.DB.prepare('DELETE FROM auth_challenges WHERE wallet_address = ?').bind(walletAddress).run()
}

/**
 * 生成登录 Challenge
 * 
 * POST /api/v1/auth/challenge
 * Body: { wallet_address: string }
 */
async function handleLoginChallenge(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    const body = await request.json() as { wallet_address?: string };
    
    if (!body.wallet_address) {
      return jsonResponse({ error: 'wallet_address is required' }, 400);
    }
    
    const challenge = generateChallenge();
    const challengeBase58 = base58Encode(challenge);
    
    // 构造 SIWS 消息
    const message = [
      'Soulon Authentication',
      '',
      `Wallet: ${body.wallet_address}`,
      `Challenge: ${challengeBase58}`,
      `Issued At: ${new Date().toISOString()}`,
      '',
      'Sign this message to authenticate.',
    ].join('\n');
    
    await putChallenge(env, body.wallet_address, { challenge: challengeBase58, message, timestamp: Date.now() })
    
    return jsonResponse({
      challenge: challengeBase58,
      message,
      expires_in: LOGIN_CHALLENGE_EXPIRY_MS / 1000,
    });
    
  } catch (error) {
    console.error('Login challenge error:', error);
    return jsonResponse({ error: 'Failed to generate challenge' }, 500);
  }
}

/**
 * 钱包签名登录
 * 
 * POST /api/v1/auth/login
 * Body: {
 *   wallet_address: string,
 *   signature: string (Base58),
 *   public_key: string (Base58)
 * }
 */
async function handleLogin(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    const body = await request.json() as {
      wallet_address?: string;
      signature?: string;
      public_key?: string;
    };
    
    if (!body.wallet_address || !body.signature || !body.public_key) {
      return jsonResponse({
        error: 'Missing required fields',
        required: ['wallet_address', 'signature', 'public_key'],
      }, 400);
    }
    
    const { wallet_address, signature, public_key } = body;
    
    // 获取存储的 Challenge
    const storedData = await getChallenge(env, wallet_address)
    
    if (!storedData) {
      return jsonResponse({
        success: false,
        error: 'Challenge not found or expired',
      }, 401);
    }
    
    // 检查是否过期
    if (Date.now() - storedData.timestamp > LOGIN_CHALLENGE_EXPIRY_MS) {
      await deleteChallenge(env, wallet_address)
      return jsonResponse({
        success: false,
        error: 'Challenge expired',
      }, 401);
    }
    
    // 验证签名
    const signatureBytes = base58Decode(signature);
    const publicKeyBytes = base58Decode(public_key);
    
    const walletBytes = base58Decode(wallet_address)
    if (walletBytes.length !== 32 || publicKeyBytes.length !== 32) {
      return jsonResponse({ success: false, error: 'Invalid public key' }, 400)
    }
    for (let i = 0; i < 32; i++) {
      if (walletBytes[i] !== publicKeyBytes[i]) {
        return jsonResponse({ success: false, error: 'wallet_address does not match public_key' }, 401)
      }
    }

    const messageBytes = new TextEncoder().encode(storedData.message)
    const isValid = verifySignature(messageBytes, signatureBytes, publicKeyBytes);
    
    if (!isValid) {
      return jsonResponse({
        success: false,
        error: 'Invalid signature',
      }, 401);
    }
    
    const jwtSecret = (env.JWT_SECRET || '').trim()
    if (!jwtSecret) {
      return jsonResponse({ success: false, error: 'Server not configured' }, 500)
    }

    const expiresInSeconds = Math.floor(SESSION_EXPIRY_MS / 1000)
    const sessionToken = await generateToken(
      { token_use: 'user_session', wallet_address, public_key },
      jwtSecret,
      expiresInSeconds
    )
    const now = Date.now()
    const expiresAt = now + SESSION_EXPIRY_MS

    await deleteChallenge(env, wallet_address)
    
    return jsonResponse({
      success: true,
      session_token: sessionToken,
      access_token: sessionToken,
      wallet_address,
      expires_at: expiresAt,
    });
    
  } catch (error) {
    console.error('Login error:', error);
    return jsonResponse({
      success: false,
      error: 'Login failed: ' + (error as Error).message,
    }, 500);
  }
}

/**
 * 验证会话
 * 
 * POST /api/v1/auth/verify
 * Body: { session_token: string }
 */
async function handleVerifySession(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    const body = await request.json() as { session_token?: string; access_token?: string };
    
    const token = body.access_token || body.session_token
    if (!token) {
      return jsonResponse({ valid: false, error: 'token is required' }, 400);
    }

    const jwtSecret = (env.JWT_SECRET || '').trim()
    if (!jwtSecret) {
      return jsonResponse({ valid: false, error: 'Server not configured' }, 500)
    }

    const payload = await verifyToken(token, jwtSecret)
    if (!payload) {
      return jsonResponse({ valid: false, error: 'Invalid token' }, 401)
    }

    const walletAddress = payload.wallet_address || payload.walletAddress
    const exp = payload.exp
    if (typeof walletAddress !== 'string' || !walletAddress.trim()) {
      return jsonResponse({ valid: false, error: 'Invalid token' }, 401)
    }

    const expiresAtMs =
      typeof exp === 'number' && exp > 0 ? Math.floor(exp * 1000) : 0

    return jsonResponse({ valid: true, wallet_address: walletAddress, expires_at: expiresAtMs })
    
  } catch (error) {
    console.error('Verify session error:', error);
    return jsonResponse({ valid: false, error: 'Verification failed' }, 500);
  }
}

/**
 * 登出
 * 
 * POST /api/v1/auth/logout
 * Body: { session_token: string }
 */
async function handleLogout(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    return jsonResponse({ success: true });
    
  } catch (error) {
    return jsonResponse({ success: true }); // 静默失败
  }
}
