/**
 * 管理后台 - 空投管理 API
 * 包含空投创建、目标筛选、执行追踪
 */

import { AdminContext, logAdminAction } from './middleware'

// ============================================
// 空投活动管理
// ============================================

/**
 * 获取空投列表
 */
export async function getAirdrops(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const status = url.searchParams.get('status')
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let whereClause = '1=1'
    const params: any[] = []

    if (status) {
      whereClause += ' AND a.status = ?'
      params.push(status)
    }

    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM airdrops a WHERE ${whereClause}`
    ).bind(...params).first()
    const total = countResult?.count || 0

    const offset = (page - 1) * pageSize
    const result = await env.DB.prepare(`
      SELECT a.*, t.symbol as token_symbol, t.name as token_name
      FROM airdrops a
      LEFT JOIN token_registry t ON a.token_id = t.id
      WHERE ${whereClause}
      ORDER BY a.created_at DESC
      LIMIT ? OFFSET ?
    `).bind(...params, pageSize, offset).all()

    const airdrops = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      description: row.description,
      tokenId: row.token_id,
      tokenSymbol: row.token_symbol,
      tokenName: row.token_name,
      totalAmount: row.total_amount,
      distributedAmount: row.distributed_amount,
      distributionMode: row.distribution_mode,
      targetCriteria: row.target_criteria ? JSON.parse(row.target_criteria) : null,
      amountFormula: row.amount_formula,
      amountConfig: row.amount_config ? JSON.parse(row.amount_config) : null,
      recipientCount: row.recipient_count,
      claimedCount: row.claimed_count,
      status: row.status,
      startAt: row.start_at ? new Date(row.start_at * 1000).toISOString() : null,
      endAt: row.end_at ? new Date(row.end_at * 1000).toISOString() : null,
      createdBy: row.created_by,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ airdrops, total, page, pageSize })
  } catch (error) {
    console.error('Error getting airdrops:', error)
    return jsonResponse({ error: 'Failed to get airdrops' }, 500)
  }
}

/**
 * 创建空投
 */
export async function createAirdrop(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      name: string
      description?: string
      tokenId: number
      totalAmount: string
      distributionMode: 'push' | 'claim' | 'merkle'
      targetCriteria?: {
        minTier?: number
        maxTier?: number
        minMemoBalance?: number
        minActiveDays?: number
        subscriptionType?: string
        stakingTier?: string
        customSql?: string
      }
      amountFormula: 'fixed' | 'tier' | 'activity' | 'custom'
      amountConfig: any
      startAt: string
      endAt: string
      claimDeadline?: string
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const startAt = Math.floor(new Date(body.startAt).getTime() / 1000)
    const endAt = Math.floor(new Date(body.endAt).getTime() / 1000)
    const claimDeadline = body.claimDeadline 
      ? Math.floor(new Date(body.claimDeadline).getTime() / 1000) 
      : null

    const result = await env.DB.prepare(`
      INSERT INTO airdrops 
        (name, description, token_id, total_amount, distribution_mode, target_criteria,
         amount_formula, amount_config, status, start_at, end_at, claim_deadline, created_by, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?)
    `).bind(
      body.name,
      body.description || null,
      body.tokenId,
      body.totalAmount,
      body.distributionMode,
      body.targetCriteria ? JSON.stringify(body.targetCriteria) : null,
      body.amountFormula,
      JSON.stringify(body.amountConfig),
      startAt,
      endAt,
      claimDeadline,
      adminContext.email,
      now,
      now
    ).run()

    await logAdminAction(env, adminContext, 'CREATE_AIRDROP', 'airdrop', String(result.meta?.last_row_id), 
      `创建空投: ${body.name}`)

    return jsonResponse({ success: true, id: result.meta?.last_row_id })
  } catch (error) {
    console.error('Error creating airdrop:', error)
    return jsonResponse({ error: 'Failed to create airdrop' }, 500)
  }
}

/**
 * 获取空投详情
 */
export async function getAirdropDetail(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const airdrop = await env.DB.prepare(`
      SELECT a.*, t.symbol as token_symbol, t.name as token_name, t.decimals
      FROM airdrops a
      LEFT JOIN token_registry t ON a.token_id = t.id
      WHERE a.id = ?
    `).bind(id).first()

    if (!airdrop) {
      return jsonResponse({ error: 'Airdrop not found' }, 404)
    }

    // 获取受益人统计
    const recipientStats = await env.DB.prepare(`
      SELECT status, COUNT(*) as count, SUM(CAST(calculated_amount AS REAL)) as total_amount
      FROM airdrop_recipients
      WHERE airdrop_id = ?
      GROUP BY status
    `).bind(id).all()

    return jsonResponse({
      id: airdrop.id,
      name: airdrop.name,
      description: airdrop.description,
      tokenId: airdrop.token_id,
      tokenSymbol: airdrop.token_symbol,
      tokenName: airdrop.token_name,
      tokenDecimals: airdrop.decimals,
      totalAmount: airdrop.total_amount,
      distributedAmount: airdrop.distributed_amount,
      distributionMode: airdrop.distribution_mode,
      targetCriteria: airdrop.target_criteria ? JSON.parse(airdrop.target_criteria) : null,
      amountFormula: airdrop.amount_formula,
      amountConfig: airdrop.amount_config ? JSON.parse(airdrop.amount_config) : null,
      merkleRoot: airdrop.merkle_root,
      recipientCount: airdrop.recipient_count,
      claimedCount: airdrop.claimed_count,
      status: airdrop.status,
      startAt: airdrop.start_at ? new Date(airdrop.start_at * 1000).toISOString() : null,
      endAt: airdrop.end_at ? new Date(airdrop.end_at * 1000).toISOString() : null,
      claimDeadline: airdrop.claim_deadline ? new Date(airdrop.claim_deadline * 1000).toISOString() : null,
      createdBy: airdrop.created_by,
      createdAt: new Date(airdrop.created_at * 1000).toISOString(),
      recipientStats: recipientStats.results || [],
    })
  } catch (error) {
    console.error('Error getting airdrop detail:', error)
    return jsonResponse({ error: 'Failed to get airdrop detail' }, 500)
  }
}

/**
 * 更新空投
 */
export async function updateAirdrop(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as Partial<{
      name: string
      description: string
      targetCriteria: any
      amountConfig: any
      startAt: string
      endAt: string
    }>

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    // 检查状态，只有 draft 状态可以修改
    const existing = await env.DB.prepare(
      'SELECT status FROM airdrops WHERE id = ?'
    ).bind(id).first()

    if (!existing) {
      return jsonResponse({ error: 'Airdrop not found' }, 404)
    }

    if (existing.status !== 'draft') {
      return jsonResponse({ error: 'Can only update draft airdrops' }, 400)
    }

    const now = Math.floor(Date.now() / 1000)
    const updates: string[] = []
    const params: any[] = []

    if (body.name !== undefined) {
      updates.push('name = ?')
      params.push(body.name)
    }
    if (body.description !== undefined) {
      updates.push('description = ?')
      params.push(body.description)
    }
    if (body.targetCriteria !== undefined) {
      updates.push('target_criteria = ?')
      params.push(JSON.stringify(body.targetCriteria))
    }
    if (body.amountConfig !== undefined) {
      updates.push('amount_config = ?')
      params.push(JSON.stringify(body.amountConfig))
    }
    if (body.startAt !== undefined) {
      updates.push('start_at = ?')
      params.push(Math.floor(new Date(body.startAt).getTime() / 1000))
    }
    if (body.endAt !== undefined) {
      updates.push('end_at = ?')
      params.push(Math.floor(new Date(body.endAt).getTime() / 1000))
    }

    updates.push('updated_at = ?')
    params.push(now)
    params.push(id)

    await env.DB.prepare(
      `UPDATE airdrops SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    await logAdminAction(env, adminContext, 'UPDATE_AIRDROP', 'airdrop', id, 
      `更新空投配置`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating airdrop:', error)
    return jsonResponse({ error: 'Failed to update airdrop' }, 500)
  }
}

/**
 * 预览空投（计算受益人）
 */
export async function previewAirdrop(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const airdrop = await env.DB.prepare(
      'SELECT * FROM airdrops WHERE id = ?'
    ).bind(id).first()

    if (!airdrop) {
      return jsonResponse({ error: 'Airdrop not found' }, 404)
    }

    const criteria = airdrop.target_criteria ? JSON.parse(airdrop.target_criteria) : {}
    const amountConfig = airdrop.amount_config ? JSON.parse(airdrop.amount_config) : {}

    // 构建查询条件
    let whereClause = '1=1'
    const params: any[] = []

    if (criteria.minTier) {
      whereClause += ' AND current_tier >= ?'
      params.push(criteria.minTier)
    }
    if (criteria.maxTier) {
      whereClause += ' AND current_tier <= ?'
      params.push(criteria.maxTier)
    }
    if (criteria.minMemoBalance) {
      whereClause += ' AND memo_balance >= ?'
      params.push(criteria.minMemoBalance)
    }
    if (criteria.subscriptionType) {
      whereClause += ' AND subscription_type = ?'
      params.push(criteria.subscriptionType)
    }

    // 获取符合条件的用户
    const users = await env.DB.prepare(`
      SELECT id, wallet_address, current_tier, memo_balance
      FROM users
      WHERE ${whereClause} AND is_banned = 0
    `).bind(...params).all()

    // 计算每个用户的空投金额
    const recipients: Array<{
      userId: string
      walletAddress: string
      tier: number
      memoBalance: number
      amount: number
    }> = []

    let totalAmount = 0

    for (const user of (users.results || [])) {
      const u = user as any
      let amount: number

      switch (airdrop.amount_formula) {
        case 'fixed':
          amount = parseFloat(amountConfig.amount || '0')
          break
        case 'tier':
          const multipliers = amountConfig.multipliers || [1, 1.5, 2.5, 4, 6]
          const baseAmount = parseFloat(amountConfig.base_amount || '100')
          amount = baseAmount * (multipliers[u.current_tier - 1] || 1)
          break
        case 'activity':
          // 简化的活跃度计算
          amount = parseFloat(amountConfig.base_amount || '100')
          break
        default:
          amount = 0
      }

      recipients.push({
        userId: u.id,
        walletAddress: u.wallet_address,
        tier: u.current_tier,
        memoBalance: u.memo_balance,
        amount,
      })
      totalAmount += amount
    }

    // 按金额分布统计
    const distribution: Record<string, number> = {}
    for (const r of recipients) {
      const range = r.amount < 100 ? '0-99' 
        : r.amount < 500 ? '100-499'
        : r.amount < 1000 ? '500-999'
        : r.amount < 5000 ? '1000-4999'
        : '5000+'
      distribution[range] = (distribution[range] || 0) + 1
    }

    // 按 Tier 统计
    const byTier: Record<number, { count: number; total: number }> = {}
    for (const r of recipients) {
      if (!byTier[r.tier]) {
        byTier[r.tier] = { count: 0, total: 0 }
      }
      byTier[r.tier].count++
      byTier[r.tier].total += r.amount
    }

    return jsonResponse({
      recipientCount: recipients.length,
      totalAmount,
      avgAmount: recipients.length > 0 ? totalAmount / recipients.length : 0,
      distribution,
      byTier,
      sampleRecipients: recipients.slice(0, 20),
    })
  } catch (error) {
    console.error('Error previewing airdrop:', error)
    return jsonResponse({ error: 'Failed to preview airdrop' }, 500)
  }
}

/**
 * 执行空投（生成受益人名单）
 */
export async function executeAirdrop(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const airdrop = await env.DB.prepare(
      'SELECT * FROM airdrops WHERE id = ?'
    ).bind(id).first()

    if (!airdrop) {
      return jsonResponse({ error: 'Airdrop not found' }, 404)
    }

    if (airdrop.status !== 'draft' && airdrop.status !== 'active') {
      return jsonResponse({ error: 'Airdrop cannot be executed in current status' }, 400)
    }

    const now = Math.floor(Date.now() / 1000)
    const criteria = airdrop.target_criteria ? JSON.parse(airdrop.target_criteria) : {}
    const amountConfig = airdrop.amount_config ? JSON.parse(airdrop.amount_config) : {}

    // 构建查询条件
    let whereClause = '1=1'
    const params: any[] = []

    if (criteria.minTier) {
      whereClause += ' AND current_tier >= ?'
      params.push(criteria.minTier)
    }
    if (criteria.maxTier) {
      whereClause += ' AND current_tier <= ?'
      params.push(criteria.maxTier)
    }
    if (criteria.minMemoBalance) {
      whereClause += ' AND memo_balance >= ?'
      params.push(criteria.minMemoBalance)
    }
    if (criteria.subscriptionType) {
      whereClause += ' AND subscription_type = ?'
      params.push(criteria.subscriptionType)
    }

    // 获取符合条件的用户
    const users = await env.DB.prepare(`
      SELECT id, wallet_address, current_tier, memo_balance
      FROM users
      WHERE ${whereClause} AND is_banned = 0
    `).bind(...params).all()

    let recipientCount = 0
    let totalDistributed = 0

    // 批量插入受益人
    for (const user of (users.results || [])) {
      const u = user as any
      let amount: number

      switch (airdrop.amount_formula) {
        case 'fixed':
          amount = parseFloat(amountConfig.amount || '0')
          break
        case 'tier':
          const multipliers = amountConfig.multipliers || [1, 1.5, 2.5, 4, 6]
          const baseAmount = parseFloat(amountConfig.base_amount || '100')
          amount = baseAmount * (multipliers[u.current_tier - 1] || 1)
          break
        default:
          amount = parseFloat(amountConfig.base_amount || '100')
      }

      await env.DB.prepare(`
        INSERT INTO airdrop_recipients 
          (airdrop_id, user_id, wallet_address, calculated_amount, tier_at_snapshot, memo_balance_snapshot, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, 'pending', ?)
      `).bind(id, u.id, u.wallet_address, String(amount), u.current_tier, u.memo_balance, now).run()

      recipientCount++
      totalDistributed += amount
    }

    // 更新空投状态
    await env.DB.prepare(`
      UPDATE airdrops 
      SET status = 'executing', recipient_count = ?, updated_at = ?
      WHERE id = ?
    `).bind(recipientCount, now, id).run()

    await logAdminAction(env, adminContext, 'EXECUTE_AIRDROP', 'airdrop', id, 
      `开始执行空投: ${recipientCount} 受益人, 总量 ${totalDistributed}`)

    return jsonResponse({ 
      success: true, 
      recipientCount, 
      totalDistributed,
      status: 'executing'
    })
  } catch (error) {
    console.error('Error executing airdrop:', error)
    return jsonResponse({ error: 'Failed to execute airdrop' }, 500)
  }
}

/**
 * 获取空投受益人列表
 */
export async function getAirdropRecipients(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  const url = new URL(request.url)
  const status = url.searchParams.get('status')
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '50')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let whereClause = 'airdrop_id = ?'
    const params: any[] = [id]

    if (status) {
      whereClause += ' AND status = ?'
      params.push(status)
    }

    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM airdrop_recipients WHERE ${whereClause}`
    ).bind(...params).first()
    const total = countResult?.count || 0

    const offset = (page - 1) * pageSize
    const result = await env.DB.prepare(`
      SELECT * FROM airdrop_recipients
      WHERE ${whereClause}
      ORDER BY calculated_amount DESC
      LIMIT ? OFFSET ?
    `).bind(...params, pageSize, offset).all()

    const recipients = (result.results || []).map((row: any) => ({
      id: row.id,
      userId: row.user_id,
      walletAddress: row.wallet_address,
      calculatedAmount: row.calculated_amount,
      tierAtSnapshot: row.tier_at_snapshot,
      memoBalanceSnapshot: row.memo_balance_snapshot,
      status: row.status,
      txSignature: row.tx_signature,
      distributedAt: row.distributed_at ? new Date(row.distributed_at * 1000).toISOString() : null,
      claimedAt: row.claimed_at ? new Date(row.claimed_at * 1000).toISOString() : null,
      errorMessage: row.error_message,
      retryCount: row.retry_count,
    }))

    return jsonResponse({ recipients, total, page, pageSize })
  } catch (error) {
    console.error('Error getting airdrop recipients:', error)
    return jsonResponse({ error: 'Failed to get airdrop recipients' }, 500)
  }
}

/**
 * 暂停空投
 */
export async function pauseAirdrop(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(
      "UPDATE airdrops SET status = 'paused', updated_at = ? WHERE id = ?"
    ).bind(now, id).run()

    await logAdminAction(env, adminContext, 'PAUSE_AIRDROP', 'airdrop', id, '暂停空投')

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error pausing airdrop:', error)
    return jsonResponse({ error: 'Failed to pause airdrop' }, 500)
  }
}

/**
 * 恢复空投
 */
export async function resumeAirdrop(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(
      "UPDATE airdrops SET status = 'executing', updated_at = ? WHERE id = ?"
    ).bind(now, id).run()

    await logAdminAction(env, adminContext, 'RESUME_AIRDROP', 'airdrop', id, '恢复空投')

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error resuming airdrop:', error)
    return jsonResponse({ error: 'Failed to resume airdrop' }, 500)
  }
}

/**
 * 取消空投
 */
export async function cancelAirdrop(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    // 更新空投状态
    await env.DB.prepare(
      "UPDATE airdrops SET status = 'cancelled', updated_at = ? WHERE id = ?"
    ).bind(now, id).run()

    // 取消所有待处理的受益人
    await env.DB.prepare(
      "UPDATE airdrop_recipients SET status = 'cancelled' WHERE airdrop_id = ? AND status = 'pending'"
    ).bind(id).run()

    await logAdminAction(env, adminContext, 'CANCEL_AIRDROP', 'airdrop', id, '取消空投')

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error cancelling airdrop:', error)
    return jsonResponse({ error: 'Failed to cancel airdrop' }, 500)
  }
}

/**
 * 获取空投公式模板
 */
export async function getAirdropFormulas(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(
      'SELECT * FROM airdrop_formulas ORDER BY is_default DESC, name'
    ).all()

    const formulas = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      formulaType: row.formula_type,
      config: JSON.parse(row.config),
      description: row.description,
      isDefault: !!row.is_default,
    }))

    return jsonResponse({ formulas })
  } catch (error) {
    console.error('Error getting airdrop formulas:', error)
    return jsonResponse({ error: 'Failed to get airdrop formulas' }, 500)
  }
}

/**
 * 创建空投公式模板
 */
export async function createAirdropFormula(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      name: string
      formulaType: string
      config: any
      description?: string
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    const result = await env.DB.prepare(`
      INSERT INTO airdrop_formulas (name, formula_type, config, description, created_by, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).bind(
      body.name,
      body.formulaType,
      JSON.stringify(body.config),
      body.description || null,
      adminContext.email,
      now
    ).run()

    await logAdminAction(env, adminContext, 'CREATE_AIRDROP_FORMULA', 'airdrop_formula', 
      String(result.meta?.last_row_id), `创建空投公式: ${body.name}`)

    return jsonResponse({ success: true, id: result.meta?.last_row_id })
  } catch (error) {
    console.error('Error creating airdrop formula:', error)
    return jsonResponse({ error: 'Failed to create airdrop formula' }, 500)
  }
}

/**
 * 处理空投管理路由
 */
export async function handleAirdropRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  // 空投列表
  if (request.method === 'GET' && path === '/admin/airdrops') {
    return getAirdrops(request, env, adminContext)
  }
  
  // 创建空投
  if (request.method === 'POST' && path === '/admin/airdrops') {
    return createAirdrop(request, env, adminContext)
  }

  // 空投详情
  const detailMatch = path.match(/^\/admin\/airdrops\/(\d+)$/)
  if (detailMatch) {
    if (request.method === 'GET') {
      return getAirdropDetail(request, env, adminContext, detailMatch[1])
    }
    if (request.method === 'PUT') {
      return updateAirdrop(request, env, adminContext, detailMatch[1])
    }
  }

  // 预览空投
  const previewMatch = path.match(/^\/admin\/airdrops\/(\d+)\/preview$/)
  if (request.method === 'POST' && previewMatch) {
    return previewAirdrop(request, env, adminContext, previewMatch[1])
  }

  // 执行空投
  const executeMatch = path.match(/^\/admin\/airdrops\/(\d+)\/execute$/)
  if (request.method === 'POST' && executeMatch) {
    return executeAirdrop(request, env, adminContext, executeMatch[1])
  }

  // 受益人列表
  const recipientsMatch = path.match(/^\/admin\/airdrops\/(\d+)\/recipients$/)
  if (request.method === 'GET' && recipientsMatch) {
    return getAirdropRecipients(request, env, adminContext, recipientsMatch[1])
  }

  // 暂停空投
  const pauseMatch = path.match(/^\/admin\/airdrops\/(\d+)\/pause$/)
  if (request.method === 'POST' && pauseMatch) {
    return pauseAirdrop(request, env, adminContext, pauseMatch[1])
  }

  // 恢复空投
  const resumeMatch = path.match(/^\/admin\/airdrops\/(\d+)\/resume$/)
  if (request.method === 'POST' && resumeMatch) {
    return resumeAirdrop(request, env, adminContext, resumeMatch[1])
  }

  // 取消空投
  const cancelMatch = path.match(/^\/admin\/airdrops\/(\d+)\/cancel$/)
  if (request.method === 'POST' && cancelMatch) {
    return cancelAirdrop(request, env, adminContext, cancelMatch[1])
  }

  // 空投公式
  if (request.method === 'GET' && path === '/admin/airdrops/formulas') {
    return getAirdropFormulas(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/airdrops/formulas') {
    return createAirdropFormula(request, env, adminContext)
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
