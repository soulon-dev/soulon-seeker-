/**
 * 管理后台 - MEMO 积分管理 API
 * 包含积分增减、规则配置、交易记录、Tier 等级管理
 */

import { AdminContext, logAdminAction } from './middleware'

// ============================================
// 积分概览
// ============================================

/**
 * 获取积分系统概览
 */
export async function getMemoOverview(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    // 全网积分总量
    const totalBalance = await env.DB.prepare(
      'SELECT SUM(memo_balance) as total FROM users'
    ).first()

    // 今日新增积分
    const todayStart = new Date()
    todayStart.setHours(0, 0, 0, 0)
    const todayEarned = await env.DB.prepare(`
      SELECT SUM(amount) as total 
      FROM memo_transactions 
      WHERE type IN ('earn', 'admin_add', 'airdrop', 'reward') 
        AND created_at >= ?
    `).bind(Math.floor(todayStart.getTime() / 1000)).first()

    // 今日消耗积分
    const todaySpent = await env.DB.prepare(`
      SELECT SUM(ABS(amount)) as total 
      FROM memo_transactions 
      WHERE type IN ('spend', 'admin_subtract') 
        AND created_at >= ?
    `).bind(Math.floor(todayStart.getTime() / 1000)).first()

    // 积分分布
    const distribution = await env.DB.prepare(`
      SELECT 
        CASE 
          WHEN memo_balance = 0 THEN '0'
          WHEN memo_balance < 1000 THEN '1-999'
          WHEN memo_balance < 5000 THEN '1000-4999'
          WHEN memo_balance < 20000 THEN '5000-19999'
          WHEN memo_balance < 100000 THEN '20000-99999'
          ELSE '100000+'
        END as range,
        COUNT(*) as count
      FROM users
      GROUP BY range
      ORDER BY MIN(memo_balance)
    `).all()

    // Tier 分布
    const tierDistribution = await env.DB.prepare(`
      SELECT current_tier, COUNT(*) as count
      FROM users
      GROUP BY current_tier
      ORDER BY current_tier
    `).all()

    // 最近 7 天积分趋势
    const weekAgo = Math.floor(Date.now() / 1000) - (7 * 86400)
    const trend = await env.DB.prepare(`
      SELECT 
        strftime('%Y-%m-%d', datetime(created_at, 'unixepoch')) as date,
        SUM(CASE WHEN type IN ('earn', 'admin_add', 'airdrop', 'reward') THEN amount ELSE 0 END) as earned,
        SUM(CASE WHEN type IN ('spend', 'admin_subtract') THEN ABS(amount) ELSE 0 END) as spent
      FROM memo_transactions
      WHERE created_at >= ?
      GROUP BY date
      ORDER BY date
    `).bind(weekAgo).all()

    return jsonResponse({
      totalBalance: totalBalance?.total || 0,
      todayEarned: todayEarned?.total || 0,
      todaySpent: todaySpent?.total || 0,
      distribution: distribution.results || [],
      tierDistribution: tierDistribution.results || [],
      trend: trend.results || [],
    })
  } catch (error) {
    console.error('Error getting memo overview:', error)
    return jsonResponse({ error: 'Failed to get memo overview' }, 500)
  }
}

// ============================================
// 积分操作
// ============================================

/**
 * 调整单用户积分
 */
export async function adjustUserMemo(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      userId: string
      operation: 'add' | 'subtract' | 'set'
      amount: number
      reason: string
    }

    if (!body.reason || body.reason.trim().length < 5) {
      return jsonResponse({ error: 'Reason is required (min 5 chars)' }, 400)
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    // 获取当前余额
    const user = await env.DB.prepare(
      'SELECT memo_balance FROM users WHERE id = ?'
    ).bind(body.userId).first()

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404)
    }

    const balanceBefore = user.memo_balance || 0
    let balanceAfter: number
    let transactionType: string
    let transactionAmount: number

    switch (body.operation) {
      case 'add':
        balanceAfter = balanceBefore + body.amount
        transactionType = 'admin_add'
        transactionAmount = body.amount
        break
      case 'subtract':
        balanceAfter = Math.max(0, balanceBefore - body.amount)
        transactionType = 'admin_subtract'
        transactionAmount = -Math.min(body.amount, balanceBefore)
        break
      case 'set':
        balanceAfter = body.amount
        transactionType = balanceAfter > balanceBefore ? 'admin_add' : 'admin_subtract'
        transactionAmount = balanceAfter - balanceBefore
        break
      default:
        return jsonResponse({ error: 'Invalid operation' }, 400)
    }

    // 更新用户余额
    await env.DB.prepare(
      'UPDATE users SET memo_balance = ? WHERE id = ?'
    ).bind(balanceAfter, body.userId).run()

    // 记录交易
    await env.DB.prepare(`
      INSERT INTO memo_transactions 
        (user_id, type, amount, balance_before, balance_after, source, description, admin_id, admin_reason, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      body.userId,
      transactionType,
      transactionAmount,
      balanceBefore,
      balanceAfter,
      'admin',
      `管理员${body.operation === 'add' ? '增加' : body.operation === 'subtract' ? '扣除' : '设置'}积分`,
      adminContext.email,
      body.reason,
      now
    ).run()

    // 检查是否需要更新 Tier
    await updateUserTierIfNeeded(env, body.userId, balanceAfter, adminContext)

    await logAdminAction(env, adminContext, 'ADJUST_MEMO', 'user', body.userId, 
      `${body.operation} ${body.amount} MEMO, 原因: ${body.reason}`)

    return jsonResponse({ 
      success: true, 
      balanceBefore, 
      balanceAfter,
      change: transactionAmount
    })
  } catch (error) {
    console.error('Error adjusting user memo:', error)
    return jsonResponse({ error: 'Failed to adjust user memo' }, 500)
  }
}

/**
 * 批量调整积分
 */
export async function batchAdjustMemo(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      userIds: string[]
      operation: 'add' | 'subtract'
      amount: number
      reason: string
    }

    if (!body.reason || body.reason.trim().length < 5) {
      return jsonResponse({ error: 'Reason is required (min 5 chars)' }, 400)
    }

    if (body.userIds.length > 1000) {
      return jsonResponse({ error: 'Max 1000 users per batch' }, 400)
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const results: Array<{ userId: string; success: boolean; error?: string }> = []

    for (const userId of body.userIds) {
      try {
        const user = await env.DB.prepare(
          'SELECT memo_balance FROM users WHERE id = ?'
        ).bind(userId).first()

        if (!user) {
          results.push({ userId, success: false, error: 'User not found' })
          continue
        }

        const balanceBefore = user.memo_balance || 0
        const balanceAfter = body.operation === 'add' 
          ? balanceBefore + body.amount
          : Math.max(0, balanceBefore - body.amount)
        const transactionAmount = body.operation === 'add' 
          ? body.amount 
          : -Math.min(body.amount, balanceBefore)

        await env.DB.prepare(
          'UPDATE users SET memo_balance = ? WHERE id = ?'
        ).bind(balanceAfter, userId).run()

        await env.DB.prepare(`
          INSERT INTO memo_transactions 
            (user_id, type, amount, balance_before, balance_after, source, description, admin_id, admin_reason, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        `).bind(
          userId,
          body.operation === 'add' ? 'admin_add' : 'admin_subtract',
          transactionAmount,
          balanceBefore,
          balanceAfter,
          'admin',
          `批量${body.operation === 'add' ? '增加' : '扣除'}积分`,
          adminContext.email,
          body.reason,
          now
        ).run()

        results.push({ userId, success: true })
      } catch (e) {
        results.push({ userId, success: false, error: (e as Error).message })
      }
    }

    await logAdminAction(env, adminContext, 'BATCH_ADJUST_MEMO', 'users', 'batch', 
      `批量${body.operation} ${body.amount} MEMO 给 ${body.userIds.length} 用户`)

    const successCount = results.filter(r => r.success).length
    return jsonResponse({ 
      success: true, 
      total: body.userIds.length,
      succeeded: successCount,
      failed: body.userIds.length - successCount,
      results 
    })
  } catch (error) {
    console.error('Error batch adjusting memo:', error)
    return jsonResponse({ error: 'Failed to batch adjust memo' }, 500)
  }
}

/**
 * 获取积分交易记录
 */
export async function getMemoTransactions(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const userId = url.searchParams.get('userId')
  const type = url.searchParams.get('type')
  const source = url.searchParams.get('source')
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let whereClause = '1=1'
    const params: any[] = []

    if (userId) {
      whereClause += ' AND user_id = ?'
      params.push(userId)
    }
    if (type) {
      whereClause += ' AND type = ?'
      params.push(type)
    }
    if (source) {
      whereClause += ' AND source = ?'
      params.push(source)
    }

    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM memo_transactions WHERE ${whereClause}`
    ).bind(...params).first()
    const total = countResult?.count || 0

    const offset = (page - 1) * pageSize
    const result = await env.DB.prepare(`
      SELECT * FROM memo_transactions 
      WHERE ${whereClause} 
      ORDER BY created_at DESC 
      LIMIT ? OFFSET ?
    `).bind(...params, pageSize, offset).all()

    const transactions = (result.results || []).map((row: any) => ({
      id: row.id,
      userId: row.user_id,
      walletAddress: row.wallet_address,
      type: row.type,
      amount: row.amount,
      balanceBefore: row.balance_before,
      balanceAfter: row.balance_after,
      source: row.source,
      description: row.description,
      adminId: row.admin_id,
      adminReason: row.admin_reason,
      referenceId: row.reference_id,
      referenceType: row.reference_type,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ transactions, total, page, pageSize })
  } catch (error) {
    console.error('Error getting memo transactions:', error)
    return jsonResponse({ error: 'Failed to get memo transactions' }, 500)
  }
}

// ============================================
// 积分规则配置
// ============================================

/**
 * 获取积分规则
 */
export async function getMemoRules(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    // 从 app_config 获取 memo 相关配置
    const result = await env.DB.prepare(`
      SELECT config_key, config_value, value_type, sub_category, display_name, description
      FROM app_config 
      WHERE category = 'memo' AND is_active = 1
      ORDER BY sub_category, config_key
    `).all()

    // 按子分类分组
    const grouped: Record<string, any[]> = {}
    for (const row of (result.results || [])) {
      const r = row as any
      const subCat = r.sub_category || 'other'
      if (!grouped[subCat]) {
        grouped[subCat] = []
      }
      grouped[subCat].push({
        key: r.config_key,
        value: r.value_type === 'json' ? JSON.parse(r.config_value) : r.config_value,
        valueType: r.value_type,
        displayName: r.display_name,
        description: r.description,
      })
    }

    return jsonResponse({ rules: grouped })
  } catch (error) {
    console.error('Error getting memo rules:', error)
    return jsonResponse({ error: 'Failed to get memo rules' }, 500)
  }
}

/**
 * 更新积分规则
 */
export async function updateMemoRule(
  request: Request,
  env: any,
  adminContext: AdminContext,
  key: string
): Promise<Response> {
  try {
    const body = await request.json() as { value: string | number | any[]; reason?: string }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const configKey = key.startsWith('memo.') ? key : `memo.${key}`
    const valueStr = typeof body.value === 'object' ? JSON.stringify(body.value) : String(body.value)

    // 获取旧值
    const existing = await env.DB.prepare(
      'SELECT config_value FROM app_config WHERE config_key = ?'
    ).bind(configKey).first()

    if (!existing) {
      return jsonResponse({ error: 'Rule not found' }, 404)
    }

    // 记录历史
    await env.DB.prepare(`
      INSERT INTO config_change_history (config_key, old_value, new_value, changed_by, change_reason, ip_address)
      VALUES (?, ?, ?, ?, ?, ?)
    `).bind(configKey, existing.config_value, valueStr, adminContext.email, body.reason || null, adminContext.ip).run()

    // 更新配置
    await env.DB.prepare(
      'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ? WHERE config_key = ?'
    ).bind(valueStr, adminContext.email, now, configKey).run()

    await logAdminAction(env, adminContext, 'UPDATE_MEMO_RULE', 'memo_rule', key, 
      `更新积分规则: ${existing.config_value} -> ${valueStr}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating memo rule:', error)
    return jsonResponse({ error: 'Failed to update memo rule' }, 500)
  }
}

// ============================================
// Tier 等级管理
// ============================================

/**
 * 获取 Tier 配置
 */
export async function getTierConfig(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(`
      SELECT config_key, config_value, value_type, display_name, description
      FROM app_config 
      WHERE category = 'tier' AND is_active = 1
      ORDER BY config_key
    `).all()

    const config: Record<string, any> = {}
    for (const row of (result.results || [])) {
      const r = row as any
      const key = r.config_key.replace('tier.', '')
      config[key] = r.value_type === 'json' ? JSON.parse(r.config_value) : r.config_value
    }

    return jsonResponse({ config })
  } catch (error) {
    console.error('Error getting tier config:', error)
    return jsonResponse({ error: 'Failed to get tier config' }, 500)
  }
}

/**
 * 更新 Tier 配置
 */
export async function updateTierConfig(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as Record<string, any>

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    for (const [key, value] of Object.entries(body)) {
      const configKey = key.startsWith('tier.') ? key : `tier.${key}`
      const valueStr = typeof value === 'object' ? JSON.stringify(value) : String(value)

      await env.DB.prepare(
        'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ? WHERE config_key = ?'
      ).bind(valueStr, adminContext.email, now, configKey).run()
    }

    await logAdminAction(env, adminContext, 'UPDATE_TIER_CONFIG', 'tier', 'config', 
      `更新 Tier 配置: ${JSON.stringify(body)}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating tier config:', error)
    return jsonResponse({ error: 'Failed to update tier config' }, 500)
  }
}

/**
 * 获取 Tier 统计
 */
export async function getTierStats(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const distribution = await env.DB.prepare(`
      SELECT 
        current_tier,
        COUNT(*) as user_count,
        AVG(memo_balance) as avg_balance,
        SUM(memo_balance) as total_balance
      FROM users
      GROUP BY current_tier
      ORDER BY current_tier
    `).all()

    // 最近升级用户
    const recentUpgrades = await env.DB.prepare(`
      SELECT user_id, old_tier, new_tier, change_reason, created_at
      FROM user_tier_history
      WHERE new_tier > old_tier
      ORDER BY created_at DESC
      LIMIT 20
    `).all()

    return jsonResponse({
      distribution: distribution.results || [],
      recentUpgrades: (recentUpgrades.results || []).map((row: any) => ({
        userId: row.user_id,
        oldTier: row.old_tier,
        newTier: row.new_tier,
        changeReason: row.change_reason,
        createdAt: new Date(row.created_at * 1000).toISOString(),
      })),
    })
  } catch (error) {
    console.error('Error getting tier stats:', error)
    return jsonResponse({ error: 'Failed to get tier stats' }, 500)
  }
}

/**
 * 调整用户 Tier
 */
export async function adjustUserTier(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      newTier: number
      reason: string
      expiresAt?: string
    }

    if (body.newTier < 1 || body.newTier > 5) {
      return jsonResponse({ error: 'Invalid tier (1-5)' }, 400)
    }

    if (!body.reason) {
      return jsonResponse({ error: 'Reason is required' }, 400)
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    // 获取当前等级
    const user = await env.DB.prepare(
      'SELECT current_tier, memo_balance FROM users WHERE id = ?'
    ).bind(userId).first()

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404)
    }

    const oldTier = user.current_tier

    // 更新用户等级
    await env.DB.prepare(
      'UPDATE users SET current_tier = ? WHERE id = ?'
    ).bind(body.newTier, userId).run()

    // 记录历史
    const expiresAt = body.expiresAt ? Math.floor(new Date(body.expiresAt).getTime() / 1000) : null
    await env.DB.prepare(`
      INSERT INTO user_tier_history 
        (user_id, old_tier, new_tier, old_memo_balance, new_memo_balance, change_reason, admin_id, admin_note, expires_at, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      userId,
      oldTier,
      body.newTier,
      user.memo_balance,
      user.memo_balance,
      'admin',
      adminContext.email,
      body.reason,
      expiresAt,
      now
    ).run()

    await logAdminAction(env, adminContext, 'ADJUST_TIER', 'user', userId, 
      `调整 Tier: ${oldTier} -> ${body.newTier}, 原因: ${body.reason}`)

    return jsonResponse({ success: true, oldTier, newTier: body.newTier })
  } catch (error) {
    console.error('Error adjusting user tier:', error)
    return jsonResponse({ error: 'Failed to adjust user tier' }, 500)
  }
}

/**
 * 获取用户 Tier 历史
 */
export async function getUserTierHistory(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(`
      SELECT * FROM user_tier_history
      WHERE user_id = ?
      ORDER BY created_at DESC
      LIMIT 50
    `).bind(userId).all()

    const history = (result.results || []).map((row: any) => ({
      id: row.id,
      oldTier: row.old_tier,
      newTier: row.new_tier,
      oldMemoBalance: row.old_memo_balance,
      newMemoBalance: row.new_memo_balance,
      changeReason: row.change_reason,
      adminId: row.admin_id,
      adminNote: row.admin_note,
      expiresAt: row.expires_at ? new Date(row.expires_at * 1000).toISOString() : null,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ history })
  } catch (error) {
    console.error('Error getting user tier history:', error)
    return jsonResponse({ error: 'Failed to get user tier history' }, 500)
  }
}

// 辅助函数：检查并更新用户 Tier
async function updateUserTierIfNeeded(env: any, userId: string, memoBalance: number, adminContext: AdminContext) {
  try {
    // 获取 Tier 配置
    const tierPointsConfig = await env.DB.prepare(
      "SELECT config_value FROM app_config WHERE config_key = 'tier.points'"
    ).first()
    
    if (!tierPointsConfig) return

    const tierPoints = JSON.parse(tierPointsConfig.config_value)
    
    // 计算应该的 Tier
    let newTier = 1
    for (let i = tierPoints.length - 1; i >= 0; i--) {
      if (memoBalance >= tierPoints[i]) {
        newTier = i + 1
        break
      }
    }

    // 获取当前 Tier
    const user = await env.DB.prepare(
      'SELECT current_tier FROM users WHERE id = ?'
    ).bind(userId).first()

    if (user && user.current_tier !== newTier) {
      const now = Math.floor(Date.now() / 1000)
      
      // 更新 Tier
      await env.DB.prepare(
        'UPDATE users SET current_tier = ? WHERE id = ?'
      ).bind(newTier, userId).run()

      // 记录历史
      await env.DB.prepare(`
        INSERT INTO user_tier_history 
          (user_id, old_tier, new_tier, old_memo_balance, new_memo_balance, change_reason, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
      `).bind(userId, user.current_tier, newTier, memoBalance, memoBalance, 'natural', now).run()
    }
  } catch (error) {
    console.error('Error updating user tier:', error)
  }
}

/**
 * 处理 MEMO 管理路由
 */
export async function handleMemoRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  // 积分概览
  if (request.method === 'GET' && path === '/admin/memo/overview') {
    return getMemoOverview(request, env, adminContext)
  }

  // 积分操作
  if (request.method === 'POST' && path === '/admin/memo/adjust') {
    return adjustUserMemo(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/memo/batch-adjust') {
    return batchAdjustMemo(request, env, adminContext)
  }

  // 交易记录
  if (request.method === 'GET' && path === '/admin/memo/transactions') {
    return getMemoTransactions(request, env, adminContext)
  }

  // 积分规则
  if (request.method === 'GET' && path === '/admin/memo/rules') {
    return getMemoRules(request, env, adminContext)
  }
  const ruleMatch = path.match(/^\/admin\/memo\/rules\/([^/]+)$/)
  if (request.method === 'PUT' && ruleMatch) {
    return updateMemoRule(request, env, adminContext, ruleMatch[1])
  }

  // Tier 管理
  if (request.method === 'GET' && path === '/admin/tier/config') {
    return getTierConfig(request, env, adminContext)
  }
  if (request.method === 'PUT' && path === '/admin/tier/config') {
    return updateTierConfig(request, env, adminContext)
  }
  if (request.method === 'GET' && path === '/admin/tier/stats') {
    return getTierStats(request, env, adminContext)
  }

  const tierUserMatch = path.match(/^\/admin\/tier\/user\/([^/]+)$/)
  if (request.method === 'PUT' && tierUserMatch) {
    return adjustUserTier(request, env, adminContext, tierUserMatch[1])
  }

  const tierHistoryMatch = path.match(/^\/admin\/tier\/user\/([^/]+)\/history$/)
  if (request.method === 'GET' && tierHistoryMatch) {
    return getUserTierHistory(request, env, adminContext, tierHistoryMatch[1])
  }

  return null
}

// 辅助函数
function jsonResponse(data: any, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}
