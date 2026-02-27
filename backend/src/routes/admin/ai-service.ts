/**
 * 管理后台 - AI 服务管理 API
 * 包含 API 密钥管理、用量监控、配额控制
 */

import { AdminContext, logAdminAction } from './middleware'
import { getSolanaRpcUrl } from '../../utils/solana-rpc'

function jsonResponse(data: unknown, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const textEncoder = new TextEncoder()
let cachedCryptoKey: CryptoKey | null = null
let cachedSecret: string | null = null

function requireEncryptionKey(env: any): string {
  const secret = (env.ENCRYPTION_KEY || '').trim()
  if (!secret) {
    throw new Error('Missing ENCRYPTION_KEY')
  }
  return secret
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
  return btoa(binary)
}

function base64ToBytes(b64: string): Uint8Array {
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

async function getAesKey(secret: string): Promise<CryptoKey> {
  if (cachedCryptoKey && cachedSecret === secret) return cachedCryptoKey
  const digest = await crypto.subtle.digest('SHA-256', textEncoder.encode(secret))
  cachedCryptoKey = await crypto.subtle.importKey('raw', digest, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt'])
  cachedSecret = secret
  return cachedCryptoKey
}

async function encryptKey(key: string, secret: string): Promise<string> {
  const cryptoKey = await getAesKey(secret)
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, cryptoKey, textEncoder.encode(key))
  return `v1:${bytesToBase64(iv)}:${bytesToBase64(new Uint8Array(ciphertext))}`
}

async function decryptKey(encrypted: string, secret: string): Promise<string> {
  if (!encrypted) return ''
  if (!encrypted.startsWith('v1:')) {
    return atob(encrypted)
  }
  const parts = encrypted.split(':')
  if (parts.length !== 3) {
    throw new Error('Invalid encrypted_key format')
  }
  const iv = base64ToBytes(parts[1])
  const data = base64ToBytes(parts[2])
  const cryptoKey = await getAesKey(secret)
  const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, cryptoKey, data)
  return new TextDecoder().decode(plaintext)
}

function maskKey(key: string): string {
  if (key.length <= 8) return '****'
  return key.substring(0, 4) + '...' + key.substring(key.length - 4)
}

// ============================================
// API 密钥管理
// ============================================

/**
 * 获取 API 密钥列表（脱敏）
 */
export async function getApiKeys(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(
      `SELECT id, name, service, key_preview, endpoint_url, rate_limit, monthly_budget,
              usage_count, total_cost, last_used_at, last_error, is_primary, is_active,
              expires_at, created_at, updated_at
       FROM api_keys ORDER BY service, is_primary DESC, name`
    ).all()

    const keys = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      service: row.service,
      keyPreview: row.key_preview,
      endpointUrl: row.endpoint_url,
      rateLimit: row.rate_limit,
      monthlyBudget: row.monthly_budget,
      usageCount: row.usage_count,
      totalCost: row.total_cost,
      lastUsedAt: row.last_used_at ? new Date(row.last_used_at * 1000).toISOString() : null,
      lastError: row.last_error,
      isPrimary: !!row.is_primary,
      isActive: !!row.is_active,
      expiresAt: row.expires_at ? new Date(row.expires_at * 1000).toISOString() : null,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ keys })
  } catch (error) {
    console.error('Error getting API keys:', error)
    return jsonResponse({ error: 'Failed to get API keys' }, 500)
  }
}

/**
 * 添加 API 密钥
 */
export async function addApiKey(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      name: string
      service: string
      key: string
      endpointUrl?: string
      rateLimit?: number
      monthlyBudget?: number
      isPrimary?: boolean
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const encryptedKey = await encryptKey(body.key, requireEncryptionKey(env))
    const keyPreview = maskKey(body.key)

    // 如果设置为主密钥，先取消其他同服务的主密钥
    if (body.isPrimary) {
      await env.DB.prepare(
        'UPDATE api_keys SET is_primary = 0 WHERE service = ?'
      ).bind(body.service).run()
    }

    const result = await env.DB.prepare(
      `INSERT INTO api_keys (name, service, key_preview, encrypted_key, endpoint_url, 
                            rate_limit, monthly_budget, is_primary, created_by, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      body.name,
      body.service,
      keyPreview,
      encryptedKey,
      body.endpointUrl || null,
      body.rateLimit || null,
      body.monthlyBudget || null,
      body.isPrimary ? 1 : 0,
      adminContext.email,
      now,
      now
    ).run()

    await logAdminAction(env, adminContext, 'ADD_API_KEY', 'api_key', body.service, 
      `添加 ${body.service} API 密钥: ${body.name}`)

    return jsonResponse({ success: true, id: result.meta?.last_row_id })
  } catch (error) {
    console.error('Error adding API key:', error)
    return jsonResponse({ error: 'Failed to add API key' }, 500)
  }
}

/**
 * 更新 API 密钥
 */
export async function updateApiKey(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      name?: string
      key?: string
      endpointUrl?: string
      rateLimit?: number
      monthlyBudget?: number
      isPrimary?: boolean
      isActive?: boolean
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const updates: string[] = []
    const params: any[] = []

    if (body.name !== undefined) {
      updates.push('name = ?')
      params.push(body.name)
    }
    if (body.key !== undefined) {
      updates.push('encrypted_key = ?')
      params.push(await encryptKey(body.key, requireEncryptionKey(env)))
      updates.push('key_preview = ?')
      params.push(maskKey(body.key))
    }
    if (body.endpointUrl !== undefined) {
      updates.push('endpoint_url = ?')
      params.push(body.endpointUrl)
    }
    if (body.rateLimit !== undefined) {
      updates.push('rate_limit = ?')
      params.push(body.rateLimit)
    }
    if (body.monthlyBudget !== undefined) {
      updates.push('monthly_budget = ?')
      params.push(body.monthlyBudget)
    }
    if (body.isPrimary !== undefined) {
      // 先获取服务类型
      if (body.isPrimary) {
        const existing = await env.DB.prepare('SELECT service FROM api_keys WHERE id = ?').bind(id).first()
        if (existing) {
          await env.DB.prepare('UPDATE api_keys SET is_primary = 0 WHERE service = ?').bind(existing.service).run()
        }
      }
      updates.push('is_primary = ?')
      params.push(body.isPrimary ? 1 : 0)
    }
    if (body.isActive !== undefined) {
      updates.push('is_active = ?')
      params.push(body.isActive ? 1 : 0)
    }

    updates.push('updated_at = ?')
    params.push(now)
    params.push(id)

    await env.DB.prepare(
      `UPDATE api_keys SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    await logAdminAction(env, adminContext, 'UPDATE_API_KEY', 'api_key', id, 
      `更新 API 密钥 ID: ${id}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating API key:', error)
    return jsonResponse({ error: 'Failed to update API key' }, 500)
  }
}

/**
 * 删除 API 密钥
 */
export async function deleteApiKey(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    // 获取密钥信息用于日志
    const existing = await env.DB.prepare('SELECT name, service FROM api_keys WHERE id = ?').bind(id).first()

    await env.DB.prepare('DELETE FROM api_keys WHERE id = ?').bind(id).run()

    await logAdminAction(env, adminContext, 'DELETE_API_KEY', 'api_key', id, 
      `删除 API 密钥: ${existing?.name || id}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error deleting API key:', error)
    return jsonResponse({ error: 'Failed to delete API key' }, 500)
  }
}

/**
 * 测试 API 密钥
 */
export async function testApiKey(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const keyData = await env.DB.prepare(
      'SELECT service, encrypted_key, endpoint_url FROM api_keys WHERE id = ?'
    ).bind(id).first()

    if (!keyData) {
      return jsonResponse({ error: 'API key not found' }, 404)
    }

    const apiKey = await decryptKey(keyData.encrypted_key, requireEncryptionKey(env))
    let testResult: { success: boolean; latency?: number; error?: string } = { success: false }

    const startTime = Date.now()

    // 根据服务类型进行测试
    try {
      switch (keyData.service) {
        case 'qwen':
          // 测试阿里云 Qwen API (OpenAI 兼容模式)
          const qwenEndpoint = keyData.endpoint_url || 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions'
          
          const qwenResponse = await fetch(qwenEndpoint, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${apiKey}`,
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              model: 'qwen-turbo',
              messages: [{ role: 'user', content: 'hi' }],
              max_tokens: 5
            })
          })
          const qwenResult = await qwenResponse.text()
          testResult.success = qwenResponse.ok
          if (!qwenResponse.ok) {
            testResult.error = qwenResult
          }
          break

        case 'embedding':
          // 测试阿里云 Embedding API
          const embeddingEndpoint = keyData.endpoint_url || 'https://dashscope-intl.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding'
          
          const embeddingResponse = await fetch(embeddingEndpoint, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${apiKey}`,
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              model: 'text-embedding-v3',
              input: { texts: ['test'] },
              parameters: { text_type: 'query' }
            })
          })
          const embeddingResult = await embeddingResponse.text()
          testResult.success = embeddingResponse.ok
          if (!embeddingResponse.ok) {
            testResult.error = embeddingResult
          }
          break

        case 'solana_rpc':
          // 测试 Solana RPC
          const rpcEndpoint = (env.SOLANA_RPC_URL || '').trim() || (keyData.endpoint_url || '').trim() || getSolanaRpcUrl(env)
          const rpcResponse = await fetch(rpcEndpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              jsonrpc: '2.0',
              id: 1,
              method: 'getHealth'
            })
          })
          const rpcResult = await rpcResponse.json() as { result?: string }
          testResult.success = rpcResult.result === 'ok'
          break

        default:
          testResult.error = `Unknown service type: ${keyData.service}`
      }
    } catch (e) {
      testResult.error = (e as Error).message
    }

    testResult.latency = Date.now() - startTime

    // 更新最后使用时间和错误信息
    const now = Math.floor(Date.now() / 1000)
    await env.DB.prepare(
      'UPDATE api_keys SET last_used_at = ?, last_error = ? WHERE id = ?'
    ).bind(now, testResult.error || null, id).run()

    return jsonResponse(testResult)
  } catch (error) {
    console.error('Error testing API key:', error)
    return jsonResponse({ error: 'Failed to test API key' }, 500)
  }
}

/**
 * 轮换 API 密钥
 */
export async function rotateApiKey(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as { newKey: string }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const encryptedKey = await encryptKey(body.newKey, requireEncryptionKey(env))
    const keyPreview = maskKey(body.newKey)

    await env.DB.prepare(
      'UPDATE api_keys SET encrypted_key = ?, key_preview = ?, updated_at = ? WHERE id = ?'
    ).bind(encryptedKey, keyPreview, now, id).run()

    await logAdminAction(env, adminContext, 'ROTATE_API_KEY', 'api_key', id, 
      `轮换 API 密钥 ID: ${id}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error rotating API key:', error)
    return jsonResponse({ error: 'Failed to rotate API key' }, 500)
  }
}

// ============================================
// AI 用量监控
// ============================================

/**
 * 获取实时 AI 用量
 */
export async function getAiUsageRealtime(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const hourAgo = now - 3600
    const dayAgo = now - 86400

    // 当前小时统计
    const hourStats = await env.DB.prepare(`
      SELECT 
        COUNT(*) as call_count,
        SUM(total_tokens) as total_tokens,
        SUM(cost_usd) as total_cost,
        AVG(latency_ms) as avg_latency
      FROM ai_usage_logs 
      WHERE created_at >= ?
    `).bind(hourAgo).first()

    // 今日统计
    const todayStart = new Date()
    todayStart.setHours(0, 0, 0, 0)
    const todayStats = await env.DB.prepare(`
      SELECT 
        COUNT(*) as call_count,
        SUM(total_tokens) as total_tokens,
        SUM(cost_usd) as total_cost
      FROM ai_usage_logs 
      WHERE created_at >= ?
    `).bind(Math.floor(todayStart.getTime() / 1000)).first()

    // 本月统计
    const monthStart = new Date()
    monthStart.setDate(1)
    monthStart.setHours(0, 0, 0, 0)
    const monthStats = await env.DB.prepare(`
      SELECT 
        COUNT(*) as call_count,
        SUM(total_tokens) as total_tokens,
        SUM(cost_usd) as total_cost
      FROM ai_usage_logs 
      WHERE created_at >= ?
    `).bind(Math.floor(monthStart.getTime() / 1000)).first()

    // 按功能分组
    const byFunction = await env.DB.prepare(`
      SELECT 
        function_type,
        COUNT(*) as call_count,
        SUM(total_tokens) as total_tokens
      FROM ai_usage_logs 
      WHERE created_at >= ?
      GROUP BY function_type
    `).bind(dayAgo).all()

    return jsonResponse({
      hourly: {
        callCount: hourStats?.call_count || 0,
        totalTokens: hourStats?.total_tokens || 0,
        totalCost: hourStats?.total_cost || 0,
        avgLatency: Math.round(hourStats?.avg_latency || 0),
      },
      today: {
        callCount: todayStats?.call_count || 0,
        totalTokens: todayStats?.total_tokens || 0,
        totalCost: todayStats?.total_cost || 0,
      },
      monthly: {
        callCount: monthStats?.call_count || 0,
        totalTokens: monthStats?.total_tokens || 0,
        totalCost: monthStats?.total_cost || 0,
      },
      byFunction: byFunction.results || [],
    })
  } catch (error) {
    console.error('Error getting AI usage realtime:', error)
    return jsonResponse({ error: 'Failed to get AI usage' }, 500)
  }
}

/**
 * 获取 AI 用量统计（按日/月）
 */
export async function getAiUsageStats(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const period = url.searchParams.get('period') || 'daily' // daily/monthly
  const days = parseInt(url.searchParams.get('days') || '30')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const startTime = now - (days * 86400)

    let groupBy: string
    let dateFormat: string

    if (period === 'monthly') {
      groupBy = "strftime('%Y-%m', datetime(created_at, 'unixepoch'))"
      dateFormat = 'YYYY-MM'
    } else {
      groupBy = "strftime('%Y-%m-%d', datetime(created_at, 'unixepoch'))"
      dateFormat = 'YYYY-MM-DD'
    }

    const result = await env.DB.prepare(`
      SELECT 
        ${groupBy} as date,
        COUNT(*) as call_count,
        SUM(total_tokens) as total_tokens,
        SUM(prompt_tokens) as prompt_tokens,
        SUM(completion_tokens) as completion_tokens,
        SUM(cost_usd) as total_cost,
        AVG(latency_ms) as avg_latency,
        SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) as error_count
      FROM ai_usage_logs 
      WHERE created_at >= ?
      GROUP BY ${groupBy}
      ORDER BY date DESC
    `).bind(startTime).all()

    return jsonResponse({ stats: result.results || [], period, days })
  } catch (error) {
    console.error('Error getting AI usage stats:', error)
    return jsonResponse({ error: 'Failed to get AI usage stats' }, 500)
  }
}

/**
 * 获取高消耗用户排行
 */
export async function getTopConsumers(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const limit = parseInt(url.searchParams.get('limit') || '20')
  const days = parseInt(url.searchParams.get('days') || '30')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const startTime = Math.floor(Date.now() / 1000) - (days * 86400)

    const result = await env.DB.prepare(`
      SELECT 
        user_id,
        wallet_address,
        COUNT(*) as call_count,
        SUM(total_tokens) as total_tokens,
        SUM(cost_usd) as total_cost
      FROM ai_usage_logs 
      WHERE created_at >= ? AND user_id IS NOT NULL
      GROUP BY user_id
      ORDER BY total_tokens DESC
      LIMIT ?
    `).bind(startTime, limit).all()

    return jsonResponse({ users: result.results || [], days, limit })
  } catch (error) {
    console.error('Error getting top consumers:', error)
    return jsonResponse({ error: 'Failed to get top consumers' }, 500)
  }
}

// ============================================
// 配额管理
// ============================================

/**
 * 获取全局配额设置
 */
export async function getGlobalQuota(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const quotaConfigs = await env.DB.prepare(`
      SELECT config_key, config_value 
      FROM app_config 
      WHERE category = 'quota' AND is_active = 1
    `).all()

    const quota: Record<string, any> = {}
    for (const row of (quotaConfigs.results || [])) {
      const key = (row as any).config_key.replace('quota.', '')
      quota[key] = (row as any).config_value
    }

    return jsonResponse({ quota })
  } catch (error) {
    console.error('Error getting global quota:', error)
    return jsonResponse({ error: 'Failed to get global quota' }, 500)
  }
}

/**
 * 更新全局配额
 */
export async function updateGlobalQuota(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as Record<string, string | number>

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    for (const [key, value] of Object.entries(body)) {
      const configKey = key.startsWith('quota.') ? key : `quota.${key}`
      await env.DB.prepare(
        'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ? WHERE config_key = ?'
      ).bind(String(value), adminContext.email, now, configKey).run()
    }

    await logAdminAction(env, adminContext, 'UPDATE_QUOTA', 'quota', 'global', 
      `更新全局配额: ${JSON.stringify(body)}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating global quota:', error)
    return jsonResponse({ error: 'Failed to update global quota' }, 500)
  }
}

/**
 * 获取用户配额
 */
export async function getUserQuota(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    // 获取用户自定义配额
    const override = await env.DB.prepare(
      'SELECT * FROM user_quota_overrides WHERE user_id = ?'
    ).bind(userId).first()

    // 获取用户信息
    const user = await env.DB.prepare(
      'SELECT subscription_type, current_tier FROM users WHERE id = ?'
    ).bind(userId).first()

    // 获取本月用量
    const monthStart = new Date()
    monthStart.setDate(1)
    monthStart.setHours(0, 0, 0, 0)
    const usage = await env.DB.prepare(`
      SELECT SUM(total_tokens) as used_tokens
      FROM ai_usage_logs 
      WHERE user_id = ? AND created_at >= ?
    `).bind(userId, Math.floor(monthStart.getTime() / 1000)).first()

    return jsonResponse({
      userId,
      subscriptionType: user?.subscription_type,
      currentTier: user?.current_tier,
      override: override ? {
        monthlyTokenLimit: override.monthly_token_limit,
        dailyConversationLimit: override.daily_conversation_limit,
        customMultiplier: override.custom_multiplier,
        reason: override.reason,
        expiresAt: override.expires_at ? new Date(override.expires_at * 1000).toISOString() : null,
      } : null,
      usedTokens: usage?.used_tokens || 0,
    })
  } catch (error) {
    console.error('Error getting user quota:', error)
    return jsonResponse({ error: 'Failed to get user quota' }, 500)
  }
}

/**
 * 设置用户自定义配额
 */
export async function setUserQuota(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      monthlyTokenLimit?: number
      dailyConversationLimit?: number
      customMultiplier?: number
      reason: string
      expiresAt?: string
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const expiresAt = body.expiresAt ? Math.floor(new Date(body.expiresAt).getTime() / 1000) : null

    // 使用 upsert
    await env.DB.prepare(`
      INSERT INTO user_quota_overrides (user_id, monthly_token_limit, daily_conversation_limit, 
                                       custom_multiplier, reason, admin_id, expires_at, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id) DO UPDATE SET
        monthly_token_limit = excluded.monthly_token_limit,
        daily_conversation_limit = excluded.daily_conversation_limit,
        custom_multiplier = excluded.custom_multiplier,
        reason = excluded.reason,
        admin_id = excluded.admin_id,
        expires_at = excluded.expires_at,
        updated_at = excluded.updated_at
    `).bind(
      userId,
      body.monthlyTokenLimit || null,
      body.dailyConversationLimit || null,
      body.customMultiplier || null,
      body.reason,
      adminContext.email,
      expiresAt,
      now,
      now
    ).run()

    await logAdminAction(env, adminContext, 'SET_USER_QUOTA', 'user', userId, 
      `设置用户配额: ${JSON.stringify(body)}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error setting user quota:', error)
    return jsonResponse({ error: 'Failed to set user quota' }, 500)
  }
}

/**
 * 紧急熔断开关
 */
export async function toggleCircuitBreaker(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as { enabled: boolean; reason?: string }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    // 更新功能开关
    await env.DB.prepare(
      'UPDATE feature_flags SET enabled = ?, updated_at = ? WHERE key = ?'
    ).bind(body.enabled ? 0 : 1, now, 'ai_service_enabled').run()

    // 同步到 KV
    if (env.KV) {
      await env.KV.put('feature:ai_service_enabled', body.enabled ? '0' : '1')
    }

    await logAdminAction(env, adminContext, 'CIRCUIT_BREAKER', 'ai_service', 
      body.enabled ? 'enable' : 'disable', 
      `AI 服务熔断: ${body.enabled ? '启用熔断' : '关闭熔断'}${body.reason ? `, 原因: ${body.reason}` : ''}`)

    return jsonResponse({ success: true, circuitBreakerEnabled: body.enabled })
  } catch (error) {
    console.error('Error toggling circuit breaker:', error)
    return jsonResponse({ error: 'Failed to toggle circuit breaker' }, 500)
  }
}

/**
 * 处理 AI 服务管理路由
 */
export async function handleAiServiceRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  // API 密钥管理
  if (request.method === 'GET' && path === '/admin/ai/keys') {
    return getApiKeys(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/ai/keys') {
    return addApiKey(request, env, adminContext)
  }
  
  const keyIdMatch = path.match(/^\/admin\/ai\/keys\/(\d+)$/)
  if (keyIdMatch) {
    if (request.method === 'PUT') {
      return updateApiKey(request, env, adminContext, keyIdMatch[1])
    }
    if (request.method === 'DELETE') {
      return deleteApiKey(request, env, adminContext, keyIdMatch[1])
    }
  }

  const testKeyMatch = path.match(/^\/admin\/ai\/keys\/(\d+)\/test$/)
  if (request.method === 'POST' && testKeyMatch) {
    return testApiKey(request, env, adminContext, testKeyMatch[1])
  }

  const rotateKeyMatch = path.match(/^\/admin\/ai\/keys\/(\d+)\/rotate$/)
  if (request.method === 'POST' && rotateKeyMatch) {
    return rotateApiKey(request, env, adminContext, rotateKeyMatch[1])
  }

  // 用量监控
  if (request.method === 'GET' && path === '/admin/ai/usage/realtime') {
    return getAiUsageRealtime(request, env, adminContext)
  }
  if (request.method === 'GET' && path === '/admin/ai/usage/stats') {
    return getAiUsageStats(request, env, adminContext)
  }
  if (request.method === 'GET' && path === '/admin/ai/usage/top-consumers') {
    return getTopConsumers(request, env, adminContext)
  }

  // 配额管理
  if (request.method === 'GET' && path === '/admin/ai/quota/global') {
    return getGlobalQuota(request, env, adminContext)
  }
  if (request.method === 'PUT' && path === '/admin/ai/quota/global') {
    return updateGlobalQuota(request, env, adminContext)
  }

  const userQuotaMatch = path.match(/^\/admin\/ai\/quota\/user\/([^/]+)$/)
  if (userQuotaMatch) {
    if (request.method === 'GET') {
      return getUserQuota(request, env, adminContext, userQuotaMatch[1])
    }
    if (request.method === 'PUT') {
      return setUserQuota(request, env, adminContext, userQuotaMatch[1])
    }
  }

  // 熔断开关
  if (request.method === 'POST' && path === '/admin/ai/circuit-breaker') {
    return toggleCircuitBreaker(request, env, adminContext)
  }

  return null
}
