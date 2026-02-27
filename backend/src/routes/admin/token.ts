/**
 * 管理后台 - 代币管理 API
 * 包含代币注册、铸造记录、经济追踪
 */

import { AdminContext, logAdminAction } from './middleware'

// ============================================
// 代币注册表管理
// ============================================

/**
 * 获取代币列表
 */
export async function getTokens(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(`
      SELECT t.*, te.total_supply, te.circulating_supply, te.exchange_rate_sol, te.exchange_rate_usdc
      FROM token_registry t
      LEFT JOIN token_economics te ON t.id = te.token_id
      ORDER BY t.is_active DESC, t.symbol
    `).all()

    const tokens = (result.results || []).map((row: any) => ({
      id: row.id,
      symbol: row.symbol,
      name: row.name,
      mintAddress: row.mint_address,
      decimals: row.decimals,
      logoUri: row.logo_uri,
      network: row.network,
      tokenType: row.token_type,
      isPaymentAccepted: !!row.is_payment_accepted,
      isStakable: !!row.is_stakable,
      isAirdropEnabled: !!row.is_airdrop_enabled,
      isActive: !!row.is_active,
      coingeckoId: row.coingecko_id,
      priceUsd: row.price_usd,
      priceUpdatedAt: row.price_updated_at 
        ? new Date(row.price_updated_at * 1000).toISOString() 
        : null,
      // 经济数据
      totalSupply: row.total_supply,
      circulatingSupply: row.circulating_supply,
      exchangeRateSol: row.exchange_rate_sol,
      exchangeRateUsdc: row.exchange_rate_usdc,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ tokens })
  } catch (error) {
    console.error('Error getting tokens:', error)
    return jsonResponse({ error: 'Failed to get tokens' }, 500)
  }
}

/**
 * 添加代币
 */
export async function addToken(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      symbol: string
      name: string
      mintAddress: string
      decimals: number
      logoUri?: string
      network?: string
      tokenType?: 'native' | 'spl' | 'nft'
      isPaymentAccepted?: boolean
      isStakable?: boolean
      isAirdropEnabled?: boolean
      coingeckoId?: string
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    const result = await env.DB.prepare(`
      INSERT INTO token_registry 
        (symbol, name, mint_address, decimals, logo_uri, network, token_type,
         is_payment_accepted, is_stakable, is_airdrop_enabled, coingecko_id, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      body.symbol,
      body.name,
      body.mintAddress,
      body.decimals,
      body.logoUri || null,
      body.network || 'mainnet',
      body.tokenType || 'spl',
      body.isPaymentAccepted ? 1 : 0,
      body.isStakable ? 1 : 0,
      body.isAirdropEnabled ? 1 : 0,
      body.coingeckoId || null,
      now,
      now
    ).run()

    // 初始化经济数据
    const tokenId = result.meta?.last_row_id
    if (tokenId) {
      await env.DB.prepare(`
        INSERT INTO token_economics (token_id, updated_at)
        VALUES (?, ?)
      `).bind(tokenId, now).run()
    }

    await logAdminAction(env, adminContext, 'ADD_TOKEN', 'token', body.mintAddress, 
      `添加代币: ${body.symbol} (${body.name})`)

    return jsonResponse({ success: true, id: tokenId })
  } catch (error: any) {
    if (error.message?.includes('UNIQUE constraint')) {
      return jsonResponse({ error: 'Mint address already exists' }, 400)
    }
    console.error('Error adding token:', error)
    return jsonResponse({ error: 'Failed to add token' }, 500)
  }
}

/**
 * 获取代币详情
 */
export async function getTokenDetail(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const token = await env.DB.prepare(`
      SELECT t.*, te.*
      FROM token_registry t
      LEFT JOIN token_economics te ON t.id = te.token_id
      WHERE t.id = ?
    `).bind(id).first()

    if (!token) {
      return jsonResponse({ error: 'Token not found' }, 404)
    }

    // 获取铸造统计
    const mintStats = await env.DB.prepare(`
      SELECT 
        COUNT(*) as total_mints,
        SUM(CASE WHEN status = 'confirmed' THEN CAST(amount AS REAL) ELSE 0 END) as total_minted,
        SUM(CASE WHEN status = 'pending' THEN CAST(amount AS REAL) ELSE 0 END) as pending_amount
      FROM token_mint_records
      WHERE token_id = ?
    `).bind(id).first()

    return jsonResponse({
      id: token.id,
      symbol: token.symbol,
      name: token.name,
      mintAddress: token.mint_address,
      decimals: token.decimals,
      logoUri: token.logo_uri,
      network: token.network,
      tokenType: token.token_type,
      isPaymentAccepted: !!token.is_payment_accepted,
      isStakable: !!token.is_stakable,
      isAirdropEnabled: !!token.is_airdrop_enabled,
      isActive: !!token.is_active,
      // 经济数据
      totalSupply: token.total_supply,
      circulatingSupply: token.circulating_supply,
      lockedSupply: token.locked_supply,
      burnedSupply: token.burned_supply,
      teamAllocation: token.team_allocation,
      communityAllocation: token.community_allocation,
      treasuryBalance: token.treasury_balance,
      exchangeRateSol: token.exchange_rate_sol,
      exchangeRateUsdc: token.exchange_rate_usdc,
      // 铸造统计
      mintStats: {
        totalMints: mintStats?.total_mints || 0,
        totalMinted: mintStats?.total_minted || 0,
        pendingAmount: mintStats?.pending_amount || 0,
      }
    })
  } catch (error) {
    console.error('Error getting token detail:', error)
    return jsonResponse({ error: 'Failed to get token detail' }, 500)
  }
}

/**
 * 更新代币
 */
export async function updateToken(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      name?: string
      logoUri?: string
      isPaymentAccepted?: boolean
      isStakable?: boolean
      isAirdropEnabled?: boolean
      isActive?: boolean
      coingeckoId?: string
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
    if (body.logoUri !== undefined) {
      updates.push('logo_uri = ?')
      params.push(body.logoUri)
    }
    if (body.isPaymentAccepted !== undefined) {
      updates.push('is_payment_accepted = ?')
      params.push(body.isPaymentAccepted ? 1 : 0)
    }
    if (body.isStakable !== undefined) {
      updates.push('is_stakable = ?')
      params.push(body.isStakable ? 1 : 0)
    }
    if (body.isAirdropEnabled !== undefined) {
      updates.push('is_airdrop_enabled = ?')
      params.push(body.isAirdropEnabled ? 1 : 0)
    }
    if (body.isActive !== undefined) {
      updates.push('is_active = ?')
      params.push(body.isActive ? 1 : 0)
    }
    if (body.coingeckoId !== undefined) {
      updates.push('coingecko_id = ?')
      params.push(body.coingeckoId)
    }

    updates.push('updated_at = ?')
    params.push(now)
    params.push(id)

    await env.DB.prepare(
      `UPDATE token_registry SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    await logAdminAction(env, adminContext, 'UPDATE_TOKEN', 'token', id, 
      `更新代币配置`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating token:', error)
    return jsonResponse({ error: 'Failed to update token' }, 500)
  }
}

/**
 * 删除代币
 */
export async function deleteToken(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const existing = await env.DB.prepare(
      'SELECT symbol, name FROM token_registry WHERE id = ?'
    ).bind(id).first()

    // 删除经济数据
    await env.DB.prepare('DELETE FROM token_economics WHERE token_id = ?').bind(id).run()
    // 删除代币
    await env.DB.prepare('DELETE FROM token_registry WHERE id = ?').bind(id).run()

    await logAdminAction(env, adminContext, 'DELETE_TOKEN', 'token', id, 
      `删除代币: ${existing?.symbol || id}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error deleting token:', error)
    return jsonResponse({ error: 'Failed to delete token' }, 500)
  }
}

// ============================================
// 代币经济管理
// ============================================

/**
 * 更新代币经济数据
 */
export async function updateTokenEconomics(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      totalSupply?: string
      circulatingSupply?: string
      lockedSupply?: string
      burnedSupply?: string
      teamAllocation?: string
      communityAllocation?: string
      treasuryBalance?: string
      exchangeRateSol?: number
      exchangeRateUsdc?: number
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const updates: string[] = []
    const params: any[] = []

    if (body.totalSupply !== undefined) {
      updates.push('total_supply = ?')
      params.push(body.totalSupply)
    }
    if (body.circulatingSupply !== undefined) {
      updates.push('circulating_supply = ?')
      params.push(body.circulatingSupply)
    }
    if (body.lockedSupply !== undefined) {
      updates.push('locked_supply = ?')
      params.push(body.lockedSupply)
    }
    if (body.burnedSupply !== undefined) {
      updates.push('burned_supply = ?')
      params.push(body.burnedSupply)
    }
    if (body.teamAllocation !== undefined) {
      updates.push('team_allocation = ?')
      params.push(body.teamAllocation)
    }
    if (body.communityAllocation !== undefined) {
      updates.push('community_allocation = ?')
      params.push(body.communityAllocation)
    }
    if (body.treasuryBalance !== undefined) {
      updates.push('treasury_balance = ?')
      params.push(body.treasuryBalance)
    }
    if (body.exchangeRateSol !== undefined) {
      updates.push('exchange_rate_sol = ?')
      params.push(body.exchangeRateSol)
      updates.push('last_rate_update = ?')
      params.push(now)
    }
    if (body.exchangeRateUsdc !== undefined) {
      updates.push('exchange_rate_usdc = ?')
      params.push(body.exchangeRateUsdc)
      updates.push('last_rate_update = ?')
      params.push(now)
    }

    updates.push('updated_at = ?')
    params.push(now)
    params.push(id)

    await env.DB.prepare(
      `UPDATE token_economics SET ${updates.join(', ')} WHERE token_id = ?`
    ).bind(...params).run()

    await logAdminAction(env, adminContext, 'UPDATE_TOKEN_ECONOMICS', 'token', id, 
      `更新代币经济数据`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating token economics:', error)
    return jsonResponse({ error: 'Failed to update token economics' }, 500)
  }
}

// ============================================
// 代币铸造管理
// ============================================

/**
 * 创建铸造请求
 */
export async function createMintRequest(
  request: Request,
  env: any,
  adminContext: AdminContext,
  tokenId: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      recipientAddress: string
      amount: number
      purpose: 'airdrop' | 'reward' | 'team' | 'marketing' | 'other'
      reason: string
    }

    if (!body.reason || body.reason.length < 10) {
      return jsonResponse({ error: 'Reason must be at least 10 characters' }, 400)
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    // 获取代币信息
    const token = await env.DB.prepare(
      'SELECT mint_address, decimals FROM token_registry WHERE id = ?'
    ).bind(tokenId).first()

    if (!token) {
      return jsonResponse({ error: 'Token not found' }, 404)
    }

    const now = Math.floor(Date.now() / 1000)
    const rawAmount = String(body.amount * Math.pow(10, token.decimals))

    const result = await env.DB.prepare(`
      INSERT INTO token_mint_records 
        (token_id, mint_address, recipient_address, amount, raw_amount, purpose, admin_id, admin_reason, status, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
    `).bind(
      tokenId,
      token.mint_address,
      body.recipientAddress,
      body.amount,
      rawAmount,
      body.purpose,
      adminContext.email,
      body.reason,
      now
    ).run()

    await logAdminAction(env, adminContext, 'CREATE_MINT_REQUEST', 'token', tokenId, 
      `创建铸造请求: ${body.amount} 代币给 ${body.recipientAddress}`)

    return jsonResponse({ success: true, id: result.meta?.last_row_id })
  } catch (error) {
    console.error('Error creating mint request:', error)
    return jsonResponse({ error: 'Failed to create mint request' }, 500)
  }
}

/**
 * 获取铸造记录
 */
export async function getMintRecords(
  request: Request,
  env: any,
  adminContext: AdminContext,
  tokenId: string
): Promise<Response> {
  const url = new URL(request.url)
  const status = url.searchParams.get('status')
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let whereClause = 'token_id = ?'
    const params: any[] = [tokenId]

    if (status) {
      whereClause += ' AND status = ?'
      params.push(status)
    }

    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM token_mint_records WHERE ${whereClause}`
    ).bind(...params).first()
    const total = countResult?.count || 0

    const offset = (page - 1) * pageSize
    const result = await env.DB.prepare(`
      SELECT * FROM token_mint_records
      WHERE ${whereClause}
      ORDER BY created_at DESC
      LIMIT ? OFFSET ?
    `).bind(...params, pageSize, offset).all()

    const records = (result.results || []).map((row: any) => ({
      id: row.id,
      recipientAddress: row.recipient_address,
      amount: row.amount,
      rawAmount: row.raw_amount,
      txSignature: row.tx_signature,
      status: row.status,
      purpose: row.purpose,
      adminId: row.admin_id,
      adminReason: row.admin_reason,
      approvedBy: row.approved_by,
      approvedAt: row.approved_at ? new Date(row.approved_at * 1000).toISOString() : null,
      confirmedAt: row.confirmed_at ? new Date(row.confirmed_at * 1000).toISOString() : null,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ records, total, page, pageSize })
  } catch (error) {
    console.error('Error getting mint records:', error)
    return jsonResponse({ error: 'Failed to get mint records' }, 500)
  }
}

/**
 * 审批铸造请求
 */
export async function approveMintRequest(
  request: Request,
  env: any,
  adminContext: AdminContext,
  mintId: string
): Promise<Response> {
  try {
    const body = await request.json() as { approved: boolean; note?: string }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    if (body.approved) {
      await env.DB.prepare(`
        UPDATE token_mint_records 
        SET status = 'approved', approved_by = ?, approved_at = ?
        WHERE id = ? AND status = 'pending'
      `).bind(adminContext.email, now, mintId).run()
    } else {
      await env.DB.prepare(`
        UPDATE token_mint_records 
        SET status = 'rejected', approved_by = ?, approved_at = ?
        WHERE id = ? AND status = 'pending'
      `).bind(adminContext.email, now, mintId).run()
    }

    await logAdminAction(env, adminContext, body.approved ? 'APPROVE_MINT' : 'REJECT_MINT', 
      'mint', mintId, body.note || '')

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error approving mint request:', error)
    return jsonResponse({ error: 'Failed to approve mint request' }, 500)
  }
}

/**
 * 确认铸造完成（上链后）
 */
export async function confirmMint(
  request: Request,
  env: any,
  adminContext: AdminContext,
  mintId: string
): Promise<Response> {
  try {
    const body = await request.json() as { txSignature: string }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(`
      UPDATE token_mint_records 
      SET status = 'confirmed', tx_signature = ?, confirmed_at = ?
      WHERE id = ?
    `).bind(body.txSignature, now, mintId).run()

    await logAdminAction(env, adminContext, 'CONFIRM_MINT', 'mint', mintId, 
      `交易签名: ${body.txSignature}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error confirming mint:', error)
    return jsonResponse({ error: 'Failed to confirm mint' }, 500)
  }
}

/**
 * 刷新代币价格（从 CoinGecko）
 */
export async function refreshTokenPrice(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const token = await env.DB.prepare(
      'SELECT coingecko_id FROM token_registry WHERE id = ?'
    ).bind(id).first()

    if (!token || !token.coingecko_id) {
      return jsonResponse({ error: 'Token or CoinGecko ID not found' }, 404)
    }

    // 调用 CoinGecko API
    try {
      const response = await fetch(
        `https://api.coingecko.com/api/v3/simple/price?ids=${token.coingecko_id}&vs_currencies=usd`
      )
      const data = await response.json() as any
      const priceUsd = data[token.coingecko_id]?.usd

      if (priceUsd) {
        const now = Math.floor(Date.now() / 1000)
        await env.DB.prepare(`
          UPDATE token_registry SET price_usd = ?, price_updated_at = ?, updated_at = ?
          WHERE id = ?
        `).bind(priceUsd, now, now, id).run()

        return jsonResponse({ success: true, priceUsd })
      }
    } catch (e) {
      console.error('CoinGecko API error:', e)
    }

    return jsonResponse({ error: 'Failed to fetch price' }, 500)
  } catch (error) {
    console.error('Error refreshing token price:', error)
    return jsonResponse({ error: 'Failed to refresh token price' }, 500)
  }
}

/**
 * 处理代币管理路由
 */
export async function handleTokenRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  // 代币列表
  if (request.method === 'GET' && path === '/admin/tokens') {
    return getTokens(request, env, adminContext)
  }
  
  // 添加代币
  if (request.method === 'POST' && path === '/admin/tokens') {
    return addToken(request, env, adminContext)
  }

  // 代币详情
  const detailMatch = path.match(/^\/admin\/tokens\/(\d+)$/)
  if (detailMatch) {
    if (request.method === 'GET') {
      return getTokenDetail(request, env, adminContext, detailMatch[1])
    }
    if (request.method === 'PUT') {
      return updateToken(request, env, adminContext, detailMatch[1])
    }
    if (request.method === 'DELETE') {
      return deleteToken(request, env, adminContext, detailMatch[1])
    }
  }

  // 代币经济
  const economicsMatch = path.match(/^\/admin\/tokens\/(\d+)\/economics$/)
  if (request.method === 'PUT' && economicsMatch) {
    return updateTokenEconomics(request, env, adminContext, economicsMatch[1])
  }

  // 铸造请求
  const mintMatch = path.match(/^\/admin\/tokens\/(\d+)\/mint$/)
  if (mintMatch) {
    if (request.method === 'POST') {
      return createMintRequest(request, env, adminContext, mintMatch[1])
    }
    if (request.method === 'GET') {
      return getMintRecords(request, env, adminContext, mintMatch[1])
    }
  }

  // 审批铸造
  const approveMatch = path.match(/^\/admin\/tokens\/mint\/(\d+)\/approve$/)
  if (request.method === 'POST' && approveMatch) {
    return approveMintRequest(request, env, adminContext, approveMatch[1])
  }

  // 确认铸造
  const confirmMatch = path.match(/^\/admin\/tokens\/mint\/(\d+)\/confirm$/)
  if (request.method === 'POST' && confirmMatch) {
    return confirmMint(request, env, adminContext, confirmMatch[1])
  }

  // 刷新价格
  const priceMatch = path.match(/^\/admin\/tokens\/(\d+)\/refresh-price$/)
  if (request.method === 'POST' && priceMatch) {
    return refreshTokenPrice(request, env, adminContext, priceMatch[1])
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
