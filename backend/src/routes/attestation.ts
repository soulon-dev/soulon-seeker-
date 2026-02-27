/**
 * TEEPIN Attestation API
 * 
 * 验证 Seeker S2 硬件身份
 */

import { Env, jsonResponse } from '../index';
import { verifySignature, generateChallenge, base58Decode, base58Encode } from '../utils/crypto';
import { verifyGenesisToken } from '../utils/solana';
import { getSolanaRpcUrl } from '../utils/solana-rpc';

// Challenge 有效期 (5 分钟)
const CHALLENGE_EXPIRY_MS = 5 * 60 * 1000;

// 存储 Challenge (内存缓存，生产环境应使用 KV)
const challengeStore = new Map<string, { challenge: string; timestamp: number }>();

export async function handleAttestation(
  request: Request,
  env: Env,
  path: string
): Promise<Response> {
  
  if (path === '/api/v1/attestation/challenge' && request.method === 'POST') {
    return handleGenerateChallenge(request, env);
  }
  
  if (path === '/api/v1/attestation/verify' && request.method === 'POST') {
    return handleVerifyAttestation(request, env);
  }
  
  return jsonResponse({ error: 'Not Found' }, 404);
}

/**
 * 生成 Attestation Challenge
 * 
 * POST /api/v1/attestation/challenge
 * Body: { wallet_address: string, timestamp: number }
 */
async function handleGenerateChallenge(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    const body = await request.json() as { wallet_address?: string; timestamp?: number };
    
    if (!body.wallet_address) {
      return jsonResponse({ error: 'wallet_address is required' }, 400);
    }
    
    const walletAddress = body.wallet_address;
    
    // 生成随机 Challenge
    const challenge = generateChallenge();
    const challengeBase58 = base58Encode(challenge);
    
    // 存储 Challenge (生产环境应使用 KV)
    const storeKey = `challenge:${walletAddress}`;
    
    if (env.CHALLENGES) {
      // 使用 Cloudflare KV
      await env.CHALLENGES.put(storeKey, JSON.stringify({
        challenge: challengeBase58,
        timestamp: Date.now(),
      }), { expirationTtl: 300 }); // 5 分钟过期
    } else {
      // 使用内存缓存 (开发环境)
      challengeStore.set(storeKey, {
        challenge: challengeBase58,
        timestamp: Date.now(),
      });
    }
    
    return jsonResponse({
      challenge: challengeBase58,
      expires_in: CHALLENGE_EXPIRY_MS / 1000,
      message: 'Sign this challenge to prove hardware ownership',
    });
    
  } catch (error) {
    console.error('Generate challenge error:', error);
    return jsonResponse({ error: 'Failed to generate challenge' }, 500);
  }
}

/**
 * 验证 Attestation 签名
 * 
 * POST /api/v1/attestation/verify
 * Body: {
 *   wallet_address: string,
 *   signature: string (Base58),
 *   public_key: string (Base58),
 *   genesis_token_id: string,
 *   timestamp: number
 * }
 */
async function handleVerifyAttestation(
  request: Request,
  env: Env
): Promise<Response> {
  try {
    const body = await request.json() as {
      wallet_address?: string;
      signature?: string;
      public_key?: string;
      genesis_token_id?: string;
      timestamp?: number;
    };
    
    // 参数验证
    if (!body.wallet_address || !body.signature || !body.public_key || !body.genesis_token_id) {
      return jsonResponse({
        error: 'Missing required fields',
        required: ['wallet_address', 'signature', 'public_key', 'genesis_token_id'],
      }, 400);
    }
    
    const { wallet_address, signature, public_key, genesis_token_id } = body;
    
    // 1. 获取存储的 Challenge
    const storeKey = `challenge:${wallet_address}`;
    let storedData: { challenge: string; timestamp: number } | null = null;
    
    if (env.CHALLENGES) {
      const stored = await env.CHALLENGES.get(storeKey);
      if (stored) {
        storedData = JSON.parse(stored);
      }
    } else {
      storedData = challengeStore.get(storeKey) || null;
    }
    
    if (!storedData) {
      return jsonResponse({
        verified: false,
        reason: 'Challenge not found or expired',
      });
    }
    
    // 2. 检查 Challenge 是否过期
    if (Date.now() - storedData.timestamp > CHALLENGE_EXPIRY_MS) {
      return jsonResponse({
        verified: false,
        reason: 'Challenge expired',
      });
    }
    
    // 3. 验证签名
    const challengeBytes = base58Decode(storedData.challenge);
    const signatureBytes = base58Decode(signature);
    const publicKeyBytes = base58Decode(public_key);
    
    const isValidSignature = verifySignature(challengeBytes, signatureBytes, publicKeyBytes);
    
    if (!isValidSignature) {
      return jsonResponse({
        verified: false,
        reason: 'Invalid signature',
      });
    }
    
    // 4. 验证 Genesis Token 归属权
    const rpcUrl = getSolanaRpcUrl(env);
    const genesisTokenValid = await verifyGenesisToken(
      wallet_address,
      genesis_token_id,
      env.GENESIS_TOKEN_COLLECTION,
      rpcUrl
    );
    
    // 5. 计算收益倍数
    let multiplier = 1.0;
    if (isValidSignature && genesisTokenValid) {
      multiplier = 1.5; // Seeker 硬件验证通过，1.5x 倍数
    } else if (isValidSignature) {
      multiplier = 1.2; // 签名有效但无 Genesis Token
    }
    
    // 6. 生成 Attestation 证书
    const validityHours = parseInt(env.ATTESTATION_VALIDITY_HOURS) || 24;
    const validUntil = Date.now() + validityHours * 60 * 60 * 1000;
    const certificate = `att_${wallet_address.substring(0, 8)}_${Date.now()}`;
    
    // 7. 清除使用过的 Challenge
    if (env.CHALLENGES) {
      await env.CHALLENGES.delete(storeKey);
    } else {
      challengeStore.delete(storeKey);
    }
    
    return jsonResponse({
      verified: true,
      multiplier,
      valid_until: validUntil,
      certificate,
      genesis_token_verified: genesisTokenValid,
      hardware_verified: isValidSignature,
    });
    
  } catch (error) {
    console.error('Verify attestation error:', error);
    return jsonResponse({
      verified: false,
      reason: 'Verification failed: ' + (error as Error).message,
    }, 500);
  }
}
