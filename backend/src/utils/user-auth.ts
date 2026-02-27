import { verifyToken } from './crypto'

export type AuthResult =
  | { ok: true; walletAddress: string; payload: Record<string, unknown> }
  | { ok: false; error: string }

function extractBearerToken(request: Request): string | null {
  const header = request.headers.get('Authorization') || request.headers.get('authorization')
  if (!header) return null
  const m = header.match(/^Bearer\s+(.+)$/i)
  return m ? m[1].trim() : null
}

function requireJwtSecret(env: any): string | null {
  const secret = (env.JWT_SECRET || '').trim()
  return secret ? secret : null
}

export async function getUserAuth(request: Request, env: any): Promise<AuthResult> {
  const token = extractBearerToken(request)
  if (!token) return { ok: false, error: 'missing_token' }
  const secret = requireJwtSecret(env)
  if (!secret) return { ok: false, error: 'missing_jwt_secret' }

  const payload = await verifyToken(token, secret)
  if (!payload) return { ok: false, error: 'invalid_token' }

  const walletAddress = payload.wallet_address || payload.walletAddress
  if (typeof walletAddress !== 'string' || !walletAddress.trim()) {
    return { ok: false, error: 'missing_wallet' }
  }

  const tokenUse = payload.token_use
  if (tokenUse !== 'user_session') {
    return { ok: false, error: 'invalid_token_use' }
  }

  return { ok: true, walletAddress: walletAddress.trim(), payload }
}

export async function requireUserWallet(request: Request, env: any): Promise<string> {
  const auth = await getUserAuth(request, env)
  if (!auth.ok) {
    throw new Error(auth.error)
  }
  return auth.walletAddress
}

