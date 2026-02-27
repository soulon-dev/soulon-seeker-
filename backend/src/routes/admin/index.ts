/**
 * 管理后台 API 入口
 * V2: 包含完整业务管理功能
 */

import { verifyAdminAccess, createAdminContext, AdminContext } from './middleware'
import { handleAnalyticsRoutes } from './analytics'
import { handleUsersRoutes } from './users'
import { handleSubscriptionsRoutes } from './subscriptions'
import { handleStakingRoutes } from './staking'
import { handleContentRoutes } from './content'
import { handleConfigRoutes } from './config'
// V2 新增模块
import { handleAppConfigRoutes } from './app-config'
import { handleAiServiceRoutes } from './ai-service'
import { handleMemoRoutes } from './memo'
import { handleAirdropRoutes } from './airdrop'
import { handleBlockchainRoutes } from './blockchain'
import { handleTokenRoutes } from './token'
import { handleSupportAdminRoutes } from './support'
import { handleGameRoutes } from './game'

/**
 * 处理管理后台 API 请求
 */
export async function handleAdminRequest(
  request: Request,
  env: any,
  ctx: any
): Promise<Response | null> {
  const url = new URL(request.url)
  const path = url.pathname
  const requestHost = url.hostname
  const requestOriginHeader = request.headers.get('Origin')
  const allowOrigins = new Set(
    String(env.ADMIN_CORS_ALLOWED_ORIGINS || '')
      .split(',')
      .map((v: string) => v.trim())
      .filter((v: string) => v.length > 0)
  )
  const allowCorsOrigin = (() => {
    if (!requestOriginHeader) return null
    return allowOrigins.has(requestOriginHeader) ? requestOriginHeader : null
  })()
  const corsHeaders: Record<string, string> = allowCorsOrigin
    ? {
      'Access-Control-Allow-Origin': allowCorsOrigin,
      'Access-Control-Allow-Credentials': 'true',
      Vary: 'Origin',
    }
    : { 'Access-Control-Allow-Origin': '*' }

  // 只处理 /admin 开头的路由
  if (!path.startsWith('/admin')) {
    return null
  }

  // CORS 预检请求
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      headers: {
        ...corsHeaders,
        'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, CF-Access-JWT-Assertion',
        'Access-Control-Max-Age': '86400',
      },
    })
  }

  // 验证管理员身份
  const authResult = await verifyAdminAccess(request, env)
  if (!authResult.valid) {
    const requestPathWithQuery = (() => {
      const u = new URL(request.url)
      return `${u.pathname}${u.search}`
    })()
    const firstAud = String(env.CF_ACCESS_AUD || '')
      .split(',')
      .map((v: string) => v.trim())
      .filter((v: string) => v.length > 0)[0] || ''
    const loginUrl =
      (env.CF_ACCESS_TEAM_NAME && firstAud)
        ? `https://${env.CF_ACCESS_TEAM_NAME}.cloudflareaccess.com/cdn-cgi/access/login/${encodeURIComponent(firstAud)}?redirect_url=${encodeURIComponent(`https://${requestHost}${requestPathWithQuery}`)}`
        : ''

    return new Response(
      JSON.stringify({
        error: 'Unauthorized',
        message: authResult.error,
        loginUrl,
      }),
      {
        status: 401,
        headers: {
          'Content-Type': 'application/json',
          ...corsHeaders,
          ...(loginUrl ? { 'X-Access-Login-Url': loginUrl } : {}),
        },
      }
    )
  }

  const clientIp = request.headers.get('CF-Connecting-IP') || request.headers.get('X-Forwarded-For')
  const adminContext = createAdminContext(authResult.email!, clientIp)

  if (request.method === 'GET' && path === '/admin/auth-bridge') {
    const returnToRaw = url.searchParams.get('returnTo') || '/'
    const base = `https://${requestHost}`
    let returnTo = `${base}/`
    try {
      const parsed = new URL(returnToRaw, base)
      const ok = allowOrigins.has(parsed.origin) || parsed.hostname === requestHost
      if (ok) {
        returnTo = parsed.toString()
      }
    } catch {
      returnTo = `${base}/`
    }

    const res = Response.redirect(returnTo, 302)
    const resHeaders: Record<string, string> = {}
    res.headers.forEach((value, key) => {
      resHeaders[key] = value
    })
    return new Response(res.body, {
      status: res.status,
      statusText: res.statusText,
      headers: {
        ...resHeaders,
        'Access-Control-Allow-Origin': '*',
      },
    })
  }

  // 添加 CORS 头的响应包装
  const addCorsHeaders = (response: Response): Response => {
    const newHeaders = new Headers(response.headers)
    Object.entries(corsHeaders).forEach(([k, v]) => newHeaders.set(k, v))
    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers: newHeaders,
    })
  }

  // 尝试各个路由处理器
  let response: Response | null = null

  // Analytics 路由
  response = await handleAnalyticsRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Users 路由
  response = await handleUsersRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Subscriptions 路由
  response = await handleSubscriptionsRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Staking 路由
  response = await handleStakingRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Content 路由
  response = await handleContentRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Config 路由（旧版，保持兼容）
  response = await handleConfigRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // ============================================
  // V2 新增路由
  // ============================================

  // App Config 路由（统一配置中心）
  response = await handleAppConfigRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // AI Service 路由（AI服务管理）
  response = await handleAiServiceRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // MEMO 路由（积分管理）
  response = await handleMemoRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Airdrop 路由（空投管理）
  response = await handleAirdropRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Blockchain 路由（区块链配置）
  response = await handleBlockchainRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Token 路由（代币管理）
  response = await handleTokenRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Support 路由（Bug 报告）
  response = await handleSupportAdminRoutes(request, env, adminContext, path)
  if (response) return addCorsHeaders(response)

  // Game 路由（剧情管理）
  response = await handleGameRoutes(request, env, adminContext, path, ctx)
  if (response) return addCorsHeaders(response)

  // 未匹配的路由
  return new Response(
    JSON.stringify({ error: 'Not Found', path }),
    {
      status: 404,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
      },
    }
  )
}
