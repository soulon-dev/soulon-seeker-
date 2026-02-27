/**
 * 管理员认证中间件
 * 验证 Cloudflare Access JWT
 */

import { createRemoteJWKSet, jwtVerify } from 'jose'

export interface AdminContext {
  adminEmail: string
  email: string // alias for adminEmail
  requestId: string
  ip: string | null
}

const accessJwksCache = new Map<string, ReturnType<typeof createRemoteJWKSet>>()

function getAccessJwks(teamName: string) {
  const cached = accessJwksCache.get(teamName)
  if (cached) return cached
  const jwks = createRemoteJWKSet(new URL(`https://${teamName}.cloudflareaccess.com/cdn-cgi/access/certs`))
  accessJwksCache.set(teamName, jwks)
  return jwks
}

/**
 * 验证 Cloudflare Access JWT
 */
export async function verifyAdminAccess(
  request: Request,
  env: any
): Promise<{ valid: boolean; email?: string; error?: string }> {
  // 开发环境跳过验证
  if (env.ENVIRONMENT === 'development') {
    return { valid: true, email: 'dev@soulon.top' }
  }

  const jwt = request.headers.get('CF-Access-JWT-Assertion')
  
  if (!jwt) {
    return { valid: false, error: 'Missing CF-Access-JWT-Assertion header' }
  }

  try {
    const teamName = (env.CF_ACCESS_TEAM_NAME || '').trim()
    const rawAudience = (env.CF_ACCESS_AUD || '').trim()
    const audience = rawAudience
      .split(',')
      .map((v: string) => v.trim())
      .filter((v: string) => v.length > 0)
    if (!teamName || audience.length === 0) {
      return { valid: false, error: 'Missing Cloudflare Access configuration' }
    }

    const issuer = `https://${teamName}.cloudflareaccess.com`
    const { payload } = await jwtVerify(jwt, getAccessJwks(teamName), {
      issuer,
      audience,
      clockTolerance: 5,
    })

    const email = (payload as any)?.email
    if (typeof email !== 'string' || !email.trim()) {
      return { valid: false, error: 'Missing email claim' }
    }

    return { valid: true, email: email.trim() }
  } catch (error) {
    return { valid: false, error: 'Invalid Cloudflare Access token' }
  }
}

/**
 * 创建管理员上下文
 */
export function createAdminContext(email: string, ip?: string | null): AdminContext {
  return {
    adminEmail: email,
    email: email, // alias
    requestId: crypto.randomUUID(),
    ip: ip || null,
  }
}

/**
 * 记录管理员操作日志
 */
export async function logAdminAction(
  env: any,
  context: AdminContext,
  action: string,
  targetType?: string,
  targetId?: string,
  details?: string,
  ipAddress?: string
): Promise<void> {
  // 如果有 D1 数据库，写入日志
  if (env.DB) {
    try {
      await env.DB.prepare(
        `INSERT INTO admin_logs (admin_email, action, target_type, target_id, details, ip_address)
         VALUES (?, ?, ?, ?, ?, ?)`
      ).bind(
        context.adminEmail,
        action,
        targetType || null,
        targetId || null,
        details || null,
        ipAddress || null
      ).run()
    } catch (error) {
      console.error('Failed to log admin action:', error)
    }
  }

  // 同时记录到 console
  console.log(`[Admin Action] ${context.adminEmail} - ${action}`, {
    targetType,
    targetId,
    details,
    requestId: context.requestId,
  })
}

/**
 * 验证管理员权限的装饰器函数
 */
export function withAdminAuth(
  handler: (request: Request, env: any, ctx: any, adminContext: AdminContext) => Promise<Response>
) {
  return async (request: Request, env: any, ctx: any): Promise<Response> => {
    const authResult = await verifyAdminAccess(request, env)
    
    if (!authResult.valid) {
      return new Response(
        JSON.stringify({ error: 'Unauthorized', message: authResult.error }),
        { status: 401, headers: { 'Content-Type': 'application/json' } }
      )
    }

    const adminContext = createAdminContext(authResult.email!)
    return handler(request, env, ctx, adminContext)
  }
}
