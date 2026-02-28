/**
 * Soulon Backend - Cloudflare Workers
 * 
 * API 端点:
 * - POST /api/v1/attestation/challenge - 生成 Attestation Challenge
 * - POST /api/v1/attestation/verify - 验证 Attestation 签名
 * - POST /api/v1/auth/login - 钱包签名登录
 * - GET /api/v1/sovereign/score - 获取 Sovereign Score
 * - GET /api/v1/staking/status - 获取质押状态
 */

import { handleAttestation } from './routes/attestation';
import { handleAuth } from './routes/auth';
import { handleGameRoutes } from './routes/game';
import { handleSovereign } from './routes/sovereign';
import { handleStaking } from './routes/staking';
import { handleAdminRequest } from './routes/admin';
import { handleSyncRoutes } from './routes/sync';
import { handleChatRoutes } from './routes/chat';
import { handlePersonaRoutes } from './routes/persona';
import { handleVectorRoutes } from './routes/vectors';
import { handleQuestionRoutes } from './routes/questions';
import { handleScheduledRenewal, sendRenewalReminders } from './scheduled/subscription-renewal';
import { createAutoRenewSubscription, cancelAutoRenewSubscription, getAutoRenewStatusPublic, reportAutoRenewPaymentResultPublic, getPendingPaymentsPublic, getPendingPlanChangesPublic, markPlanChangeScheduledPublic } from './routes/admin/subscriptions';
import { handleGenesisRoutes } from './routes/genesis';
import { handleSupportRoutes } from './routes/support';
import { handleI18nRoutes } from './routes/i18n';
import { handleJupiterProxy } from './routes/jupiter';
import { handlePrivacyPolicy } from './routes/privacy-policy';
import { handleLicense } from './routes/license';
import { getSolanaRpcUrl } from './utils/solana-rpc';
import { handleCopyright } from './routes/copyright';
import { handleMemoriesRoutes } from './routes/memories';
import { handleIrysUpload } from './routes/irys';
import { handleNftMetadata } from './routes/nft-metadata';
import { getPreferredLang, t } from './i18n';
import { getUserAuth } from './utils/user-auth'
import { checkRateLimit } from './utils/rate-limit'
import { Env } from './types';
import { jsonResponse } from './utils/response';

export { Env, jsonResponse };

const baseCorsHeaders = {
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, PATCH, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization',
}

function parseOriginList(value: string | undefined): Set<string> {
  const raw = (value || '').trim()
  if (!raw) return new Set()
  return new Set(
    raw
      .split(',')
      .map((v) => v.trim())
      .filter((v) => v.length > 0)
  )
}

function isPublicCorsPath(path: string): boolean {
  return (
    path === '/api/v1/config/client' ||
    path.startsWith('/api/v1/i18n') ||
    path.startsWith('/api/v1/questions') ||
    path.startsWith('/nft/') ||
    path === '/health' ||
    path === '/'
  )
}

function getCorsHeaders(request: Request, env: Env, path: string): Record<string, string> {
  const origin = request.headers.get('Origin')
  if (!origin) return { ...baseCorsHeaders }

  if (isPublicCorsPath(path)) {
    return { ...baseCorsHeaders, 'Access-Control-Allow-Origin': '*' }
  }

  const allow =
    path.startsWith('/admin')
      ? parseOriginList(env.ADMIN_CORS_ALLOWED_ORIGINS)
      : parseOriginList(env.CORS_ALLOWED_ORIGINS)

  if (allow.has(origin)) {
    return { ...baseCorsHeaders, 'Access-Control-Allow-Origin': origin, Vary: 'Origin' }
  }

  return { ...baseCorsHeaders }
}

function enforceCsrfBaseline(request: Request, env: Env, path: string): Response | null {
  if (request.method === 'GET' || request.method === 'HEAD' || request.method === 'OPTIONS') return null
  const origin = request.headers.get('Origin')
  if (!origin) return null
  if (isPublicCorsPath(path)) return null

  const allow =
    path.startsWith('/admin')
      ? parseOriginList(env.ADMIN_CORS_ALLOWED_ORIGINS)
      : parseOriginList(env.CORS_ALLOWED_ORIGINS)

  if (!allow.has(origin)) {
    return jsonError('csrf_blocked', 403)
  }
  return null
}

function jsonError(error: string, status: number = 401): Response {
  return new Response(JSON.stringify({ error }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function withCors(response: Response, cors: Record<string, string>): Response {
  const newHeaders = new Headers(response.headers)
  for (const [k, v] of Object.entries(cors)) {
    newHeaders.set(k, v)
  }
  return new Response(response.body, { status: response.status, headers: newHeaders })
}

function getClientIp(request: Request): string {
  const cfIp = request.headers.get('CF-Connecting-IP')
  if (cfIp) return cfIp
  const xff = request.headers.get('X-Forwarded-For') || request.headers.get('x-forwarded-for')
  if (xff) return xff.split(',')[0]?.trim() || 'unknown'
  return 'unknown'
}

async function enforceRateLimit(request: Request, env: Env, path: string, ip: string): Promise<Response | null> {
  if (!env.KV || !ip || ip === 'unknown') return null

  let limit = 0
  let windowSeconds = 60
  if (path === '/api/v1/auth/challenge' || path === '/api/v1/auth/login') {
    limit = 10
  } else if (path.startsWith('/api/v1/attestation/')) {
    limit = 10
  } else if (path.startsWith('/api/v1/ai/')) {
    limit = 30
  } else if (path.startsWith('/admin')) {
    limit = 60
  } else {
    return null
  }

  const bucket = Math.floor(Date.now() / 1000 / windowSeconds)
  const key = `rl:${path}:${ip}:${bucket}`
  const decision = await checkRateLimit(env.KV, key, limit, windowSeconds)
  if (!decision.allowed) {
    return new Response(JSON.stringify({ error: 'rate_limited' }), {
      status: 429,
      headers: {
        'Content-Type': 'application/json',
        'Retry-After': String(decision.retryAfterSeconds),
      },
    })
  }
  return null
}

async function logRequestTelemetry(
  env: Env,
  request: Request,
  response: Response,
  requestId: string,
  startMs: number,
  userIdentity: string | null
): Promise<void> {
  if (!env.DB) return
  if (request.method === 'OPTIONS') return

  const durationMs = Math.max(0, Date.now() - startMs)
  const url = new URL(request.url)
  const path = (url.pathname || '').slice(0, 512)
  const method = (request.method || 'GET').slice(0, 16)
  const status = response.status
  const now = Math.floor(Date.now() / 1000)
  const ip = getClientIp(request)

  let errorType: string | null = null
  if (status >= 500) errorType = 'server_error'
  else if (status === 429) errorType = 'rate_limited'
  else if (status === 401) errorType = 'unauthorized'
  else if (status === 403) errorType = 'forbidden'
  else if (status >= 400) errorType = 'client_error'

  try {
    await env.DB.prepare(
      `INSERT INTO admin_request_logs
        (request_id, path, method, status, duration_ms, error_type, user_identity, ip, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      requestId,
      path,
      method,
      status,
      durationMs,
      errorType,
      userIdentity,
      ip,
      now
    ).run()
  } catch {}
}

function extractWalletFromPath(path: string): string | null {
  const m = path.match(/^\/api\/v1\/user\/([^/]+)\//)
  if (m) return m[1]
  const m2 = path.match(/^\/api\/v1\/subscription\/auto-renew\/([^/]+)/)
  if (m2) return m2[1]
  return null
}

function requiresUserAuth(path: string): boolean {
  if (path === '/api/v1/config/client') return false
  if (path.startsWith('/api/v1/i18n')) return false
  if (path.startsWith('/api/v1/questions')) return false
  if (path.startsWith('/api/v1/auth')) return false
  if (path.startsWith('/api/v1/attestation')) return false
  if (path.startsWith('/api/v1/solana/')) return false
  if (path.startsWith('/api/v1/jupiter/')) return false
  if (path.startsWith('/api/v1/subscription/genesis/')) return false
  if (path === '/health' || path === '/') return false

  if (path.startsWith('/api/v1/debug/')) return true
  if (path.startsWith('/api/v1/users/')) return true
  if (path.startsWith('/api/v1/user/')) return true
  if (path.startsWith('/api/v1/chat')) return true
  if (path.startsWith('/api/v1/chats/')) return true
  if (path.startsWith('/api/v1/memories/')) return true
  if (path.startsWith('/api/v1/persona')) return true
  if (path.startsWith('/api/v1/vectors')) return true
  if (path.startsWith('/api/v1/staking')) return true
  if (path.startsWith('/api/v1/subscriptions/')) return true
  if (path.startsWith('/api/v1/subscription/executor/')) return false
  if (path.startsWith('/api/v1/subscription/')) return true
  if (path.startsWith('/api/v1/push/')) return true
  if (path.startsWith('/api/v1/ai/')) return true
  if (path.startsWith('/api/v1/game/')) return true
  return false
}

async function injectWalletIntoJsonRequest(request: Request, walletAddress: string): Promise<Request> {
  const contentType = request.headers.get('Content-Type') || request.headers.get('content-type') || ''
  if (!contentType.toLowerCase().includes('application/json')) return request
  const raw = await request.text()
  const obj = raw ? JSON.parse(raw) : {}
  if (obj && typeof obj === 'object') {
    ;(obj as any).walletAddress = walletAddress
    ;(obj as any).wallet_address = walletAddress
    ;(obj as any).wallet = walletAddress
  }
  const headers = new Headers(request.headers)
  headers.set('Content-Type', 'application/json')
  return new Request(request.url, {
    method: request.method,
    headers,
    body: JSON.stringify(obj),
  })
}

export default {
  // 定时任务处理（自动续费扣款）
  async scheduled(event: any, env: Env, ctx: any): Promise<void> {
    console.log(`[Scheduled] Trigger: ${event.cron || 'manual'}`);
    
    // 根据触发时间执行不同任务
    const hour = new Date().getUTCHours();
    
    // 每小时执行续费检查
    await handleScheduledRenewal(env);
    
    // 每天 UTC 9:00 (北京时间 17:00) 发送续费提醒
    if (hour === 9) {
      await sendRenewalReminders(env);
    }
  },

  async fetch(request: Request, env: Env, ctx: any): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const lang = getPreferredLang(request);
    const cors = getCorsHeaders(request, env, path)
    const requestId = (globalThis as any).crypto?.randomUUID
      ? (globalThis as any).crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const startMs = Date.now()
    const finalize = (response: Response, userIdentity: string | null): Response => {
      const resp = withCors(response, cors)
      resp.headers.set('X-Request-Id', requestId)
      resp.headers.set('X-Response-Time', String(Date.now() - startMs))
      ctx.waitUntil(logRequestTelemetry(env, request, resp, requestId, startMs, userIdentity))
      return resp
    }

    // 处理 CORS 预检请求
    if (request.method === 'OPTIONS') {
      const resp = new Response(null, { headers: cors })
      resp.headers.set('X-Request-Id', requestId)
      resp.headers.set('X-Response-Time', String(Date.now() - startMs))
      return resp
    }

    const nft = await handleNftMetadata(request, env, path)
    if (nft) {
      return finalize(nft, null)
    }

    const csrf = enforceCsrfBaseline(request, env, path)
    if (csrf) {
      return finalize(csrf, null)
    }

    const ip = getClientIp(request)
    const limited = await enforceRateLimit(request, env, path, ip)
    if (limited) {
      return finalize(limited, null)
    }

    let req: Request = request
    let authWallet: string | null = null

    if (requiresUserAuth(path)) {
      const auth = await getUserAuth(request, env)
      if (!auth.ok) {
        console.error(`[Auth] Failed for ${path}: ${auth.error}`);
        return finalize(jsonResponse({ error: 'unauthorized', detail: auth.error }, 401), null)
      }
      authWallet = auth.walletAddress
      ;(req as any).walletAddress = authWallet

      const walletFromPath = extractWalletFromPath(path)
      if (walletFromPath && walletFromPath !== authWallet) {
        return finalize(jsonError('forbidden', 403), authWallet)
      }

      const walletFromQuery = url.searchParams.get('wallet') || url.searchParams.get('walletAddress')
      if (walletFromQuery && walletFromQuery !== authWallet) {
        return finalize(jsonError('forbidden', 403), authWallet)
      }

      if (request.method !== 'GET' && request.method !== 'HEAD') {
        req = await injectWalletIntoJsonRequest(request, authWallet)
        ;(req as any).walletAddress = authWallet
      }
    }

    try {
      let response: Response | null;

      // 管理后台 API 路由（优先处理）
      if (path.startsWith('/admin')) {
        response = await handleAdminRequest(request, env, ctx);
        if (response) {
          return finalize(response, null)
        }
      }

      // 应用身份验证端点（MWA 钱包验证用）
      const host = (request.headers.get('host') || '').toLowerCase()
      if (host.startsWith('privacy.') || path === '/privacy' || path === '/privacy-policy' || path === '/license' || path === '/copyright') {
        if (path === '/license') {
          response = await handleLicense(request, env)
        } else if (path === '/copyright') {
          response = await handleCopyright(request, env)
        } else {
          response = await handlePrivacyPolicy(request, env)
        }
      }
      else if (path === '/' || path === '/index.html') {
        response = new Response(
          `<!DOCTYPE html><html><head><title>Soulon</title></head><body><h1>Soulon - Solana dApp</h1></body></html>`,
          { headers: { 'Content-Type': 'text/html' } }
        );
      }
      // 应用图标（MWA 钱包显示用）
      else if (path === '/icon.png' || path === '/favicon.ico') {
        // 返回一个简单的 SVG 作为图标
        const svgIcon = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="#9945FF"/><text x="50" y="65" font-size="40" text-anchor="middle" fill="white">M</text></svg>`;
        response = new Response(svgIcon, {
          headers: { 'Content-Type': 'image/svg+xml' }
        });
      }
      else if (path === '/cdn-cgi/access/login') {
        const redirectUrl = url.searchParams.get('redirect_url') || url.searchParams.get('redirectUrl') || ''
        if (!redirectUrl) {
          response = jsonResponse({ error: 'missing_redirect_url' }, 400)
        } else if (env.CF_ACCESS_TEAM_NAME && env.CF_ACCESS_AUD) {
          const loginUrl = `https://${env.CF_ACCESS_TEAM_NAME}.cloudflareaccess.com/cdn-cgi/access/login/${env.CF_ACCESS_AUD}?redirect_url=${encodeURIComponent(redirectUrl)}`
          response = Response.redirect(loginUrl, 302)
        } else {
          response = jsonResponse({ error: 'access_not_configured', message: 'Missing CF_ACCESS_TEAM_NAME/CF_ACCESS_AUD' }, 500)
        }
      }
      // 客户端配置 API（公开端点，无需认证）
      else if (path === '/api/v1/config/client') {
        response = await handleClientConfig(env);
      }
      // AI 代理端点（替代 API Key 下发）
      else if (path === '/api/v1/ai/proxy/completions') {
        response = await handleAiProxy(req, env);
      }
      else if (path === '/api/v1/ai/quota/status') {
        response = await handleAiQuotaStatus(req, env);
      }
      // Embedding 代理端点（替代 API Key 下发）
      else if (path === '/api/v1/ai/proxy/embeddings') {
        response = await handleAiEmbeddingProxy(req, env);
      }
      // AI 配置调试端点
      else if (path === '/api/v1/debug/ai-config') {
        response = await handleAiConfigDebug(env);
      }
      // 数据同步 API 路由
      else if (path.startsWith('/api/v1/users/sync') || 
          path.startsWith('/api/v1/users/profile') ||
          path.startsWith('/api/v1/users/full-profile') ||
          path.startsWith('/api/v1/subscriptions/sync') ||
          path.startsWith('/api/v1/staking/sync') ||
          path.startsWith('/api/v1/chats/log') ||
          path.startsWith('/api/v1/memories/log')) {
        response = await handleSyncRoutes(req, env, path);
      }
      // 🆕 Irys 后端代付上传 (付费用户)
      else if (path === '/api/v1/memories/upload' && request.method === 'POST') {
        response = await handleIrysUpload(req, env);
      }
      else if (path.startsWith('/api/v1/memories/blob')) {
        const memResponse = await handleMemoriesRoutes(req, env, path)
        if (memResponse) {
          response = memResponse
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 聊天数据 API 路由
      else if (path.startsWith('/api/v1/chat')) {
        const chatResponse = await handleChatRoutes(req, env, path);
        if (chatResponse) {
          response = chatResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 人格数据 API 路由
      else if (path.startsWith('/api/v1/persona')) {
        const personaResponse = await handlePersonaRoutes(req, env, path);
        if (personaResponse) {
          response = personaResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 向量数据 API 路由
      else if (path.startsWith('/api/v1/vectors')) {
        const vectorResponse = await handleVectorRoutes(req, env, path);
        if (vectorResponse) {
          response = vectorResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 主动提问 API 路由
      else if (path.startsWith('/api/v1/questions')) {
        const questionResponse = await handleQuestionRoutes(req, env, path);
        if (questionResponse) {
          response = questionResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 支持/反馈 API 路由
      else if (path.startsWith('/api/v1/support')) {
        const supportResponse = await handleSupportRoutes(req, env, path);
        if (supportResponse) {
          response = supportResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // i18n 翻译 API 路由
      else if (path.startsWith('/api/v1/i18n')) {
        const i18nResponse = await handleI18nRoutes(req, env, path);
        if (i18nResponse) {
          response = i18nResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 路由分发
      else if (path.startsWith('/api/v1/attestation')) {
        response = await handleAttestation(req, env, path);
      } else if (path.startsWith('/api/v1/auth')) {
        response = await handleAuth(req, env, path);
      } else if (path.startsWith('/api/v1/sovereign')) {
        response = await handleSovereign(req, env, path);
      }
      // 质押数据 API 路由
      else if (path.startsWith('/api/v1/staking')) {
        const stakingResponse = await handleStaking(req, env, path);
        if (stakingResponse) {
          response = stakingResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 公开的自动续费 API（APP 调用）
      else if (path === '/api/v1/subscription/auto-renew' && request.method === 'POST') {
        response = await createAutoRenewSubscription(req, env, null);
      } else if (path.match(/^\/api\/v1\/subscription\/auto-renew\/([^/]+)$/) && request.method === 'GET') {
        const walletAddress = path.split('/')[5];
        response = await getAutoRenewStatusPublic(req, env, walletAddress);
      } else if (path.match(/^\/api\/v1\/subscription\/auto-renew\/([^/]+)\/cancel$/) && request.method === 'POST') {
        const walletAddress = path.split('/')[5];
        response = await cancelAutoRenewSubscription(req, env, null, walletAddress);
      } else if (path === '/api/v1/subscription/payment-result' && request.method === 'POST') {
        response = await reportAutoRenewPaymentResultPublic(req, env);
      } else if (path === '/api/v1/subscription/executor/pending-payments' && request.method === 'GET') {
        response = await getPendingPaymentsPublic(req, env);
      } else if (path === '/api/v1/subscription/executor/pending-plan-changes' && request.method === 'GET') {
        response = await getPendingPlanChangesPublic(req, env);
      } else if (path === '/api/v1/subscription/executor/plan-change-scheduled' && request.method === 'POST') {
        response = await markPlanChangeScheduledPublic(req, env);
      // FCM Token 注册 API
      } else if (path === '/api/v1/push/register' && request.method === 'POST') {
        response = await registerFcmToken(req, env);
      // 用户档案 API（保存/获取 onboarding 状态）
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/profile$/) && request.method === 'GET') {
        const walletAddress = path.split('/')[4];
        response = await getUserProfile(req, env, walletAddress);
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/profile$/) && request.method === 'POST') {
        const walletAddress = path.split('/')[4];
        response = await saveUserProfile(req, env, walletAddress);
      // 资源防护 API - 签到
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/check-in$/) && request.method === 'POST') {
        const walletAddress = path.split('/')[4];
        response = await processCheckIn(req, env, walletAddress);
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/check-in$/) && request.method === 'GET') {
        const walletAddress = path.split('/')[4];
        response = await getCheckInStatus(req, env, walletAddress);
      // 资源防护 API - 奇遇
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/adventure$/) && request.method === 'POST') {
        const walletAddress = path.split('/')[4];
        response = await completeAdventure(req, env, walletAddress);
      // 资源防护 API - 对话奖励
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/dialogue-reward$/) && request.method === 'POST') {
        const walletAddress = path.split('/')[4];
        response = await recordDialogueReward(req, env, walletAddress);
      // 资源防护 API - 交易历史
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/transactions$/) && request.method === 'GET') {
        const walletAddress = path.split('/')[4];
        response = await getTransactionHistory(req, env, walletAddress);
      // 🆕 实时余额 API（后端优先架构核心端点）
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/balance$/) && request.method === 'GET') {
        const walletAddress = path.split('/')[4];
        response = await getRealTimeBalance(req, env, walletAddress);
      // 🔧 同步余额 API（从交易日志重新计算 memo_balance）
      } else if (path.match(/^\/api\/v1\/user\/([^/]+)\/sync-balance$/) && request.method === 'POST') {
        const walletAddress = path.split('/')[4];
        response = await syncMemoBalance(req, env, walletAddress);
      }
      // 🆕 Great Voyage Game API
      else if (path.startsWith('/api/v1/game/')) {
        const gameResponse = await handleGameRoutes(req, env, path);
        if (gameResponse) {
          response = gameResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      }
      // 🆕 Solana 链上操作代理 API
      else if (path.startsWith('/api/v1/solana/')) {
        response = await handleSolanaProxy(req, env, path);
      // 🆕 Jupiter API 代理（避免在客户端暴露 API Key）
      } else if (path.startsWith('/api/v1/jupiter/')) {
        response = await handleJupiterProxy(req, env, path);
      // 🆕 Genesis Token 试用相关 API
      } else if (path.startsWith('/api/v1/subscription/genesis/')) {
        const genesisResponse = await handleGenesisRoutes(req, env, path);
        if (genesisResponse) {
          response = genesisResponse;
        } else {
          response = jsonResponse({ error: t('not_found', lang) }, 404);
        }
      } else if (path === '/health' || path === '/') {
        response = jsonResponse({
          status: 'ok',
          service: 'Soulon Backend',
          version: env.APP_VERSION || 'unknown',
          gitSha: env.GIT_SHA || null,
          environment: env.ENVIRONMENT,
          timestamp: new Date().toISOString(),
          adminEndpoint: '/admin',
        });
      } else {
        response = jsonResponse({ error: t('not_found', lang) }, 404);
      }

      if (!response) {
        response = jsonResponse({ error: t('not_found', lang) }, 404);
      }

      return finalize(response, authWallet)

    } catch (error) {
      console.error('Request error:', error);
      return finalize(jsonResponse(
        { error: t('internal_server_error', lang), message: (error as Error).message },
        500
      ), authWallet)
    }
  },
};



/**
 * 处理客户端配置请求（公开端点）
 * 返回非敏感配置供客户端使用
 */
async function handleClientConfig(env: Env): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    // 获取所有非敏感配置
    const result = await env.DB.prepare(
      `SELECT config_key as configKey, config_value as configValue, value_type as valueType, category
       FROM app_config 
       WHERE is_active = 1 AND (is_sensitive = 0 OR config_key = 'blockchain.recipient_wallet')
       ORDER BY category, config_key`
    ).all();

    // 按分类分组
    const configs: Record<string, any[]> = {};
    for (const row of result.results as any[]) {
      if (!configs[row.category]) {
        configs[row.category] = [];
      }
      
      let finalValue = row.configValue;
      
      // Special handling for signed configs
      if (row.configKey === 'blockchain.recipient_wallet' && env.KV) {
          const signedValue = await env.KV.get(`config:${row.configKey}`);
          if (signedValue) {
              finalValue = signedValue; // Use the signed JSON from KV
          }
      }

      configs[row.category].push({
        configKey: row.configKey,
        configValue: finalValue,
        valueType: row.valueType,
      });
    }

    // 获取收款钱包地址（从 wallet_addresses 表）
    try {
      const walletResult = await env.DB.prepare(
        `SELECT address FROM wallet_addresses 
         WHERE type = 'recipient' AND is_active = 1 
         ORDER BY created_at DESC LIMIT 1`
      ).first();

      if (walletResult && walletResult.address) {
        // 添加到 payment 分类
        if (!configs['payment']) {
          configs['payment'] = [];
        }
        configs['payment'].push({
          configKey: 'payment.recipient_wallet',
          configValue: walletResult.address,
          valueType: 'string',
        });
        console.log(`Client config: Added recipient wallet ${(walletResult.address as string).substring(0, 8)}...`);
      }
    } catch (walletError) {
      console.error('Error fetching recipient wallet:', walletError);
      // 继续返回其他配置，不因钱包地址获取失败而中断
    }

    const ensureConfig = (category: string, key: string, value: string, valueType: string = 'string') => {
      if (!configs[category]) configs[category] = [];
      const exists = configs[category].some((c: any) => c.configKey === key);
      if (!exists) {
        configs[category].push({ configKey: key, configValue: value, valueType });
      }
    };

    const getConfigValue = (key: string): string | undefined => {
      for (const items of Object.values(configs)) {
        const hit = (items as any[]).find((c: any) => c.configKey === key);
        if (hit) return hit.configValue;
      }
      return undefined;
    };

    ensureConfig('subscription', 'subscription.badge.yearly', '推荐', 'string');
    ensureConfig('subscription', 'subscription.badge.quarterly', '推荐', 'string');
    ensureConfig('subscription', 'subscription.monthly_usdc', '9.99', 'number');
    ensureConfig('subscription', 'subscription.quarterly_usdc', '24.99', 'number');
    ensureConfig('subscription', 'subscription.yearly_usdc', '79.99', 'number');
    ensureConfig('subscription', 'subscription.monthly_token_multiplier', '2.0', 'number');
    ensureConfig('subscription', 'subscription.quarterly_token_multiplier', '3.0', 'number');
    ensureConfig('subscription', 'subscription.yearly_token_multiplier', '5.0', 'number');
    ensureConfig('subscription', 'subscription.monthly_points_multiplier', '1.5', 'number');
    ensureConfig('subscription', 'subscription.quarterly_points_multiplier', '2.0', 'number');
    ensureConfig('subscription', 'subscription.yearly_points_multiplier', '3.0', 'number');

    const hasPlansConfig = !!getConfigValue('subscription.plans');
    if (!hasPlansConfig) {
      const monthlyUsdc = parseFloat(getConfigValue('subscription.monthly_usdc') || '9.99');
      const quarterlyUsdc = parseFloat(getConfigValue('subscription.quarterly_usdc') || '24.99');
      const yearlyUsdc = parseFloat(getConfigValue('subscription.yearly_usdc') || '79.99');
      const monthlyTokenMult = parseFloat(getConfigValue('subscription.monthly_token_multiplier') || '2.0');
      const quarterlyTokenMult = parseFloat(getConfigValue('subscription.quarterly_token_multiplier') || '3.0');
      const yearlyTokenMult = parseFloat(getConfigValue('subscription.yearly_token_multiplier') || '5.0');
      const monthlyPointsMult = parseFloat(getConfigValue('subscription.monthly_points_multiplier') || '1.5');
      const quarterlyPointsMult = parseFloat(getConfigValue('subscription.quarterly_points_multiplier') || '2.0');
      const yearlyPointsMult = parseFloat(getConfigValue('subscription.yearly_points_multiplier') || '3.0');

      const fixed = (n: number) => (Number.isFinite(n) ? n : 0);
      const perMonth = (total: number, months: number) => fixed(total) / months;

      ensureConfig('subscription', 'subscription.plans', JSON.stringify({
        version: 1,
        defaultSelectedId: 'yearly',
        uiRules: {
          hidePlans: [
            {
              planIds: ['monthly_continuous'],
              when: {
                any: [
                  { autoRenewPlanTypeIn: [2] },
                  { pendingPlanTypeIn: [2] },
                  { activeSubscriptionTypeIn: ['quarterly_continuous'] }
                ]
              }
            }
          ],
          disallowSelect: [
            {
              planIds: ['monthly_continuous'],
              message: '连续包季会员不可直接降级为连续包月。请先在订阅管理取消订阅，待到期后再订阅连续包月会员。',
              when: {
                any: [
                  { activeSubscriptionTypeIn: ['quarterly_continuous'] },
                  { autoRenewPlanTypeIn: [2] },
                  { pendingPlanTypeIn: [2] }
                ]
              }
            }
          ],
          autoRenewUpgrade: [
            {
              fromPlanType: 1,
              toPlanType: 2,
              targetPlanIds: ['quarterly_continuous'],
              action: 'schedule_change',
              title: '确认升级',
              description: '将把当前连续包月升级为连续包季。升级将于当前周期到期后生效，届时开始按季度扣款。升级后的第一笔扣款前不可取消订阅合约。',
              lockCancelUntilEffective: true
            }
          ]
        },
        plans: [
          {
            id: 'monthly_continuous',
            basePlanId: 'monthly',
            name: '连续包月',
            shortName: '连续包月',
            priceUsdc: fixed(monthlyUsdc),
            renewalPriceUsdc: fixed(monthlyUsdc),
            pricePerMonth: `≈ $${fixed(monthlyUsdc).toFixed(2)}/月`,
            duration: '1 个月',
            durationMonths: 1,
            autoRenew: true,
            badgeText: null,
            savings: null,
            tokenMultiplier: fixed(monthlyTokenMult),
            pointsMultiplier: fixed(monthlyPointsMult),
            features: [
              '解锁生态质押功能',
              `每月 Token 限额提升 ${fixed(monthlyTokenMult).toFixed(0)} 倍`,
              `积分累积加速 ${fixed(monthlyPointsMult).toFixed(1)}x`,
              '专属客服支持'
            ]
          },
          {
            id: 'monthly',
            basePlanId: 'monthly',
            name: '月费',
            shortName: '月费',
            priceUsdc: fixed(monthlyUsdc),
            renewalPriceUsdc: null,
            pricePerMonth: `≈ $${fixed(monthlyUsdc).toFixed(2)}/月`,
            duration: '1 个月',
            durationMonths: 1,
            autoRenew: false,
            badgeText: null,
            savings: null,
            tokenMultiplier: fixed(monthlyTokenMult),
            pointsMultiplier: fixed(monthlyPointsMult),
            features: [
              '解锁生态质押功能',
              `每月 Token 限额提升 ${fixed(monthlyTokenMult).toFixed(0)} 倍`,
              `积分累积加速 ${fixed(monthlyPointsMult).toFixed(1)}x`,
              '专属客服支持'
            ]
          },
          {
            id: 'yearly',
            basePlanId: 'yearly',
            name: '12 个月',
            shortName: '12个月',
            priceUsdc: fixed(yearlyUsdc),
            renewalPriceUsdc: null,
            pricePerMonth: `≈ $${perMonth(yearlyUsdc, 12).toFixed(2)}/月`,
            duration: '12 个月',
            durationMonths: 12,
            autoRenew: false,
            badgeText: '推荐',
            savings: null,
            tokenMultiplier: fixed(yearlyTokenMult),
            pointsMultiplier: fixed(yearlyPointsMult),
            features: [
              '包含季度会员所有权益',
              `每月 Token 限额提升 ${fixed(yearlyTokenMult).toFixed(0)} 倍`,
              `积分累积加速 ${fixed(yearlyPointsMult).toFixed(1)}x`,
              '专属空投资格',
              '治理投票权'
            ]
          },
          {
            id: 'quarterly',
            basePlanId: 'quarterly',
            name: '3 个月',
            shortName: '3个月',
            priceUsdc: fixed(quarterlyUsdc),
            renewalPriceUsdc: null,
            pricePerMonth: `≈ $${perMonth(quarterlyUsdc, 3).toFixed(2)}/月`,
            duration: '3 个月',
            durationMonths: 3,
            autoRenew: false,
            badgeText: '推荐',
            savings: null,
            tokenMultiplier: fixed(quarterlyTokenMult),
            pointsMultiplier: fixed(quarterlyPointsMult),
            features: [
              '包含月费所有权益',
              `每月 Token 限额提升 ${fixed(quarterlyTokenMult).toFixed(0)} 倍`,
              `积分累积加速 ${fixed(quarterlyPointsMult).toFixed(1)}x`,
              '优先体验新功能'
            ]
          },
          {
            id: 'quarterly_continuous',
            basePlanId: 'quarterly',
            name: '连续包季',
            shortName: '连续包季',
            priceUsdc: fixed(quarterlyUsdc),
            renewalPriceUsdc: fixed(quarterlyUsdc),
            pricePerMonth: `≈ $${perMonth(quarterlyUsdc, 3).toFixed(2)}/月`,
            duration: '3 个月',
            durationMonths: 3,
            autoRenew: true,
            badgeText: null,
            savings: null,
            tokenMultiplier: fixed(quarterlyTokenMult),
            pointsMultiplier: fixed(quarterlyPointsMult),
            features: [
              '包含月费所有权益',
              `每月 Token 限额提升 ${fixed(quarterlyTokenMult).toFixed(0)} 倍`,
              `积分累积加速 ${fixed(quarterlyPointsMult).toFixed(1)}x`,
              '优先体验新功能'
            ]
          },
          {
            id: 'monthly_one_time',
            basePlanId: 'monthly',
            name: '一个月',
            shortName: '一个月',
            priceUsdc: fixed(monthlyUsdc),
            renewalPriceUsdc: null,
            pricePerMonth: `≈ $${fixed(monthlyUsdc).toFixed(2)}/月`,
            duration: '1 个月',
            durationMonths: 1,
            autoRenew: false,
            badgeText: null,
            savings: null,
            tokenMultiplier: fixed(monthlyTokenMult),
            pointsMultiplier: fixed(monthlyPointsMult),
            features: [
              '解锁生态质押功能',
              `每月 Token 限额提升 ${fixed(monthlyTokenMult).toFixed(0)} 倍`,
              `积分累积加速 ${fixed(monthlyPointsMult).toFixed(1)}x`,
              '专属客服支持'
            ]
          }
        ]
      }), 'json');
    }

    return jsonResponse({
      configs,
      total: result.results.length,
      syncedAt: new Date().toISOString(),
    });
  } catch (error) {
    console.error('Error fetching client config:', error);
    return jsonResponse({ error: 'Failed to fetch config' }, 500);
  }
}

/**
 * AI 配置调试端点
 * 用于诊断 API 密钥配置问题
 */
async function handleAiConfigDebug(env: Env): Promise<Response> {
  if (env.ENVIRONMENT !== 'development') {
    return jsonResponse({ error: 'Not Found' }, 404);
  }
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available', dbAvailable: false }, 500);
  }

  try {
    // 查询所有 API 密钥（脱敏）
    const allKeys = await env.DB.prepare(
      `SELECT id, name, service, key_preview, endpoint_url, is_primary, is_active, 
              created_at, last_used_at, last_error
       FROM api_keys ORDER BY service, is_primary DESC`
    ).all();

    // 查询主 Qwen 密钥
    const qwenKey = await env.DB.prepare(
      `SELECT id, name, service, key_preview, encrypted_key, endpoint_url, is_primary, is_active
       FROM api_keys
       WHERE service = 'qwen' AND is_active = 1
       ORDER BY is_primary DESC, updated_at DESC, created_at DESC
       LIMIT 1`
    ).first();

    // 查询主 Embedding 密钥
    const embeddingKey = await env.DB.prepare(
      `SELECT id, name, service, key_preview, encrypted_key, endpoint_url, is_primary, is_active
       FROM api_keys
       WHERE service = 'embedding' AND is_active = 1
       ORDER BY is_primary DESC, updated_at DESC, created_at DESC
       LIMIT 1`
    ).first();

    return jsonResponse({
      dbAvailable: true,
      secretConfigured: !!(env.QWEN_API_KEY && env.QWEN_API_KEY.trim().length > 0),
      totalKeys: allKeys.results?.length || 0,
      keys: (allKeys.results || []).map((k: any) => ({
        id: k.id,
        name: k.name,
        service: k.service,
        keyPreview: k.key_preview,
        endpointUrl: k.endpoint_url,
        isPrimary: !!k.is_primary,
        isActive: !!k.is_active,
        lastError: k.last_error,
      })),
      qwenConfig: qwenKey ? {
        found: true,
        id: qwenKey.id,
        name: qwenKey.name,
        keyPreview: qwenKey.key_preview,
        encryptedKeyLength: (qwenKey.encrypted_key as string)?.length,
        endpointUrl: qwenKey.endpoint_url,
        isPrimary: !!qwenKey.is_primary,
        isActive: !!qwenKey.is_active,
      } : { found: false },
      embeddingConfig: embeddingKey ? {
        found: true,
        id: embeddingKey.id,
        name: embeddingKey.name,
        keyPreview: embeddingKey.key_preview,
      } : { found: false, note: 'Will use Qwen key if available' },
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    console.error('Error in AI config debug:', error);
    return jsonResponse({ 
      error: 'Debug failed', 
      message: (error as Error).message 
    }, 500);
  }
}

/**
 * AI 代理处理函数（后端代理模式）
 * 接收客户端 Prompt，注入 API Key 后转发给 AI 服务商
 */
async function handleAiProxy(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405);
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const body = await request.json() as any;
    const walletAddress = (body.walletAddress || request.headers.get('X-Wallet-Address') || '').toString();
    if (!walletAddress) {
      return jsonResponse({ error: 'missing_wallet_address' }, 400);
    }
    
    let monthlyStatsReady = (globalThis as any).__monthlyStatsReady as boolean | undefined;
    if (!monthlyStatsReady) {
      await env.DB.prepare(
        `CREATE TABLE IF NOT EXISTS user_monthly_stats (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id TEXT NOT NULL,
          wallet_address TEXT NOT NULL,
          stat_month TEXT NOT NULL,
          tokens_used INTEGER DEFAULT 0,
          created_at INTEGER DEFAULT (unixepoch()),
          updated_at INTEGER DEFAULT (unixepoch()),
          UNIQUE(user_id, stat_month)
        )`
      ).run();
      (globalThis as any).__monthlyStatsReady = true;
    }

    let aiUsageReady = (globalThis as any).__aiUsageReady as boolean | undefined;
    if (!aiUsageReady) {
      await env.DB.prepare(
        `CREATE TABLE IF NOT EXISTS ai_usage_logs (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id TEXT,
          wallet_address TEXT,
          api_key_id INTEGER,
          model TEXT NOT NULL,
          function_type TEXT NOT NULL,
          prompt_tokens INTEGER NOT NULL,
          completion_tokens INTEGER NOT NULL,
          total_tokens INTEGER NOT NULL,
          cost_usd REAL,
          latency_ms INTEGER,
          success INTEGER DEFAULT 1,
          error_message TEXT,
          request_id TEXT,
          created_at INTEGER DEFAULT (unixepoch())
        )`
      ).run();
      (globalThis as any).__aiUsageReady = true;
    }

    const nowSec = Math.floor(Date.now() / 1000);
    const now = new Date(nowSec * 1000);
    const statDate = now.toISOString().slice(0, 10);
    const statMonth = `${now.getUTCFullYear()}${String(now.getUTCMonth() + 1).padStart(2, '0')}`;

    const dailyLimit = 6000;
    const monthlyLimit = 140000;
    const maxTokensHardCap = 2048;

    const ensureUser = async (): Promise<string> => {
      const existing = await env.DB!.prepare(
        `SELECT id FROM users WHERE wallet_address = ? LIMIT 1`
      ).bind(walletAddress).first();
      if (existing?.id) return existing.id as string;
      const newId = `user_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;
      await env.DB!.prepare(
        `INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, created_at, last_active_at)
         VALUES (?, ?, 0, 1, 'FREE', ?, ?)`
      ).bind(newId, walletAddress, nowSec, nowSec).run();
      return newId;
    };

    const userId = await ensureUser();

    const dailyRow = await env.DB.prepare(
      `SELECT tokens_used FROM user_daily_stats WHERE user_id = ? AND stat_date = ? LIMIT 1`
    ).bind(userId, statDate).first();
    const dailyUsed = (dailyRow?.tokens_used as number | undefined) ?? 0;

    const monthlyRow = await env.DB.prepare(
      `SELECT tokens_used FROM user_monthly_stats WHERE user_id = ? AND stat_month = ? LIMIT 1`
    ).bind(userId, statMonth).first();
    const monthlyUsed = (monthlyRow?.tokens_used as number | undefined) ?? 0;

    if (monthlyUsed >= monthlyLimit) {
      return jsonResponse({ error: 'monthly_quota_exceeded', monthlyUsed, monthlyLimit }, 429);
    }

    const normalizeMessages = (input: any[]): any[] => {
      const normalized = input
        .filter((m) => m && typeof m === 'object')
        .map((m) => ({
          ...m,
          role: (m.role || '').toString().toLowerCase(),
          content: (m.content ?? '').toString(),
        }));

      const systemMessages = normalized.filter((m) => m.role === 'system');
      const nonSystemMessages = normalized.filter((m) => m.role !== 'system');

      if (systemMessages.length === 0) return nonSystemMessages;

      const mergedSystemContent = systemMessages
        .map((m) => m.content)
        .filter((c) => c && c.trim().length > 0)
        .join('\n\n')
        .trim();

      if (!mergedSystemContent) return nonSystemMessages;

      return [
        { role: 'system', content: mergedSystemContent },
        ...nonSystemMessages,
      ];
    };

    const rawMessages = Array.isArray(body.messages) ? body.messages : [];
    const messages = normalizeMessages(rawMessages);
    const lastUserContent = [...messages].reverse().find((m: any) => m?.role === 'user')?.content?.toString() || '';

    const functionType = (body.function_type || 'conversation').toString();

    const isHighQuality = (() => {
      if (messages.length >= 8) return true;
      if (lastUserContent.length >= 120) return true;
      if (/```|stack|trace|bug|error|kotlin|swift|typescript|sql|方案|设计|实现|步骤|对比|分析|权衡/i.test(lastUserContent)) return true;
      if (/(必须|要求|至少|不少于|不要|改成|实现|支持).{0,20}(并且|同时|另外)/.test(lastUserContent)) return true;
      return false;
    })();

    const requestedModel = (body.model || '').toString();
    const selectedModel =
      !requestedModel || requestedModel === 'auto'
        ? (() => {
            if (functionType === 'persona') return 'qwen-flash';
            if (functionType === 'analysis') return 'qwen-flash';
            if (functionType === 'questionnaire') return 'qwen-flash';
            return isHighQuality ? 'qwen-max' : 'qwen-flash';
          })()
        : requestedModel;

    const shouldEnableSearch = (() => {
      if (typeof body.enable_search === 'boolean') return body.enable_search;
      if (functionType === 'persona' || functionType === 'analysis' || functionType === 'questionnaire') return false;
      if (/今天|最新|实时|价格|汇率|天气|新闻|发布|版本|what('?s)? new|latest|today|price|rate|weather|stock/i.test(lastUserContent)) return true;
      return false;
    })();

    const upstreamModel = (() => {
      if (!shouldEnableSearch) return selectedModel;
      if (selectedModel.startsWith('qwen3-max')) return selectedModel;
      return 'qwen3-max';
    })();

    const startMs = Date.now();

    const maxTokens = Math.min(
      Number.isFinite(body.max_tokens) ? body.max_tokens : parseInt(body.max_tokens || '0') || 0,
      maxTokensHardCap
    ) || 1000;

    const estimatePromptTokens = (() => {
      const roughChars = messages.reduce((sum: number, m: any) => sum + ((m?.content || '').toString().length), 0);
      return Math.ceil(roughChars / 4) + messages.length * 5;
    })();
    const estimateTotalTokens = estimatePromptTokens + maxTokens;
    if (monthlyUsed + estimateTotalTokens > monthlyLimit) {
      return jsonResponse(
        { error: 'monthly_quota_would_exceed', monthlyUsed, monthlyLimit, estimatedTokens: estimateTotalTokens },
        429
      );
    }

    const textEncoder = new TextEncoder();
    const textDecoder = new TextDecoder();

    const base64ToBytes = (b64: string): Uint8Array<ArrayBuffer> => {
      const binary = atob(b64);
      const bytes = new Uint8Array(new ArrayBuffer(binary.length));
      for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      return bytes;
    };

    const getAesKey = async (secret: string): Promise<CryptoKey> => {
      const cached = (globalThis as any).__aiProxyCryptoKey as CryptoKey | undefined;
      const cachedSecret = (globalThis as any).__aiProxyCryptoSecret as string | undefined;
      if (cached && cachedSecret === secret) return cached;
      const digest = await crypto.subtle.digest('SHA-256', textEncoder.encode(secret));
      const key = await crypto.subtle.importKey('raw', digest, { name: 'AES-GCM' }, false, ['decrypt']);
      (globalThis as any).__aiProxyCryptoKey = key;
      (globalThis as any).__aiProxyCryptoSecret = secret;
      return key;
    };

    const decryptStoredKey = async (encrypted: string): Promise<string> => {
      if (!encrypted) return '';
      if (!encrypted.startsWith('v1:')) {
        return atob(encrypted);
      }
      const secret = (env.ENCRYPTION_KEY || '').trim();
      if (!secret) {
        throw new Error('Missing ENCRYPTION_KEY');
      }
      const parts = encrypted.split(':');
      if (parts.length !== 3) {
        throw new Error('Invalid encrypted_key format');
      }
      const iv = base64ToBytes(parts[1]);
      const data = base64ToBytes(parts[2]);
      const cryptoKey = await getAesKey(secret);
      const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, cryptoKey, data);
      return textDecoder.decode(plaintext);
    };

    let apiKey = (env.QWEN_API_KEY && env.QWEN_API_KEY.trim().length > 0) ? env.QWEN_API_KEY.trim() : undefined;
    let endpointUrl: string | undefined;

    const qwenRow = await env.DB.prepare(
      `SELECT encrypted_key, endpoint_url 
       FROM api_keys 
       WHERE service = 'qwen' AND is_active = 1
       ORDER BY is_primary DESC, updated_at DESC, created_at DESC
       LIMIT 1`
    ).first();

    if (qwenRow) {
      endpointUrl = (qwenRow.endpoint_url as string) || undefined;
    }

    if (!apiKey) {
      if (qwenRow && qwenRow.encrypted_key) {
        try {
          apiKey = await decryptStoredKey(qwenRow.encrypted_key as string);
        } catch (e) {
          return jsonResponse({ error: 'Key decryption failed' }, 500);
        }
      }
    }

    if (!apiKey) {
      return jsonResponse({ error: 'AI service not configured', detail: 'missing_qwen_api_key' }, 503);
    }
    
    const endpoint = endpointUrl || 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions';
    
    const proxyBody: any = {
      model: upstreamModel,
      messages,
      temperature: body.temperature || 0.7,
      max_tokens: maxTokens,
      stream: false
    };
    if (shouldEnableSearch) {
      proxyBody.enable_search = true;
      proxyBody.search_options = { search_strategy: 'agent' };
    }

    const upstreamResponse = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`
      },
      body: JSON.stringify(proxyBody)
    });

    const text = await upstreamResponse.text();
    const latencyMs = Date.now() - startMs;

    if (!upstreamResponse.ok) {
      await env.DB.prepare(
        `INSERT INTO ai_usage_logs (user_id, wallet_address, model, function_type, prompt_tokens, completion_tokens, total_tokens, latency_ms, success, error_message, created_at)
         VALUES (?, ?, ?, ?, 0, 0, 0, ?, 0, ?, ?)`
      ).bind(userId, walletAddress, upstreamModel, functionType, latencyMs, text.slice(0, 500), nowSec).run();

      return new Response(text, {
        status: upstreamResponse.status,
        headers: {
          'Content-Type': upstreamResponse.headers.get('Content-Type') || 'application/json'
        }
      });
    }

    let usage = { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 };
    try {
      const parsed = JSON.parse(text);
      if (parsed?.usage) {
        usage = {
          prompt_tokens: parsed.usage.prompt_tokens || 0,
          completion_tokens: parsed.usage.completion_tokens || 0,
          total_tokens: parsed.usage.total_tokens || 0
        };
      }
    } catch (_e) {
    }

    const usedTokens = usage.total_tokens || (usage.prompt_tokens + usage.completion_tokens) || 0;
    if (monthlyUsed + usedTokens > monthlyLimit) {
      return jsonResponse(
        { error: 'monthly_quota_exceeded', monthlyUsed, monthlyLimit },
        429
      );
    }
    const newDailyUsed = Math.min(dailyLimit, dailyUsed + usedTokens);
    const newMonthlyUsed = monthlyUsed + usedTokens;

    await env.DB.prepare(
      `INSERT INTO user_daily_stats (user_id, wallet_address, stat_date, tokens_used, updated_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(user_id, stat_date) DO UPDATE SET tokens_used = ?, updated_at = ?`
    ).bind(userId, walletAddress, statDate, newDailyUsed, nowSec, newDailyUsed, nowSec).run();

    await env.DB.prepare(
      `INSERT INTO user_monthly_stats (user_id, wallet_address, stat_month, tokens_used, updated_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(user_id, stat_month) DO UPDATE SET tokens_used = ?, updated_at = ?`
    ).bind(userId, walletAddress, statMonth, newMonthlyUsed, nowSec, newMonthlyUsed, nowSec).run();

    await env.DB.prepare(
      `INSERT INTO ai_usage_logs (user_id, wallet_address, model, function_type, prompt_tokens, completion_tokens, total_tokens, latency_ms, success, request_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`
    ).bind(
      userId,
      walletAddress,
      upstreamModel,
      functionType,
      usage.prompt_tokens || 0,
      usage.completion_tokens || 0,
      usedTokens,
      latencyMs,
      (() => {
        try { return JSON.parse(text)?.id || null; } catch (_e) { return null; }
      })(),
      nowSec
    ).run();

    return new Response(text, {
      status: upstreamResponse.status,
      headers: {
        'Content-Type': upstreamResponse.headers.get('Content-Type') || 'application/json'
      }
    });

  } catch (error) {
    console.error('AI Proxy Error:', error);
    return jsonResponse({ error: 'AI Proxy Failed', message: (error as Error).message }, 500);
  }
}

async function handleAiQuotaStatus(request: Request, env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database not available' }, 500);
  const url = new URL(request.url);
  const walletAddress = (url.searchParams.get('wallet') || request.headers.get('X-Wallet-Address') || '').toString();
  if (!walletAddress) return jsonResponse({ error: 'missing_wallet_address' }, 400);

  let monthlyStatsReady = (globalThis as any).__monthlyStatsReady as boolean | undefined;
  if (!monthlyStatsReady) {
    await env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS user_monthly_stats (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        wallet_address TEXT NOT NULL,
        stat_month TEXT NOT NULL,
        tokens_used INTEGER DEFAULT 0,
        created_at INTEGER DEFAULT (unixepoch()),
        updated_at INTEGER DEFAULT (unixepoch()),
        UNIQUE(user_id, stat_month)
      )`
    ).run();
    (globalThis as any).__monthlyStatsReady = true;
  }

  const nowSec = Math.floor(Date.now() / 1000);
  const now = new Date(nowSec * 1000);
  const statDate = now.toISOString().slice(0, 10);
  const statMonth = `${now.getUTCFullYear()}${String(now.getUTCMonth() + 1).padStart(2, '0')}`;

  const dailyLimit = 6000;
  const monthlyLimit = 140000;

  const userRow = await env.DB.prepare(
    `SELECT id FROM users WHERE wallet_address = ? LIMIT 1`
  ).bind(walletAddress).first();
  const userId = (userRow?.id as string | undefined) || '';
  if (!userId) {
    return jsonResponse(
      { walletAddress, dailyUsed: 0, dailyLimit, monthlyUsed: 0, monthlyLimit, statDate, statMonth },
      200
    );
  }

  const dailyRow = await env.DB.prepare(
    `SELECT tokens_used FROM user_daily_stats WHERE user_id = ? AND stat_date = ? LIMIT 1`
  ).bind(userId, statDate).first();
  const dailyUsed = (dailyRow?.tokens_used as number | undefined) ?? 0;

  const monthlyRow = await env.DB.prepare(
    `SELECT tokens_used FROM user_monthly_stats WHERE user_id = ? AND stat_month = ? LIMIT 1`
  ).bind(userId, statMonth).first();
  const monthlyUsed = (monthlyRow?.tokens_used as number | undefined) ?? 0;

  return jsonResponse(
    { walletAddress, dailyUsed, dailyLimit, monthlyUsed, monthlyLimit, statDate, statMonth },
    200
  );
}

async function handleAiEmbeddingProxy(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405);
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const body = await request.json() as any;

    let apiKey = (env.QWEN_API_KEY && env.QWEN_API_KEY.trim().length > 0) ? env.QWEN_API_KEY.trim() : undefined;
    let endpointUrl: string | undefined;

    const embeddingRow = await env.DB.prepare(
      `SELECT encrypted_key, endpoint_url 
       FROM api_keys
       WHERE service = 'embedding' AND is_active = 1
       ORDER BY is_primary DESC, updated_at DESC, created_at DESC
       LIMIT 1`
    ).first();

    if (embeddingRow) {
      endpointUrl = (embeddingRow.endpoint_url as string) || undefined;
    }

    if (!apiKey && embeddingRow && embeddingRow.encrypted_key) {
      try {
        apiKey = atob(embeddingRow.encrypted_key as string);
      } catch (e) {
        return jsonResponse({ error: 'Key decryption failed' }, 500);
      }
    }

    if (!apiKey) {
      const qwenRow = await env.DB.prepare(
        `SELECT encrypted_key 
         FROM api_keys
         WHERE service = 'qwen' AND is_active = 1
         ORDER BY is_primary DESC, updated_at DESC, created_at DESC
         LIMIT 1`
      ).first();
      if (qwenRow && qwenRow.encrypted_key) {
        try {
          apiKey = atob(qwenRow.encrypted_key as string);
        } catch (e) {
          return jsonResponse({ error: 'Key decryption failed' }, 500);
        }
      }
    }

    if (!apiKey) {
      return jsonResponse({ error: 'AI service not configured' }, 503);
    }

    const endpoint = endpointUrl || 'https://dashscope-intl.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding';

    const upstreamResponse = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`
      },
      body: JSON.stringify(body)
    });

    const text = await upstreamResponse.text();
    return new Response(text, {
      status: upstreamResponse.status,
      headers: {
        'Content-Type': upstreamResponse.headers.get('Content-Type') || 'application/json'
      }
    });
  } catch (error) {
    console.error('AI Embedding Proxy Error:', error);
    return jsonResponse({ error: 'AI Proxy Failed', message: (error as Error).message }, 500);
  }
}

/**
 * 注册 FCM Token（用于推送通知）
 */
async function registerFcmToken(request: Request, env: Env): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const body = await request.json() as {
      walletAddress: string;
      fcmToken: string;
      deviceId?: string;
      platform?: string;
    };

    if (!body.walletAddress || !body.fcmToken) {
      return jsonResponse({ error: 'walletAddress and fcmToken are required' }, 400);
    }

    const now = Math.floor(Date.now() / 1000);

    // 更新或插入 FCM Token
    await env.DB.prepare(
      `INSERT INTO fcm_tokens (wallet_address, fcm_token, device_id, platform, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(wallet_address) DO UPDATE SET
         fcm_token = excluded.fcm_token,
         device_id = excluded.device_id,
         platform = excluded.platform,
         updated_at = excluded.updated_at`
    ).bind(
      body.walletAddress,
      body.fcmToken,
      body.deviceId || null,
      body.platform || 'android',
      now,
      now
    ).run();

    console.log(`FCM token registered for ${body.walletAddress}`);

    return jsonResponse({ success: true });
  } catch (error) {
    console.error('Error registering FCM token:', error);
    return jsonResponse({ 
      error: 'Failed to register FCM token',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 获取用户档案（包括 onboarding 状态、积分、等级）
 */
async function getUserProfile(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const result = await env.DB.prepare(
      `SELECT wallet_address, onboarding_completed, persona_data, 
              memo_balance, current_tier, total_memo_earned,
              created_at, updated_at
       FROM user_profiles WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    if (!result) {
      return jsonResponse({ 
        walletAddress,
        onboardingCompleted: false,
        personaData: null,
        memoBalance: 0,
        currentTier: 1,
        totalMemoEarned: 0,
        exists: false
      });
    }

    return jsonResponse({
      walletAddress: result.wallet_address,
      onboardingCompleted: result.onboarding_completed === 1,
      personaData: result.persona_data ? JSON.parse(result.persona_data as string) : null,
      memoBalance: result.memo_balance || 0,
      currentTier: result.current_tier || 1,
      totalMemoEarned: result.total_memo_earned || 0,
      createdAt: result.created_at,
      updatedAt: result.updated_at,
      exists: true
    });
  } catch (error) {
    console.error('Error getting user profile:', error);
    return jsonResponse({ 
      error: 'Failed to get user profile',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 保存用户档案（包括 onboarding 状态、积分、等级）
 */
async function saveUserProfile(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const body = await request.json() as {
      onboardingCompleted?: boolean;
      personaData?: any;
      memoBalance?: number;
      currentTier?: number;
      totalMemoEarned?: number;
    };

    const now = Math.floor(Date.now() / 1000);

    // Upsert 用户档案（包含积分和等级）
    await env.DB.prepare(
      `INSERT INTO user_profiles (wallet_address, onboarding_completed, persona_data, memo_balance, current_tier, total_memo_earned, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(wallet_address) DO UPDATE SET
         onboarding_completed = COALESCE(excluded.onboarding_completed, user_profiles.onboarding_completed),
         persona_data = COALESCE(excluded.persona_data, user_profiles.persona_data),
         memo_balance = COALESCE(excluded.memo_balance, user_profiles.memo_balance),
         current_tier = COALESCE(excluded.current_tier, user_profiles.current_tier),
         total_memo_earned = COALESCE(excluded.total_memo_earned, user_profiles.total_memo_earned),
         updated_at = excluded.updated_at`
    ).bind(
      walletAddress,
      body.onboardingCompleted !== undefined ? (body.onboardingCompleted ? 1 : 0) : null,
      body.personaData ? JSON.stringify(body.personaData) : null,
      body.memoBalance !== undefined ? body.memoBalance : null,
      body.currentTier !== undefined ? body.currentTier : null,
      body.totalMemoEarned !== undefined ? body.totalMemoEarned : null,
      now,
      now
    ).run();

    console.log(`User profile saved for ${walletAddress}: balance=${body.memoBalance}, tier=${body.currentTier}`);

    return jsonResponse({ 
      success: true,
      walletAddress,
      onboardingCompleted: body.onboardingCompleted || false,
      memoBalance: body.memoBalance,
      currentTier: body.currentTier
    });
  } catch (error) {
    console.error('Error saving user profile:', error);
    return jsonResponse({ 
      error: 'Failed to save user profile',
      message: (error as Error).message
    }, 500);
  }
}

// ============================================
// 资源防护系统 - 签到、奇遇、交易验证
// ============================================

async function ensureCheckInTables(db: any): Promise<void> {
  const ready = (globalThis as any).__checkInTablesReady as boolean | undefined;
  if (ready) return;

  await db.prepare(
    `CREATE TABLE IF NOT EXISTS user_check_ins (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      wallet_address TEXT NOT NULL,
      check_in_date TEXT NOT NULL,
      consecutive_days INTEGER NOT NULL,
      weekly_progress INTEGER NOT NULL,
      reward_amount INTEGER NOT NULL,
      tier_multiplier REAL NOT NULL,
      created_at INTEGER NOT NULL,
      UNIQUE(wallet_address, check_in_date)
    )`
  ).run();

  await db.prepare(
    `CREATE TABLE IF NOT EXISTS memo_transaction_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      wallet_address TEXT NOT NULL,
      type TEXT NOT NULL,
      amount INTEGER NOT NULL,
      description TEXT,
      reference_id TEXT,
      created_at INTEGER NOT NULL
    )`
  ).run();

  ;(globalThis as any).__checkInTablesReady = true;
}

/**
 * 签到验证 API
 * 防止重复签到和刷签到
 */
async function processCheckIn(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    await ensureCheckInTables(env.DB);

    // 统一使用 UTC 时间（全球统一的切换时间：UTC 0点 = 北京时间 8点）
    const now = new Date();
    const today = now.toISOString().split('T')[0]; // YYYY-MM-DD (UTC)
    const yesterday = new Date(now.getTime() - 86400000).toISOString().split('T')[0];
    
    // 计算距离下次 UTC 0点的剩余时间（秒）
    const nextUtcMidnight = new Date(now);
    nextUtcMidnight.setUTCHours(24, 0, 0, 0);
    const secondsUntilReset = Math.floor((nextUtcMidnight.getTime() - now.getTime()) / 1000);
    
    // 检查今日是否已签到
    const existingCheckIn = await env.DB.prepare(
      `SELECT id, check_in_date FROM user_check_ins 
       WHERE wallet_address = ? AND check_in_date = ?`
    ).bind(walletAddress, today).first();

    if (existingCheckIn) {
      return jsonResponse({ 
        success: false,
        error: 'already_checked_in',
        message: '今日已签到',
        checkInDate: today,
        secondsUntilReset  // 返回倒计时
      }, 400);
    }

    // 获取连续签到天数
    const lastCheckIn = await env.DB.prepare(
      `SELECT consecutive_days, check_in_date FROM user_check_ins 
       WHERE wallet_address = ? ORDER BY check_in_date DESC LIMIT 1`
    ).bind(walletAddress).first();

    let consecutiveDays = 1;
    if (lastCheckIn && lastCheckIn.check_in_date === yesterday) {
      consecutiveDays = (lastCheckIn.consecutive_days as number) + 1;
    }

    // 计算奖励（7天循环：20,20,20,50,50,50,150）
    const rewards = [20, 20, 20, 50, 50, 50, 150];
    const weeklyProgress = ((consecutiveDays - 1) % 7) + 1;
    const baseReward = rewards[weeklyProgress - 1];

    // 获取用户当前等级倍数（从 users 表）
    const userProfile = await env.DB.prepare(
      `SELECT id, current_tier, memo_balance FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    const tierMultipliers: {[key: number]: number} = { 1: 1.0, 2: 1.5, 3: 2.0, 4: 3.0, 5: 5.0 };
    const currentTier = (userProfile?.current_tier as number) || 1;
    const multiplier = tierMultipliers[currentTier] || 1.0;
    const finalReward = Math.floor(baseReward * multiplier);

    const timestamp = Math.floor(Date.now() / 1000);

    // 如果用户不存在，先创建
    if (!userProfile) {
      const newId = `user_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      await env.DB.prepare(
        `INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, created_at, last_active_at)
         VALUES (?, ?, 0, 1, 'FREE', ?, ?)`
      ).bind(newId, walletAddress, timestamp, timestamp).run();
    }

    // 记录签到
    await env.DB.prepare(
      `INSERT INTO user_check_ins (wallet_address, check_in_date, consecutive_days, weekly_progress, reward_amount, tier_multiplier, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).bind(walletAddress, today, consecutiveDays, weeklyProgress, finalReward, multiplier, timestamp).run();

    // 记录交易
    await env.DB.prepare(
      `INSERT INTO memo_transaction_logs (wallet_address, type, amount, description, reference_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(walletAddress, 'CHECK_IN', finalReward, `签到奖励：第${weeklyProgress}天 (连续${consecutiveDays}天)`, `checkin_${today}`, timestamp).run();

    // 更新用户积分（更新 users 表）
    const currentBalance = (userProfile?.memo_balance as number) || 0;
    await env.DB.prepare(
      `UPDATE users SET memo_balance = memo_balance + ?, last_active_at = ? WHERE wallet_address = ?`
    ).bind(finalReward, timestamp, walletAddress).run();

    console.log(`Check-in success: ${walletAddress}, day ${consecutiveDays}, reward ${finalReward}`);

    // 重新计算距离下次 UTC 0点的剩余时间（秒）
    const currentTime = new Date();
    const nextReset = new Date(currentTime);
    nextReset.setUTCHours(24, 0, 0, 0);
    const secondsUntilNextReset = Math.floor((nextReset.getTime() - currentTime.getTime()) / 1000);

    return jsonResponse({
      success: true,
      checkInDate: today,
      consecutiveDays,
      weeklyProgress,
      reward: finalReward,
      tierMultiplier: multiplier,
      newBalance: currentBalance + finalReward,
      secondsUntilReset: secondsUntilNextReset  // 返回倒计时，供客户端显示
    });
  } catch (error) {
    console.error('Check-in error:', error);
    return jsonResponse({ 
      error: 'Failed to process check-in',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 获取签到状态
 */
async function getCheckInStatus(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    await ensureCheckInTables(env.DB);

    // 统一使用 UTC 时间
    const now = new Date();
    const today = now.toISOString().split('T')[0];
    
    // 计算距离下次 UTC 0点的剩余时间（秒）
    const nextUtcMidnight = new Date(now);
    nextUtcMidnight.setUTCHours(24, 0, 0, 0);
    const secondsUntilReset = Math.floor((nextUtcMidnight.getTime() - now.getTime()) / 1000);
    
    // 今日签到记录
    const todayCheckIn = await env.DB.prepare(
      `SELECT * FROM user_check_ins WHERE wallet_address = ? AND check_in_date = ?`
    ).bind(walletAddress, today).first();

    // 最近签到记录
    const lastCheckIn = await env.DB.prepare(
      `SELECT * FROM user_check_ins WHERE wallet_address = ? ORDER BY check_in_date DESC LIMIT 1`
    ).bind(walletAddress).first();

    // 总签到天数
    const totalDays = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM user_check_ins WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    return jsonResponse({
      hasCheckedInToday: !!todayCheckIn,
      consecutiveDays: lastCheckIn?.consecutive_days || 0,
      weeklyProgress: lastCheckIn?.weekly_progress || 0,
      totalCheckInDays: totalDays?.count || 0,
      lastCheckInDate: lastCheckIn?.check_in_date || null,
      secondsUntilReset,  // 距离下次重置的剩余秒数
      resetTimeUTC: '00:00 UTC'  // 提示重置时间
    });
  } catch (error) {
    console.error('Get check-in status error:', error);
    return jsonResponse({ error: 'Failed to get check-in status' }, 500);
  }
}

/**
 * 奇遇任务完成验证
 * 防止重复领取奖励
 */
async function completeAdventure(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const body = await request.json() as {
      questionId: string;
      questionText?: string;
    };

    if (!body.questionId) {
      return jsonResponse({ error: 'questionId is required' }, 400);
    }

    // 检查是否已完成此奇遇
    const existing = await env.DB.prepare(
      `SELECT id FROM adventure_completions WHERE wallet_address = ? AND question_id = ?`
    ).bind(walletAddress, body.questionId).first();

    if (existing) {
      return jsonResponse({
        success: false,
        error: 'already_completed',
        message: '此奇遇已完成'
      }, 400);
    }

    // 获取用户等级（从 users 表）
    const userProfile = await env.DB.prepare(
      `SELECT id, current_tier, memo_balance FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    const tierMultipliers: {[key: number]: number} = { 1: 1.0, 2: 1.5, 3: 2.0, 4: 3.0, 5: 5.0 };
    const currentTier = (userProfile?.current_tier as number) || 1;
    const multiplier = tierMultipliers[currentTier] || 1.0;
    
    // 奇遇基础奖励 150，随机浮动 50-200
    const baseReward = 150;
    const finalReward = Math.floor(baseReward * multiplier);

    const now = Math.floor(Date.now() / 1000);

    // 如果用户不存在，先创建
    if (!userProfile) {
      const newId = `user_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      await env.DB.prepare(
        `INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, created_at, last_active_at)
         VALUES (?, ?, 0, 1, 'FREE', ?, ?)`
      ).bind(newId, walletAddress, now, now).run();
    }

    // 记录奇遇完成
    await env.DB.prepare(
      `INSERT INTO adventure_completions (wallet_address, question_id, question_text, reward_amount, tier_multiplier, completed_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(walletAddress, body.questionId, body.questionText || '', finalReward, multiplier, now).run();

    // 记录交易
    await env.DB.prepare(
      `INSERT INTO memo_transaction_logs (wallet_address, type, amount, description, reference_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(walletAddress, 'ADVENTURE', finalReward, `奇遇完成：${(body.questionText || '').slice(0, 30)}...`, `adventure_${body.questionId}`, now).run();

    // 更新用户积分（更新 users 表）
    const currentBalance = (userProfile?.memo_balance as number) || 0;
    await env.DB.prepare(
      `UPDATE users SET memo_balance = memo_balance + ?, last_active_at = ? WHERE wallet_address = ?`
    ).bind(finalReward, now, walletAddress).run();

    console.log(`Adventure completed: ${walletAddress}, question ${body.questionId}, reward ${finalReward}`);

    return jsonResponse({
      success: true,
      questionId: body.questionId,
      reward: finalReward,
      tierMultiplier: multiplier,
      newBalance: currentBalance + finalReward
    });
  } catch (error) {
    console.error('Adventure completion error:', error);
    return jsonResponse({ 
      error: 'Failed to complete adventure',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 对话奖励记录
 * 防止刷对话积分
 */
async function recordDialogueReward(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const body = await request.json() as {
      sessionId?: string;
      isFirstChat?: boolean;
      resonanceGrade?: string;
      resonanceScore?: number;
    };

    const today = new Date().toISOString().split('T')[0];
    const now = Math.floor(Date.now() / 1000);

    // 获取今日对话次数
    const todayDialogues = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM dialogue_rewards 
       WHERE wallet_address = ? AND DATE(datetime(created_at, 'unixepoch')) = ?`
    ).bind(walletAddress, today).first();

    const dialogueCount = (todayDialogues?.count as number) || 0;
    const DAILY_LIMIT = 50;
    const isOverLimit = dialogueCount >= DAILY_LIMIT;

    // 获取用户等级（从 users 表）
    const userProfile = await env.DB.prepare(
      `SELECT id, current_tier, memo_balance FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    const tierMultipliers: {[key: number]: number} = { 1: 1.0, 2: 1.5, 3: 2.0, 4: 3.0, 5: 5.0 };
    const currentTier = (userProfile?.current_tier as number) || 1;
    const multiplier = tierMultipliers[currentTier] || 1.0;

    // 如果用户不存在，先创建
    if (!userProfile) {
      const newId = `user_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      await env.DB.prepare(
        `INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, created_at, last_active_at)
         VALUES (?, ?, 0, 1, 'FREE', ?, ?)`
      ).bind(newId, walletAddress, now, now).run();
    }

    // 计算奖励
    let baseReward = isOverLimit ? 1 : 10;
    let description = `AI对话奖励：第${dialogueCount + 1}条`;
    
    // 首聊奖励检查
    const todayFirstChat = await env.DB.prepare(
      `SELECT id FROM dialogue_rewards 
       WHERE wallet_address = ? AND DATE(datetime(created_at, 'unixepoch')) = ? AND is_first_chat = 1`
    ).bind(walletAddress, today).first();

    let firstChatBonus = 0;
    if (!todayFirstChat && body.isFirstChat) {
      firstChatBonus = 30;
      description = '每日首聊奖励 + AI对话奖励';
    }

    // 人格共鸣奖励
    let resonanceBonus = 0;
    if (body.resonanceScore !== undefined) {
      if (body.resonanceScore >= 90) resonanceBonus = 100;
      else if (body.resonanceScore >= 70) resonanceBonus = 30;
      else if (body.resonanceScore >= 40) resonanceBonus = 10;
    }

    const totalBase = baseReward + firstChatBonus + resonanceBonus;
    const finalReward = Math.floor(totalBase * multiplier);

    // 记录对话奖励
    await env.DB.prepare(
      `INSERT INTO dialogue_rewards (wallet_address, session_id, dialogue_index, base_reward, first_chat_bonus, resonance_bonus, resonance_grade, tier_multiplier, final_reward, is_first_chat, is_over_limit, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      walletAddress, 
      body.sessionId || '', 
      dialogueCount + 1,
      baseReward,
      firstChatBonus,
      resonanceBonus,
      body.resonanceGrade || 'B',
      multiplier,
      finalReward,
      body.isFirstChat ? 1 : 0,
      isOverLimit ? 1 : 0,
      now
    ).run();

    // 记录交易
    await env.DB.prepare(
      `INSERT INTO memo_transaction_logs (wallet_address, type, amount, description, reference_id, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`
    ).bind(walletAddress, 'DIALOGUE', finalReward, description, `dialogue_${now}`, now).run();

    // 更新用户积分（更新 users 表）
    const currentBalance = (userProfile?.memo_balance as number) || 0;
    await env.DB.prepare(
      `UPDATE users SET memo_balance = memo_balance + ?, last_active_at = ? WHERE wallet_address = ?`
    ).bind(finalReward, now, walletAddress).run();

    return jsonResponse({
      success: true,
      dialogueIndex: dialogueCount + 1,
      reward: finalReward,
      breakdown: {
        base: baseReward,
        firstChatBonus,
        resonanceBonus,
        tierMultiplier: multiplier
      },
      isOverLimit,
      newBalance: currentBalance + finalReward
    });
  } catch (error) {
    console.error('Dialogue reward error:', error);
    return jsonResponse({ 
      error: 'Failed to record dialogue reward',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 获取积分交易历史
 */
async function getTransactionHistory(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    const url = new URL(request.url);
    const limit = parseInt(url.searchParams.get('limit') || '50');
    const offset = parseInt(url.searchParams.get('offset') || '0');

    const transactions = await env.DB.prepare(
      `SELECT * FROM memo_transaction_logs 
       WHERE wallet_address = ? 
       ORDER BY created_at DESC 
       LIMIT ? OFFSET ?`
    ).bind(walletAddress, limit, offset).all();

    const total = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM memo_transaction_logs WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    return jsonResponse({
      transactions: transactions.results,
      total: total?.count || 0,
      limit,
      offset
    });
  } catch (error) {
    console.error('Get transaction history error:', error);
    return jsonResponse({ error: 'Failed to get transaction history' }, 500);
  }
}

// ============================================
// 🆕 后端优先架构 - 实时数据端点
// ============================================

/**
 * 获取实时余额和用户状态（后端优先架构核心端点）
 * 
 * 这是 App 获取用户数据的主要端点，返回完整的实时状态
 * 使用 users 表存储用户基础信息
 */
async function getRealTimeBalance(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    // 获取用户档案（从 users 表）
    const userProfile = await env.DB.prepare(
      `SELECT id, wallet_address, memo_balance, current_tier, subscription_type, subscription_expiry
       FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first();

    // Tier 配置
    const tierConfigs: {[key: number]: {name: string, multiplier: number}} = {
      1: { name: 'Bronze', multiplier: 1.0 },
      2: { name: 'Silver', multiplier: 1.5 },
      3: { name: 'Gold', multiplier: 2.0 },
      4: { name: 'Platinum', multiplier: 3.0 },
      5: { name: 'Diamond', multiplier: 5.0 }
    };

    // 如果用户不存在，创建默认用户
    if (!userProfile) {
      const now = Math.floor(Date.now() / 1000);
      const newId = `user_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
      await env.DB.prepare(
        `INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, created_at, last_active_at)
         VALUES (?, ?, 0, 1, 'FREE', ?, ?)`
      ).bind(newId, walletAddress, now, now).run();
      
      return jsonResponse({
        walletAddress,
        memoBalance: 0,
        currentTier: 1,
        tierName: 'Bronze',
        tierMultiplier: 1.0,
        totalMemoEarned: 0,
        subscriptionType: 'FREE',
        subscriptionExpiry: null,
        onboardingCompleted: false,
        // 今日状态
        dailyDialogueCount: 0,
        hasCheckedInToday: false,
        hasFirstChatToday: false,
        // 签到信息
        consecutiveCheckInDays: 0,
        weeklyCheckInProgress: 0,
        totalCheckInDays: 0,
        // 元信息
        syncedAt: new Date().toISOString()
      });
    }

    const currentTier = (userProfile.current_tier as number) || 1;
    const tierConfig = tierConfigs[currentTier] || tierConfigs[1];

    const rawExpiry = (userProfile.subscription_expiry as number | null) || 0
    const subscriptionExpiry =
      rawExpiry > 0 ? (rawExpiry < 1_000_000_000_000 ? rawExpiry * 1000 : rawExpiry) : null

    // 检查是否存在活跃的自动续费订阅（纠错逻辑）
    let activeSubscriptionType = userProfile.subscription_type || 'FREE';
    try {
      const activeSub = await env.DB.prepare(
        'SELECT plan_type FROM auto_renew_subscriptions WHERE wallet_address = ? AND is_active = 1'
      ).bind(walletAddress).first();

      if (activeSub) {
        const PLAN_TYPE_MAP: Record<number, string> = {
          1: 'monthly_continuous',
          2: 'quarterly_continuous',
          3: 'yearly_continuous'
        };
        const correctType = PLAN_TYPE_MAP[activeSub.plan_type as number] || 'FREE';
        
        // 如果数据库中的状态不一致，优先使用订阅表的状态，并异步修复
        if (activeSubscriptionType !== correctType && correctType !== 'FREE') {
          console.log(`[AutoFix] Correcting subscription_type for ${walletAddress}: ${activeSubscriptionType} -> ${correctType}`);
          activeSubscriptionType = correctType;
          // 异步修复 users 表
          env.DB.prepare(
            'UPDATE users SET subscription_type = ?, updated_at = ? WHERE wallet_address = ?'
          ).bind(correctType, Math.floor(Date.now() / 1000), walletAddress).run().catch(console.error);
        }
      }
    } catch (e) {
      console.warn('Failed to check auto_renew_subscriptions:', e);
    }

    // 获取今日对话次数（从 dialogue_rewards 表，如果存在）
    const today = new Date().toISOString().split('T')[0];
    let dailyDialogueCount = 0;
    let hasFirstChatToday = false;
    
    try {
      const todayDialogues = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM dialogue_rewards 
         WHERE wallet_address = ? AND DATE(datetime(created_at, 'unixepoch')) = ?`
      ).bind(walletAddress, today).first();
      dailyDialogueCount = (todayDialogues?.count as number) || 0;

      const todayFirstChat = await env.DB.prepare(
        `SELECT id FROM dialogue_rewards 
         WHERE wallet_address = ? AND DATE(datetime(created_at, 'unixepoch')) = ? AND is_first_chat = 1`
      ).bind(walletAddress, today).first();
      hasFirstChatToday = !!todayFirstChat;
    } catch (e) {
      // dialogue_rewards 表可能不存在，忽略错误
      console.log('dialogue_rewards table may not exist:', e);
    }

    // 获取签到状态（从 user_check_ins 表，如果存在）
    let hasCheckedInToday = false;
    let consecutiveCheckInDays = 0;
    let weeklyCheckInProgress = 0;
    let totalCheckInDays = 0;

    try {
      const todayCheckIn = await env.DB.prepare(
        `SELECT * FROM user_check_ins WHERE wallet_address = ? AND check_in_date = ?`
      ).bind(walletAddress, today).first();
      hasCheckedInToday = !!todayCheckIn;

      const lastCheckIn = await env.DB.prepare(
        `SELECT consecutive_days, weekly_progress FROM user_check_ins 
         WHERE wallet_address = ? ORDER BY check_in_date DESC LIMIT 1`
      ).bind(walletAddress).first();
      consecutiveCheckInDays = (lastCheckIn?.consecutive_days as number) || 0;
      weeklyCheckInProgress = (lastCheckIn?.weekly_progress as number) || 0;

      const totalCheckIns = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM user_check_ins WHERE wallet_address = ?`
      ).bind(walletAddress).first();
      totalCheckInDays = (totalCheckIns?.count as number) || 0;
    } catch (e) {
      // user_check_ins 表可能不存在，忽略错误
      console.log('user_check_ins table may not exist:', e);
    }

    // 计算 totalMemoEarned（从 memo_transaction_logs 表，如果存在）
    let totalMemoEarned = 0;
    try {
      // 从交易日志表累计所有正向奖励（签到、对话、奇遇等）
      const totalEarned = await env.DB.prepare(
        `SELECT SUM(amount) as total FROM memo_transaction_logs 
         WHERE wallet_address = ? AND amount > 0`
      ).bind(walletAddress).first();
      totalMemoEarned = (totalEarned?.total as number) || 0;
      
      // 如果没有交易记录，使用 memo_balance 作为备选
      if (totalMemoEarned === 0) {
        totalMemoEarned = (userProfile.memo_balance as number) || 0;
      }
    } catch (e) {
      // 如果表不存在，使用 memo_balance 作为 fallback
      console.log('memo_transaction_logs query failed:', e);
      totalMemoEarned = (userProfile.memo_balance as number) || 0;
    }

    return jsonResponse({
      walletAddress,
      memoBalance: userProfile.memo_balance || 0,
      currentTier,
      tierName: tierConfig.name,
      tierMultiplier: tierConfig.multiplier,
      totalMemoEarned,
      subscriptionType: activeSubscriptionType,
      subscriptionExpiry,
      onboardingCompleted: true, // 默认已完成引导（可后续扩展）
      // 今日状态
      dailyDialogueCount,
      hasCheckedInToday,
      hasFirstChatToday,
      // 签到信息
      consecutiveCheckInDays,
      weeklyCheckInProgress,
      totalCheckInDays,
      // 元信息
      syncedAt: new Date().toISOString()
    });
  } catch (error) {
    console.error('Get real-time balance error:', error);
    return jsonResponse({ 
      error: 'Failed to get balance',
      message: (error as Error).message
    }, 500);
  }
}

// ============================================
// 🔧 同步余额（从交易日志重新计算）
// ============================================

/**
 * 同步 memo_balance
 * 
 * 从 memo_transaction_logs 表重新计算总获取量，并更新 users 表的 memo_balance
 * 用于修复因早期 bug 导致的数据不一致问题
 */
async function syncMemoBalance(request: Request, env: Env, walletAddress: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    // 1. 从交易日志计算总获取量
    const totalEarned = await env.DB.prepare(
      `SELECT SUM(amount) as total FROM memo_transaction_logs 
       WHERE wallet_address = ? AND amount > 0`
    ).bind(walletAddress).first();
    
    const calculatedBalance = (totalEarned?.total as number) || 0;
    
    // 2. 获取当前 users 表中的 memo_balance
    const currentUser = await env.DB.prepare(
      `SELECT memo_balance FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first();
    
    const currentBalance = (currentUser?.memo_balance as number) || 0;
    
    // 3. 如果不一致，更新 users 表
    if (calculatedBalance !== currentBalance) {
      await env.DB.prepare(
        `UPDATE users SET memo_balance = ?, last_active_at = ? WHERE wallet_address = ?`
      ).bind(calculatedBalance, new Date().toISOString(), walletAddress).run();
      
      console.log(`[SyncBalance] Fixed: ${walletAddress}, ${currentBalance} -> ${calculatedBalance}`);
      
      return jsonResponse({
        success: true,
        walletAddress,
        previousBalance: currentBalance,
        newBalance: calculatedBalance,
        difference: calculatedBalance - currentBalance,
        message: '余额已同步'
      });
    }
    
    return jsonResponse({
      success: true,
      walletAddress,
      balance: currentBalance,
      message: '余额已一致，无需同步'
    });
    
  } catch (error) {
    console.error('Sync memo balance error:', error);
    return jsonResponse({ 
      error: 'Failed to sync balance',
      message: (error as Error).message
    }, 500);
  }
}

// ============================================
// 🆕 Solana 链上操作代理
// ============================================

/**
 * Solana 链上操作代理处理器
 * 
 * 所有链上查询和验证都通过后端转发，确保数据一致性和安全性
 */
async function handleSolanaProxy(request: Request, env: Env, path: string): Promise<Response> {
  const rpcUrl = getSolanaRpcUrl(env);
  
  try {
    // 解析路径: /api/v1/solana/{action}/{param}
    const pathParts = path.split('/');
    const action = pathParts[4]; // balance, tokens, staking, verify-transaction
    const param = pathParts[5];  // wallet address or other param

    switch (action) {
      case 'balance':
        return await getSolanaBalance(param, rpcUrl);
      
      case 'tokens':
        return await getSolanaTokens(param, rpcUrl);
      
      case 'staking':
        return await getSolanaStaking(param, env);
      
      case 'verify-transaction':
        if (request.method !== 'POST') {
          return jsonResponse({ error: 'Method not allowed' }, 405);
        }
        return await verifySolanaTransaction(request, rpcUrl);
      
      default:
        return jsonResponse({ error: 'Unknown Solana action' }, 404);
    }
  } catch (error) {
    console.error('Solana proxy error:', error);
    return jsonResponse({ 
      error: 'Solana proxy failed',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 获取 SOL 余额（通过后端代理）
 */
async function getSolanaBalance(wallet: string, rpcUrl: string): Promise<Response> {
  if (!wallet) {
    return jsonResponse({ error: 'Wallet address required' }, 400);
  }

  try {
    const response = await fetch(rpcUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'getBalance',
        params: [wallet]
      })
    });

    const data = await response.json() as { result?: { value: number }, error?: any };
    
    if (data.error) {
      return jsonResponse({ error: 'RPC error', details: data.error }, 500);
    }

    const lamports = data.result?.value || 0;
    const sol = lamports / 1_000_000_000;

    return jsonResponse({
      wallet,
      lamports,
      sol,
      lastUpdate: new Date().toISOString()
    });
  } catch (error) {
    console.error('Get SOL balance error:', error);
    return jsonResponse({ 
      error: 'Failed to get balance',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 获取 Token 余额（通过后端代理）
 */
async function getSolanaTokens(wallet: string, rpcUrl: string): Promise<Response> {
  if (!wallet) {
    return jsonResponse({ error: 'Wallet address required' }, 400);
  }

  try {
    const tokenPrograms = [
      'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA',
      'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb',
    ];

    const tokensByAccount = new Map<string, any>();

    for (const programId of tokenPrograms) {
      const response = await fetch(rpcUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'getTokenAccountsByOwner',
          params: [
            wallet,
            { programId },
            { encoding: 'jsonParsed' }
          ]
        })
      });

      const data = await response.json() as { result?: { value: any[] }, error?: any };
      if (data.error) {
        continue;
      }

      for (const account of (data.result?.value || [])) {
        const info = account.account?.data?.parsed?.info;
        const balance = info?.tokenAmount?.uiAmount || 0;
        if (!balance || balance <= 0) continue;
        tokensByAccount.set(account.pubkey, {
          mint: info?.mint,
          balance,
          decimals: info?.tokenAmount?.decimals || 0,
          address: account.pubkey
        });
      }
    }

    const tokens = Array.from(tokensByAccount.values());

    return jsonResponse({
      wallet,
      tokens,
      count: tokens.length,
      lastUpdate: new Date().toISOString()
    });
  } catch (error) {
    console.error('Get tokens error:', error);
    return jsonResponse({ 
      error: 'Failed to get tokens',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 获取质押状态（从数据库读取）
 */
async function getSolanaStaking(wallet: string, env: Env): Promise<Response> {
  if (!wallet) {
    return jsonResponse({ error: 'Wallet address required' }, 400);
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500);
  }

  try {
    // 从数据库获取质押记录
    const stakingRecord = await env.DB.prepare(
      `SELECT * FROM staking_records 
       WHERE wallet_address = ? AND status = 'active'
       ORDER BY created_at DESC LIMIT 1`
    ).bind(wallet).first();

    if (!stakingRecord) {
      return jsonResponse({
        wallet,
        hasStaking: false,
        stakedAmount: 0,
        stakingBonus: 1.0,
        lastUpdate: new Date().toISOString()
      });
    }

    // 计算质押加成
    const stakedLamports = (stakingRecord.amount as number) || 0;
    const stakedSol = stakedLamports / 1_000_000_000;
    
    // 质押加成规则：每质押 100 SOL 增加 10% 加成，最高 50%
    const bonusPercent = Math.min(stakedSol / 100 * 0.1, 0.5);
    const stakingBonus = 1 + bonusPercent;

    return jsonResponse({
      wallet,
      hasStaking: true,
      stakedAmount: stakedLamports,
      stakedSol,
      stakingBonus,
      unlockTime: stakingRecord.unlock_time,
      status: stakingRecord.status,
      lastUpdate: new Date().toISOString()
    });
  } catch (error) {
    console.error('Get staking error:', error);
    return jsonResponse({ 
      error: 'Failed to get staking',
      message: (error as Error).message
    }, 500);
  }
}

/**
 * 验证 Solana 交易（通过后端代理）
 */
async function verifySolanaTransaction(request: Request, rpcUrl: string): Promise<Response> {
  try {
    const body = await request.json() as {
      signature: string;
      expectedType?: string;
    };

    if (!body.signature) {
      return jsonResponse({ error: 'Transaction signature required' }, 400);
    }

    // 获取交易详情
    const response = await fetch(rpcUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'getTransaction',
        params: [
          body.signature,
          { encoding: 'jsonParsed', maxSupportedTransactionVersion: 0 }
        ]
      })
    });

    const data = await response.json() as { result?: any, error?: any };
    
    if (data.error) {
      return jsonResponse({ error: 'RPC error', details: data.error }, 500);
    }

    if (!data.result) {
      return jsonResponse({
        verified: false,
        signature: body.signature,
        status: 'not_found',
        message: 'Transaction not found or not yet confirmed'
      });
    }

    const tx = data.result;
    const meta = tx.meta;
    
    // 检查交易是否成功
    const isSuccess = meta?.err === null;
    
    return jsonResponse({
      verified: isSuccess,
      signature: body.signature,
      status: isSuccess ? 'confirmed' : 'failed',
      slot: tx.slot,
      blockTime: tx.blockTime,
      fee: meta?.fee,
      error: meta?.err,
      lastUpdate: new Date().toISOString()
    });
  } catch (error) {
    console.error('Verify transaction error:', error);
    return jsonResponse({ 
      error: 'Failed to verify transaction',
      message: (error as Error).message
    }, 500);
  }
}
