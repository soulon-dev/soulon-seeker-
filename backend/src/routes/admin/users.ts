/**
 * 管理后台 - 用户管理 API（完整版）
 * 
 * 功能：
 * - 用户列表查询（支持多条件筛选）
 * - 用户详情查看（包含完整信息）
 * - 会员权限调整（订阅、Tier、特殊权限）
 * - 用户操作历史查看
 * - 用户封禁/解封
 */

import { AdminContext, logAdminAction } from './middleware'

// JSON 响应辅助函数
function jsonResponse(data: unknown, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

// ============================================
// 用户列表
// ============================================

/**
 * 获取用户列表（支持多条件筛选）
 */
export async function getUsers(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')
  const search = url.searchParams.get('search') || ''
  const subscriptionType = url.searchParams.get('subscriptionType') || ''
  const tier = url.searchParams.get('tier') || ''
  const status = url.searchParams.get('status') || '' // active, banned, inactive
  const sortBy = url.searchParams.get('sortBy') || 'created_at'
  const sortOrder = url.searchParams.get('sortOrder') || 'desc'

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    // 构建查询条件
    const conditions: string[] = ['1=1']
    const params: any[] = []

    if (search) {
      conditions.push('(wallet_address LIKE ? OR id LIKE ?)')
      params.push(`%${search}%`, `%${search}%`)
    }
    if (subscriptionType) {
      conditions.push('subscription_type = ?')
      params.push(subscriptionType)
    }
    if (tier) {
      conditions.push('current_tier = ?')
      params.push(parseInt(tier))
    }
    if (status === 'banned') {
      conditions.push('is_banned = 1')
    } else if (status === 'active') {
      conditions.push('is_banned = 0 AND last_active_at > ?')
      params.push(Math.floor(Date.now() / 1000) - 7 * 24 * 3600) // 7天内活跃
    } else if (status === 'inactive') {
      conditions.push('is_banned = 0 AND last_active_at < ?')
      params.push(Math.floor(Date.now() / 1000) - 30 * 24 * 3600) // 30天内不活跃
    }

    const whereClause = conditions.join(' AND ')
    const validSortColumns = ['created_at', 'last_active_at', 'memo_balance', 'current_tier', 'total_tokens_used']
    const sortColumn = validSortColumns.includes(sortBy) ? sortBy : 'created_at'
    const sortDir = sortOrder === 'asc' ? 'ASC' : 'DESC'

    // 获取总数
    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM users WHERE ${whereClause}`
    ).bind(...params).first()
    const total = countResult?.count || 0

    // 获取分页数据
    const offset = (page - 1) * pageSize
    const dataResult = await env.DB.prepare(
      `SELECT * FROM users WHERE ${whereClause} ORDER BY ${sortColumn} ${sortDir} LIMIT ? OFFSET ?`
    ).bind(...params, pageSize, offset).all()

    const users = (dataResult.results || []).map((row: any) => ({
      id: row.id,
      walletAddress: row.wallet_address,
      createdAt: row.created_at,
      lastActiveAt: row.last_active_at,
      memoBalance: row.memo_balance || 0,
      currentTier: row.current_tier || 1,
      subscriptionType: row.subscription_type || 'FREE',
      subscriptionExpiry: row.subscription_expiry,
      stakedAmount: row.staked_amount || 0,
      stakingTier: row.staking_tier,
      isFounder: !!row.is_founder,
      isExpert: !!row.is_expert,
      isBanned: !!row.is_banned,
      totalTokensUsed: row.total_tokens_used || 0,
      memoriesCount: row.memories_count || 0,
      totalMemoEarned: row.total_memo_earned || 0,
      totalMemoSpent: row.total_memo_spent || 0,
    }))

    return jsonResponse({ users, total, page, pageSize })
  } catch (error) {
    console.error('Error getting users:', error)
    return jsonResponse({ error: 'Failed to get users' }, 500)
  }
}

// ============================================
// 用户详情
// ============================================

/**
 * 获取用户详细信息
 */
export async function getUserDetail(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    // 获取用户基本信息
    const user = await env.DB.prepare(
      'SELECT * FROM users WHERE id = ? OR wallet_address = ?'
    ).bind(userId, userId).first()

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404)
    }

    // 获取用户统计数据
    const stats = {
      // 积分统计
      memoTransactions: await env.DB.prepare(
        `SELECT 
          SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END) as total_earned,
          SUM(CASE WHEN amount < 0 THEN ABS(amount) ELSE 0 END) as total_spent,
          COUNT(*) as transaction_count
         FROM memo_transactions WHERE user_id = ?`
      ).bind(user.id).first(),

      // AI 使用统计
      aiUsage: await env.DB.prepare(
        `SELECT 
          COUNT(*) as total_calls,
          SUM(total_tokens) as total_tokens,
          SUM(cost_usd) as total_cost,
          MAX(created_at) as last_call_at
         FROM ai_usage_logs WHERE user_id = ?`
      ).bind(user.id).first(),

      // 订阅历史数量
      subscriptionCount: await env.DB.prepare(
        'SELECT COUNT(*) as count FROM payment_transactions WHERE user_id = ? AND type = ?'
      ).bind(user.id, 'subscription').first(),

      // 质押记录数量
      stakingCount: await env.DB.prepare(
        'SELECT COUNT(*) as count FROM staking_records WHERE user_id = ?'
      ).bind(user.id).first(),

      // 记忆数量
      memoriesCount: await env.DB.prepare(
        'SELECT COUNT(*) as count FROM memories WHERE user_id = ?'
      ).bind(user.id).first(),

      // 空投领取
      airdropsClaimed: await env.DB.prepare(
        `SELECT COUNT(*) as count, SUM(CAST(calculated_amount AS REAL)) as total_amount
         FROM airdrop_recipients WHERE user_id = ? AND status = 'claimed'`
      ).bind(user.id).first(),
    }

    // 获取 Tier 历史
    const tierHistory = await env.DB.prepare(
      `SELECT * FROM user_tier_history WHERE user_id = ? ORDER BY created_at DESC LIMIT 10`
    ).bind(user.id).all()

    // 获取配额覆盖设置
    const quotaOverride = await env.DB.prepare(
      'SELECT * FROM user_quota_overrides WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)'
    ).bind(user.id, Math.floor(Date.now() / 1000)).first()

    return jsonResponse({
      user: {
        id: user.id,
        walletAddress: user.wallet_address,
        createdAt: user.created_at,
        lastActiveAt: user.last_active_at,
        memoBalance: user.memo_balance || 0,
        currentTier: user.current_tier || 1,
        sovereignRatio: user.sovereign_ratio || 0,
        subscriptionType: user.subscription_type || 'FREE',
        subscriptionExpiry: user.subscription_expiry,
        stakedAmount: user.staked_amount || 0,
        stakingTier: user.staking_tier,
        isFounder: !!user.is_founder,
        isExpert: !!user.is_expert,
        isBanned: !!user.is_banned,
        banReason: user.ban_reason,
        bannedAt: user.banned_at,
        totalTokensUsed: user.total_tokens_used || 0,
        monthlyTokensUsed: user.monthly_tokens_used || 0,
      },
      stats: {
        memoEarned: stats.memoTransactions?.total_earned || 0,
        memoSpent: stats.memoTransactions?.total_spent || 0,
        memoTransactionCount: stats.memoTransactions?.transaction_count || 0,
        aiCalls: stats.aiUsage?.total_calls || 0,
        aiTokens: stats.aiUsage?.total_tokens || 0,
        aiCost: stats.aiUsage?.total_cost || 0,
        lastAiCallAt: stats.aiUsage?.last_call_at,
        subscriptionCount: stats.subscriptionCount?.count || 0,
        stakingCount: stats.stakingCount?.count || 0,
        memoriesCount: stats.memoriesCount?.count || 0,
        airdropsClaimed: stats.airdropsClaimed?.count || 0,
        airdropsAmount: stats.airdropsClaimed?.total_amount || 0,
      },
      tierHistory: tierHistory.results || [],
      quotaOverride: quotaOverride || null,
    })
  } catch (error) {
    console.error('Error getting user detail:', error)
    return jsonResponse({ error: 'Failed to get user detail' }, 500)
  }
}

// ============================================
// 用户权限管理
// ============================================

/**
 * 更新用户订阅状态
 */
export async function updateUserSubscription(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as {
      subscriptionType: string // FREE, MONTHLY, YEARLY
      expiryDate?: string // YYYY-MM-DD
      reason: string
    }

    // 获取当前用户
    const user = await env.DB.prepare(
      'SELECT * FROM users WHERE id = ?'
    ).bind(userId).first()

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404)
    }

    const oldSubscription = user.subscription_type
    const expiryTimestamp = body.expiryDate 
      ? Math.floor(new Date(body.expiryDate).getTime() / 1000) 
      : null

    // 更新用户订阅
    await env.DB.prepare(
      `UPDATE users SET 
        subscription_type = ?,
        subscription_expiry = ?,
        last_active_at = ?
       WHERE id = ?`
    ).bind(
      body.subscriptionType,
      expiryTimestamp,
      Math.floor(Date.now() / 1000),
      userId
    ).run()

    // 记录操作日志
    await logAdminAction(
      env,
      adminContext,
      'UPDATE_SUBSCRIPTION',
      'user',
      userId,
      `订阅变更: ${oldSubscription} → ${body.subscriptionType}, 原因: ${body.reason}`
    )

    return jsonResponse({
      success: true,
      oldSubscription,
      newSubscription: body.subscriptionType,
      expiryDate: body.expiryDate,
    })
  } catch (error) {
    console.error('Error updating subscription:', error)
    return jsonResponse({ error: 'Failed to update subscription' }, 500)
  }
}

/**
 * 更新用户 Tier 等级
 */
export async function updateUserTier(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as {
      newTier: number // 1-5
      reason: string
      expiresAt?: string // 可选，临时调整
    }

    if (body.newTier < 1 || body.newTier > 5) {
      return jsonResponse({ error: 'Invalid tier (must be 1-5)' }, 400)
    }

    // 获取当前用户
    const user = await env.DB.prepare(
      'SELECT * FROM users WHERE id = ?'
    ).bind(userId).first()

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404)
    }

    const oldTier = user.current_tier || 1
    const now = Math.floor(Date.now() / 1000)
    const expiresAt = body.expiresAt 
      ? Math.floor(new Date(body.expiresAt).getTime() / 1000) 
      : null

    // 更新用户 Tier
    await env.DB.prepare(
      'UPDATE users SET current_tier = ?, last_active_at = ? WHERE id = ?'
    ).bind(body.newTier, now, userId).run()

    // 记录 Tier 历史
    await env.DB.prepare(
      `INSERT INTO user_tier_history 
        (user_id, old_tier, new_tier, old_memo_balance, new_memo_balance, change_reason, admin_id, admin_note, expires_at, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      userId,
      oldTier,
      body.newTier,
      user.memo_balance,
      user.memo_balance,
      'admin_adjustment',
      adminContext.email,
      body.reason,
      expiresAt,
      now
    ).run()

    // 记录操作日志
    await logAdminAction(
      env,
      adminContext,
      'UPDATE_TIER',
      'user',
      userId,
      `Tier 变更: ${oldTier} → ${body.newTier}, 原因: ${body.reason}`
    )

    return jsonResponse({
      success: true,
      oldTier,
      newTier: body.newTier,
      expiresAt: body.expiresAt,
    })
  } catch (error) {
    console.error('Error updating tier:', error)
    return jsonResponse({ error: 'Failed to update tier' }, 500)
  }
}

/**
 * 设置用户特殊权限（创始人/技术专家）
 */
export async function updateUserSpecialStatus(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as {
      isFounder?: boolean
      isExpert?: boolean
      reason: string
    }

    // 获取当前用户
    const user = await env.DB.prepare(
      'SELECT * FROM users WHERE id = ?'
    ).bind(userId).first()

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404)
    }

    const updates: string[] = []
    const params: any[] = []
    const changes: string[] = []

    if (body.isFounder !== undefined) {
      updates.push('is_founder = ?')
      params.push(body.isFounder ? 1 : 0)
      changes.push(`创始人: ${body.isFounder}`)
    }
    if (body.isExpert !== undefined) {
      updates.push('is_expert = ?')
      params.push(body.isExpert ? 1 : 0)
      changes.push(`技术专家: ${body.isExpert}`)
    }

    if (updates.length === 0) {
      return jsonResponse({ error: 'No updates provided' }, 400)
    }

    updates.push('last_active_at = ?')
    params.push(Math.floor(Date.now() / 1000))
    params.push(userId)

    await env.DB.prepare(
      `UPDATE users SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    // 记录操作日志
    await logAdminAction(
      env,
      adminContext,
      'UPDATE_SPECIAL_STATUS',
      'user',
      userId,
      `特殊权限变更: ${changes.join(', ')}, 原因: ${body.reason}`
    )

    return jsonResponse({
      success: true,
      isFounder: body.isFounder ?? !!user.is_founder,
      isExpert: body.isExpert ?? !!user.is_expert,
    })
  } catch (error) {
    console.error('Error updating special status:', error)
    return jsonResponse({ error: 'Failed to update special status' }, 500)
  }
}

/**
 * 设置用户配额覆盖
 */
export async function setUserQuotaOverride(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as {
      monthlyTokenLimit?: number
      dailyConversationLimit?: number
      customMultiplier?: number
      reason: string
      expiresAt?: string
    }

    // 获取当前用户
    const user = await env.DB.prepare(
      'SELECT id FROM users WHERE id = ?'
    ).bind(userId).first()

    if (!user) {
      return jsonResponse({ error: 'User not found' }, 404)
    }

    const now = Math.floor(Date.now() / 1000)
    const expiresAt = body.expiresAt 
      ? Math.floor(new Date(body.expiresAt).getTime() / 1000) 
      : null

    // 删除旧的覆盖设置
    await env.DB.prepare(
      'DELETE FROM user_quota_overrides WHERE user_id = ?'
    ).bind(userId).run()

    // 插入新的覆盖设置
    await env.DB.prepare(
      `INSERT INTO user_quota_overrides 
        (user_id, monthly_token_limit, daily_conversation_limit, custom_multiplier, reason, admin_id, expires_at, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      userId,
      body.monthlyTokenLimit || null,
      body.dailyConversationLimit || null,
      body.customMultiplier || null,
      body.reason,
      adminContext.email,
      expiresAt,
      now
    ).run()

    // 记录操作日志
    await logAdminAction(
      env,
      adminContext,
      'SET_QUOTA_OVERRIDE',
      'user',
      userId,
      `配额覆盖: Token=${body.monthlyTokenLimit}, 对话=${body.dailyConversationLimit}, 倍数=${body.customMultiplier}`
    )

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error setting quota override:', error)
    return jsonResponse({ error: 'Failed to set quota override' }, 500)
  }
}

/**
 * 清除用户配额覆盖
 */
export async function clearUserQuotaOverride(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    await env.DB.prepare(
      'DELETE FROM user_quota_overrides WHERE user_id = ?'
    ).bind(userId).run()

    await logAdminAction(
      env,
      adminContext,
      'CLEAR_QUOTA_OVERRIDE',
      'user',
      userId,
      '清除配额覆盖'
    )

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error clearing quota override:', error)
    return jsonResponse({ error: 'Failed to clear quota override' }, 500)
  }
}

// ============================================
// 用户封禁管理
// ============================================

/**
 * 封禁用户
 */
export async function banUser(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as { reason: string }
    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(
      'UPDATE users SET is_banned = 1, ban_reason = ?, last_active_at = ? WHERE id = ?'
    ).bind(body.reason, now, userId).run()

    await logAdminAction(
      env,
      adminContext,
      'BAN_USER',
      'user',
      userId,
      `封禁原因: ${body.reason}`
    )

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error banning user:', error)
    return jsonResponse({ error: 'Failed to ban user' }, 500)
  }
}

/**
 * 解封用户
 */
export async function unbanUser(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(
      'UPDATE users SET is_banned = 0, ban_reason = NULL, last_active_at = ? WHERE id = ?'
    ).bind(now, userId).run()

    await logAdminAction(
      env,
      adminContext,
      'UNBAN_USER',
      'user',
      userId,
      '解封用户'
    )

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error unbanning user:', error)
    return jsonResponse({ error: 'Failed to unban user' }, 500)
  }
}

// ============================================
// 用户操作历史
// ============================================

/**
 * 获取用户操作历史
 */
export async function getUserHistory(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  const url = new URL(request.url)
  const type = url.searchParams.get('type') || 'all' // all, ai, memo, subscription, staking, login
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')

  try {
    const history: any[] = []
    const offset = (page - 1) * pageSize

    // AI 使用记录
    if (type === 'all' || type === 'ai') {
      const aiLogs = await env.DB.prepare(
        `SELECT 'ai' as type, function_type as subtype, total_tokens as amount, 
          cost_usd as cost, model, created_at
         FROM ai_usage_logs WHERE user_id = ?
         ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(userId, type === 'ai' ? pageSize : 10, type === 'ai' ? offset : 0).all()
      history.push(...(aiLogs.results || []).map((r: any) => ({
        ...r,
        typeLabel: 'AI 调用',
        description: `${r.subtype}: ${r.amount} tokens, $${r.cost?.toFixed(4) || 0}`,
      })))
    }

    // 积分变动记录
    if (type === 'all' || type === 'memo') {
      const memoLogs = await env.DB.prepare(
        `SELECT 'memo' as type, type as subtype, amount, balance_after, 
          source, description, created_at
         FROM memo_transactions WHERE user_id = ?
         ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(userId, type === 'memo' ? pageSize : 10, type === 'memo' ? offset : 0).all()
      history.push(...(memoLogs.results || []).map((r: any) => ({
        ...r,
        typeLabel: '积分变动',
        description: `${r.subtype}: ${r.amount > 0 ? '+' : ''}${r.amount} MEMO (${r.source || ''})`
      })))
    }

    // 订阅记录
    if (type === 'all' || type === 'subscription') {
      const subLogs = await env.DB.prepare(
        `SELECT 'subscription' as record_type, type as subtype, amount, 
          token, status, signature as tx_signature, created_at
         FROM payment_transactions WHERE user_id = ? AND type = 'subscription'
         ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(userId, type === 'subscription' ? pageSize : 10, type === 'subscription' ? offset : 0).all()
      history.push(...(subLogs.results || []).map((r: any) => ({
        ...r,
        type: 'subscription',
        typeLabel: '订阅支付',
        description: `${r.subtype || '订阅'}: ${r.amount} ${r.token || 'SOL'} (${r.status})`
      })))
    }

    // 质押记录
    if (type === 'all' || type === 'staking') {
      const stakeLogs = await env.DB.prepare(
        `SELECT 'staking' as type, project_id as subtype, amount, 
          status, start_time, unlock_time, created_at
         FROM staking_records WHERE user_id = ?
         ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(userId, type === 'staking' ? pageSize : 10, type === 'staking' ? offset : 0).all()
      history.push(...(stakeLogs.results || []).map((r: any) => ({
        ...r,
        typeLabel: '质押操作',
        description: `项目 ${r.subtype}: ${r.amount} (${r.status})`
      })))
    }

    // Tier 变更记录
    if (type === 'all' || type === 'tier') {
      const tierLogs = await env.DB.prepare(
        `SELECT 'tier' as type, old_tier, new_tier, change_reason as subtype,
          admin_note as description, created_at
         FROM user_tier_history WHERE user_id = ?
         ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(userId, type === 'tier' ? pageSize : 10, type === 'tier' ? offset : 0).all()
      history.push(...(tierLogs.results || []).map((r: any) => ({
        ...r,
        typeLabel: '等级变更',
        description: `${r.old_tier} → ${r.new_tier} (${r.subtype})`
      })))
    }

    // 按时间排序
    history.sort((a, b) => (b.created_at || 0) - (a.created_at || 0))

    // 分页
    const paginatedHistory = type === 'all' 
      ? history.slice(offset, offset + pageSize) 
      : history

    return jsonResponse({
      history: paginatedHistory,
      total: history.length,
      page,
      pageSize,
    })
  } catch (error) {
    console.error('Error getting user history:', error)
    return jsonResponse({ error: 'Failed to get user history' }, 500)
  }
}

/**
 * 获取用户聊天记录摘要
 */
export async function getUserChatLogs(
  request: Request,
  env: any,
  adminContext: AdminContext,
  userId: string
): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')
  const offset = (page - 1) * pageSize

  try {
    // 获取总数
    const countResult = await env.DB.prepare(
      'SELECT COUNT(*) as count FROM chat_logs WHERE user_id = ?'
    ).bind(userId).first()

    // 获取记录
    const logs = await env.DB.prepare(
      `SELECT id, user_message_preview, tokens_used, created_at
       FROM chat_logs WHERE user_id = ?
       ORDER BY created_at DESC LIMIT ? OFFSET ?`
    ).bind(userId, pageSize, offset).all()

    return jsonResponse({
      logs: logs.results || [],
      total: countResult?.count || 0,
      page,
      pageSize,
    })
  } catch (error) {
    console.error('Error getting chat logs:', error)
    return jsonResponse({ error: 'Failed to get chat logs' }, 500)
  }
}

// ============================================
// 路由处理
// ============================================

/**
 * 处理用户管理路由
 */
export async function handleUsersRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  // GET /admin/users - 用户列表
  if (request.method === 'GET' && path === '/admin/users') {
    return getUsers(request, env, adminContext)
  }

  // GET /admin/users/:id - 用户详情
  const userDetailMatch = path.match(/^\/admin\/users\/([^/]+)$/)
  if (request.method === 'GET' && userDetailMatch) {
    return getUserDetail(request, env, adminContext, userDetailMatch[1])
  }

  // GET /admin/users/:id/history - 用户操作历史
  const historyMatch = path.match(/^\/admin\/users\/([^/]+)\/history$/)
  if (request.method === 'GET' && historyMatch) {
    return getUserHistory(request, env, adminContext, historyMatch[1])
  }

  // GET /admin/users/:id/chats - 用户聊天记录
  const chatsMatch = path.match(/^\/admin\/users\/([^/]+)\/chats$/)
  if (request.method === 'GET' && chatsMatch) {
    return getUserChatLogs(request, env, adminContext, chatsMatch[1])
  }

  // PUT /admin/users/:id/subscription - 更新订阅
  const subscriptionMatch = path.match(/^\/admin\/users\/([^/]+)\/subscription$/)
  if (request.method === 'PUT' && subscriptionMatch) {
    return updateUserSubscription(request, env, adminContext, subscriptionMatch[1])
  }

  // PUT /admin/users/:id/tier - 更新 Tier
  const tierMatch = path.match(/^\/admin\/users\/([^/]+)\/tier$/)
  if (request.method === 'PUT' && tierMatch) {
    return updateUserTier(request, env, adminContext, tierMatch[1])
  }

  // PUT /admin/users/:id/special - 更新特殊权限
  const specialMatch = path.match(/^\/admin\/users\/([^/]+)\/special$/)
  if (request.method === 'PUT' && specialMatch) {
    return updateUserSpecialStatus(request, env, adminContext, specialMatch[1])
  }

  // PUT /admin/users/:id/quota - 设置配额覆盖
  const quotaMatch = path.match(/^\/admin\/users\/([^/]+)\/quota$/)
  if (request.method === 'PUT' && quotaMatch) {
    return setUserQuotaOverride(request, env, adminContext, quotaMatch[1])
  }

  // DELETE /admin/users/:id/quota - 清除配额覆盖
  if (request.method === 'DELETE' && quotaMatch) {
    return clearUserQuotaOverride(request, env, adminContext, quotaMatch[1])
  }

  // POST /admin/users/:id/ban - 封禁用户
  const banMatch = path.match(/^\/admin\/users\/([^/]+)\/ban$/)
  if (request.method === 'POST' && banMatch) {
    return banUser(request, env, adminContext, banMatch[1])
  }

  // POST /admin/users/:id/unban - 解封用户
  const unbanMatch = path.match(/^\/admin\/users\/([^/]+)\/unban$/)
  if (request.method === 'POST' && unbanMatch) {
    return unbanUser(request, env, adminContext, unbanMatch[1])
  }

  return null
}
