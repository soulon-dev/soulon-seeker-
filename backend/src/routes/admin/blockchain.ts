/**
 * 管理后台 - 区块链配置管理 API
 * 包含钱包地址、RPC节点、智能合约管理
 */

import { AdminContext, logAdminAction } from './middleware'
import { signConfigValue } from '../../utils/config-signature'
import { getSolanaRpcUrl } from '../../utils/solana-rpc'
import {
  assertSolanaPubkeyBase58,
  getSubscriptionProgramStatus,
  initializeSubscriptionProgramConfigOnChain,
  updateSubscriptionProgramConfigOnChain,
  type SubscriptionConfigField,
} from '../../services/subscription-chain'

// ============================================
// 钱包地址管理
// ============================================

/**
 * 获取钱包地址列表
 */
export async function getWalletAddresses(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const type = url.searchParams.get('type')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let whereClause = '1=1'
    const params: any[] = []

    if (type) {
      whereClause += ' AND type = ?'
      params.push(type)
    }

    const result = await env.DB.prepare(`
      SELECT * FROM wallet_addresses 
      WHERE ${whereClause} 
      ORDER BY type, name
    `).bind(...params).all()

    const wallets = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      address: row.address,
      type: row.type,
      network: row.network,
      description: row.description,
      isActive: !!row.is_active,
      balanceSol: row.balance_sol,
      balanceUsdc: row.balance_usdc,
      lastBalanceCheck: row.last_balance_check 
        ? new Date(row.last_balance_check * 1000).toISOString() 
        : null,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ wallets })
  } catch (error) {
    console.error('Error getting wallet addresses:', error)
    return jsonResponse({ error: 'Failed to get wallet addresses' }, 500)
  }
}

/**
 * 添加钱包地址
 */
export async function addWalletAddress(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      name: string
      address: string
      type: 'admin' | 'executor' | 'recipient' | 'staking_pool' | 'treasury' | 'fee' | 'team'
      network?: string
      description?: string
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const exists = await env.DB.prepare(
      'SELECT id FROM wallet_addresses WHERE address = ? LIMIT 1'
    ).bind(body.address).first()
    if (exists) {
      return jsonResponse({ error: 'Address already exists' }, 400)
    }

    // 验证地址格式（严格 Base58->32 bytes）
    try {
      assertSolanaPubkeyBase58(body.address, 'wallet address')
    } catch (e: any) {
      return jsonResponse({ error: e?.message || 'Invalid Solana address format' }, 400)
    }

    const now = Math.floor(Date.now() / 1000)
    const network = body.network || 'mainnet'
    const isSingleton = body.type === 'admin' || body.type === 'executor' || body.type === 'recipient'
    const inserted = await env.DB.prepare(`
      INSERT INTO wallet_addresses (name, address, type, network, description, is_active, created_by, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      body.name,
      body.address,
      body.type,
      network,
      body.description || null,
      isSingleton ? 0 : 1,
      adminContext.email,
      now,
      now
    ).run()

    const insertedId = inserted.meta?.last_row_id
    let onchainSignature: string | undefined

    if (body.type === 'admin' || body.type === 'executor' || body.type === 'recipient') {
      const onchain = await tryUpdateSubscriptionConfigOnChain(env, body.type, body.address, network)
      // 如果不是 "recipient" 类型，我们强制要求链上更新成功才允许添加
      // 对于 "recipient"（收款钱包），允许链上更新失败（可能因为缺少私钥/权限），只在数据库记录
      if (!onchain.success && body.type !== 'recipient') {
        if (insertedId) {
          await env.DB.prepare('DELETE FROM wallet_addresses WHERE id = ?').bind(insertedId).run()
        }
        await logAdminAction(env, adminContext, 'UPDATE_SUBSCRIPTION_CONFIG_FAILED', 'wallet', body.address, onchain.error || 'onchain update failed')
        return jsonResponse({ error: onchain.error || 'Failed to update on-chain config' }, 502)
      }

      if (onchain.success) {
        onchainSignature = onchain.signature
        await logAdminAction(env, adminContext, 'UPDATE_SUBSCRIPTION_CONFIG_OK', 'wallet', body.address, `field=${body.type} sig=${onchainSignature}`)
      } else {
        // 记录警告日志
        await logAdminAction(env, adminContext, 'UPDATE_SUBSCRIPTION_CONFIG_SKIPPED', 'wallet', body.address, `field=${body.type} error=${onchain.error}`)
      }

      await env.DB.prepare(
        'UPDATE wallet_addresses SET is_active = 0, updated_at = ? WHERE type = ? AND network = ? AND is_active = 1 AND id != ?'
      ).bind(now, body.type, network, insertedId).run()

      await env.DB.prepare(
        'UPDATE wallet_addresses SET is_active = 1, updated_at = ? WHERE id = ?'
      ).bind(now, insertedId).run()
    }

    await logAdminAction(env, adminContext, 'ADD_WALLET', 'wallet', body.address, 
      `添加钱包: ${body.name} (${body.type})`)

    if (body.type === 'recipient') {
      await syncRecipientWalletConfig(env, adminContext, body.address, now)
    } else if (body.type === 'admin') {
      await syncPlainConfig(env, adminContext, 'blockchain.subscription_admin', body.address, now)
    } else if (body.type === 'executor') {
      await syncPlainConfig(env, adminContext, 'blockchain.subscription_executor', body.address, now)
    }

    return jsonResponse({ success: true, id: insertedId, onchainSignature })
  } catch (error: any) {
    if (error.message?.includes('UNIQUE constraint')) {
      return jsonResponse({ error: 'Address already exists' }, 400)
    }
    console.error('Error adding wallet address:', error)
    return jsonResponse({ error: 'Failed to add wallet address' }, 500)
  }
}

// ============================================
// Subscription Program 管理（状态查询/初始化）
// ============================================

export async function getSubscriptionConfigStatus(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const url = new URL(request.url)
    const network = (url.searchParams.get('network') || 'devnet').toLowerCase()

    const rpcUrl = await resolveRpcUrl(env, network)
    const programId = await resolveSubscriptionProgramId(env, network)

    const status = await getSubscriptionProgramStatus({ rpcUrl, programId })
    return jsonResponse({
      ...status,
      signerConfigured: !!((env.SUBSCRIPTION_ADMIN_PRIVATE_KEY || env.ADMIN_PRIVATE_KEY || '').trim()),
    })
  } catch (error: any) {
    console.error('Error getting subscription config status:', error)
    return jsonResponse({ error: error?.message || 'Failed to get status' }, 500)
  }
}

export async function initializeSubscriptionConfig(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as { network?: string; executor: string; recipient: string }
    const network = (body.network || 'devnet').toLowerCase()

    const rpcUrl = await resolveRpcUrl(env, network)
    const programId = await resolveSubscriptionProgramId(env, network)

    const adminKey = (env.SUBSCRIPTION_ADMIN_PRIVATE_KEY || env.ADMIN_PRIVATE_KEY || '').trim()
    if (!adminKey) {
      return jsonResponse({ error: 'Missing SUBSCRIPTION_ADMIN_PRIVATE_KEY' }, 400)
    }

    try {
      assertSolanaPubkeyBase58(body.executor, 'executor')
      assertSolanaPubkeyBase58(body.recipient, 'recipient')
    } catch (e: any) {
      return jsonResponse({ error: e?.message || 'Invalid address' }, 400)
    }

    const res = await initializeSubscriptionProgramConfigOnChain({
      rpcUrl,
      programId,
      adminSecretKeyBase58: adminKey,
      executor: body.executor,
      recipient: body.recipient,
    })

    if (res.signature) {
      await logAdminAction(env, adminContext, 'SUBSCRIPTION_CONFIG_INITIALIZED', 'subscription_config', res.configPda, `sig=${res.signature}`)
    }

    return jsonResponse({
      success: true,
      signature: res.signature || null,
      configPda: res.configPda,
      confirmed: res.confirmed,
      alreadyInitialized: !res.signature,
    })
  } catch (error: any) {
    console.error('Error initializing subscription config:', error)
    return jsonResponse({ error: error?.message || 'Failed to initialize config' }, 500)
  }
}

async function syncRecipientWalletConfig(env: any, adminContext: AdminContext, address: string, now: number): Promise<void> {
  if (!env.DB) return

  const key = 'blockchain.recipient_wallet'
  const existing = await env.DB.prepare(
    'SELECT config_value FROM app_config WHERE config_key = ?'
  ).bind(key).first()

  if (existing) {
    if (existing.config_value !== address) {
      await env.DB.prepare(
        `INSERT INTO config_change_history (config_key, old_value, new_value, changed_by, change_reason, ip_address)
         VALUES (?, ?, ?, ?, ?, ?)`
      ).bind(
        key,
        existing.config_value,
        address,
        adminContext.email,
        'sync from wallet_addresses',
        adminContext.ip
      ).run()
    }

    await env.DB.prepare(
      'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ?, is_active = 1 WHERE config_key = ?'
    ).bind(address, adminContext.email, now, key).run()
  } else {
    await env.DB.prepare(
      `INSERT INTO app_config
       (config_key, config_value, value_type, category, sub_category, display_name, description, default_value, is_sensitive, requires_restart, is_active, updated_by, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      key,
      address,
      'string',
      'blockchain',
      'wallet',
      '收款钱包地址',
      '客户端支付收款地址（签名下发）',
      '',
      1,
      0,
      1,
      adminContext.email,
      now,
      now
    ).run()
  }

  if (env.KV) {
    const signature = await signConfigValue(env, key, address)
    const storedValue = signature
      ? JSON.stringify({ value: address, signature, timestamp: Date.now() })
      : address
    await env.KV.put(`config:${key}`, storedValue)
  }

  await logAdminAction(env, adminContext, 'SYNC_CONFIG', 'config', key, `sync recipient wallet from wallet_addresses`)
}

async function syncPlainConfig(env: any, adminContext: AdminContext, key: string, value: string, now: number): Promise<void> {
  if (!env.DB) return

  const existing = await env.DB.prepare(
    'SELECT config_value FROM app_config WHERE config_key = ?'
  ).bind(key).first()

  if (existing) {
    if (existing.config_value !== value) {
      await env.DB.prepare(
        `INSERT INTO config_change_history (config_key, old_value, new_value, changed_by, change_reason, ip_address)
         VALUES (?, ?, ?, ?, ?, ?)`
      ).bind(
        key,
        existing.config_value,
        value,
        adminContext.email,
        'sync from wallet_addresses',
        adminContext.ip
      ).run()
    }

    await env.DB.prepare(
      'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ?, is_active = 1 WHERE config_key = ?'
    ).bind(value, adminContext.email, now, key).run()
  } else {
    await env.DB.prepare(
      `INSERT INTO app_config
       (config_key, config_value, value_type, category, sub_category, display_name, description, default_value, is_sensitive, requires_restart, is_active, updated_by, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      key,
      value,
      'string',
      'blockchain',
      'subscription',
      key === 'blockchain.subscription_admin' ? '订阅管理员地址' : '订阅执行器地址',
      '订阅合约 ProgramConfig 地址（用于后台展示/管理）',
      '',
      0,
      0,
      1,
      adminContext.email,
      now,
      now
    ).run()
  }

  if (env.KV) {
    await env.KV.put(`config:${key}`, value)
  }

  await logAdminAction(env, adminContext, 'SYNC_CONFIG', 'config', key, `sync ${key} from wallet_addresses`)
}

async function tryUpdateSubscriptionConfigOnChain(
  env: any,
  field: SubscriptionConfigField,
  newValue: string,
  network: string
): Promise<{ success: boolean; signature?: string; error?: string }> {
  const adminKey = (env.SUBSCRIPTION_ADMIN_PRIVATE_KEY || env.ADMIN_PRIVATE_KEY || '').trim()
  if (!adminKey) {
    return { success: false, error: 'Missing SUBSCRIPTION_ADMIN_PRIVATE_KEY (or ADMIN_PRIVATE_KEY fallback)' }
  }

  const rpcUrl = getSolanaRpcUrl(env, network)
  const programId = (env.SUBSCRIPTION_PROGRAM_ID || '').trim()
  if (!programId) {
    return { success: false, error: 'Missing SUBSCRIPTION_PROGRAM_ID' }
  }

  if (network === 'devnet' && rpcUrl.includes('mainnet')) {
    return { success: false, error: 'Network mismatch: devnet wallet with mainnet RPC' }
  }
  if (network === 'mainnet' && rpcUrl.includes('devnet')) {
    return { success: false, error: 'Network mismatch: mainnet wallet with devnet RPC' }
  }

  try {
    const res = await updateSubscriptionProgramConfigOnChain({
      rpcUrl,
      programId,
      adminSecretKeyBase58: adminKey,
      field,
      newValue,
    })

    const confirmed = field === 'admin'
      ? res.confirmedAdmin
      : field === 'executor'
        ? res.confirmedExecutor
        : res.confirmedRecipient

    if (confirmed !== newValue) {
      return { success: false, error: `On-chain verification failed: expected ${newValue}, got ${confirmed}` }
    }

    return { success: true, signature: res.signature }
  } catch (e: any) {
    return { success: false, error: e?.message || 'On-chain update failed' }
  }
}

/**
 * 更新钱包地址
 */
export async function updateWalletAddress(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      name?: string
      description?: string
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
    if (body.description !== undefined) {
      updates.push('description = ?')
      params.push(body.description)
    }
    if (body.isActive !== undefined) {
      updates.push('is_active = ?')
      params.push(body.isActive ? 1 : 0)
    }

    updates.push('updated_at = ?')
    params.push(now)
    params.push(id)

    await env.DB.prepare(
      `UPDATE wallet_addresses SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    await logAdminAction(env, adminContext, 'UPDATE_WALLET', 'wallet', id, 
      `更新钱包配置`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating wallet address:', error)
    return jsonResponse({ error: 'Failed to update wallet address' }, 500)
  }
}

/**
 * 删除钱包地址
 */
export async function deleteWalletAddress(
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
      'SELECT name, address FROM wallet_addresses WHERE id = ?'
    ).bind(id).first()

    await env.DB.prepare('DELETE FROM wallet_addresses WHERE id = ?').bind(id).run()

    await logAdminAction(env, adminContext, 'DELETE_WALLET', 'wallet', id, 
      `删除钱包: ${existing?.name || id}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error deleting wallet address:', error)
    return jsonResponse({ error: 'Failed to delete wallet address' }, 500)
  }
}

/**
 * 刷新钱包余额
 */
export async function refreshWalletBalance(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const wallet = await env.DB.prepare(
      'SELECT address, network FROM wallet_addresses WHERE id = ?'
    ).bind(id).first()

    if (!wallet) {
      return jsonResponse({ error: 'Wallet not found' }, 404)
    }

    // 调用 Solana RPC 获取余额
    const rpcUrl = getSolanaRpcUrl(env, wallet.network)

    let balanceSol = 0
    let balanceUsdc = 0

    try {
      // 获取 SOL 余额
      const solResponse = await fetch(rpcUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'getBalance',
          params: [wallet.address]
        })
      })
      const solResult = await solResponse.json() as any
      if (solResult.result?.value) {
        balanceSol = solResult.result.value / 1_000_000_000 // lamports to SOL
      }

      // 获取 USDC 余额（Token账户）
      // 这里简化处理，实际需要获取 Token 账户
    } catch (e) {
      console.error('Error fetching balance:', e)
    }

    const now = Math.floor(Date.now() / 1000)
    await env.DB.prepare(`
      UPDATE wallet_addresses 
      SET balance_sol = ?, balance_usdc = ?, last_balance_check = ?, updated_at = ?
      WHERE id = ?
    `).bind(balanceSol, balanceUsdc, now, now, id).run()

    return jsonResponse({ 
      success: true, 
      balanceSol, 
      balanceUsdc,
      checkedAt: new Date().toISOString()
    })
  } catch (error) {
    console.error('Error refreshing wallet balance:', error)
    return jsonResponse({ error: 'Failed to refresh wallet balance' }, 500)
  }
}

// ============================================
// RPC 节点管理
// ============================================

/**
 * 获取 RPC 节点列表
 */
export async function getRpcNodes(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const result = await env.DB.prepare(`
      SELECT * FROM rpc_nodes ORDER BY priority, name
    `).all()

    const nodes = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      url: row.url,
      network: row.network,
      provider: row.provider,
      isPrimary: !!row.is_primary,
      isActive: !!row.is_active,
      priority: row.priority,
      rateLimit: row.rate_limit,
      avgLatency: row.avg_latency_ms,
      successRate: row.success_rate,
      lastHealthCheck: row.last_health_check 
        ? new Date(row.last_health_check * 1000).toISOString() 
        : null,
      lastError: row.last_error,
    }))

    return jsonResponse({ nodes })
  } catch (error) {
    console.error('Error getting RPC nodes:', error)
    return jsonResponse({ error: 'Failed to get RPC nodes' }, 500)
  }
}

/**
 * 添加 RPC 节点
 */
export async function addRpcNode(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      name: string
      url: string
      network?: string
      provider?: string
      isPrimary?: boolean
      priority?: number
      rateLimit?: number
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    // 如果设置为主节点，先取消其他主节点
    if (body.isPrimary) {
      await env.DB.prepare(
        'UPDATE rpc_nodes SET is_primary = 0 WHERE network = ?'
      ).bind(body.network || 'mainnet').run()
    }

    const result = await env.DB.prepare(`
      INSERT INTO rpc_nodes (name, url, network, provider, is_primary, priority, rate_limit, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      body.name,
      body.url,
      body.network || 'mainnet',
      body.provider || 'custom',
      body.isPrimary ? 1 : 0,
      body.priority || 0,
      body.rateLimit || null,
      now,
      now
    ).run()

    await logAdminAction(env, adminContext, 'ADD_RPC_NODE', 'rpc', body.url, 
      `添加 RPC 节点: ${body.name}`)

    return jsonResponse({ success: true, id: result.meta?.last_row_id })
  } catch (error) {
    console.error('Error adding RPC node:', error)
    return jsonResponse({ error: 'Failed to add RPC node' }, 500)
  }
}

/**
 * 更新 RPC 节点
 */
export async function updateRpcNode(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      name?: string
      url?: string
      isPrimary?: boolean
      isActive?: boolean
      priority?: number
      rateLimit?: number
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
    if (body.url !== undefined) {
      updates.push('url = ?')
      params.push(body.url)
    }
    if (body.isPrimary !== undefined) {
      if (body.isPrimary) {
        // 获取网络类型
        const existing = await env.DB.prepare('SELECT network FROM rpc_nodes WHERE id = ?').bind(id).first()
        if (existing) {
          await env.DB.prepare('UPDATE rpc_nodes SET is_primary = 0 WHERE network = ?').bind(existing.network).run()
        }
      }
      updates.push('is_primary = ?')
      params.push(body.isPrimary ? 1 : 0)
    }
    if (body.isActive !== undefined) {
      updates.push('is_active = ?')
      params.push(body.isActive ? 1 : 0)
    }
    if (body.priority !== undefined) {
      updates.push('priority = ?')
      params.push(body.priority)
    }
    if (body.rateLimit !== undefined) {
      updates.push('rate_limit = ?')
      params.push(body.rateLimit)
    }

    updates.push('updated_at = ?')
    params.push(now)
    params.push(id)

    await env.DB.prepare(
      `UPDATE rpc_nodes SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    await logAdminAction(env, adminContext, 'UPDATE_RPC_NODE', 'rpc', id, 
      `更新 RPC 节点配置`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating RPC node:', error)
    return jsonResponse({ error: 'Failed to update RPC node' }, 500)
  }
}

/**
 * 删除 RPC 节点
 */
export async function deleteRpcNode(
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
      'SELECT name FROM rpc_nodes WHERE id = ?'
    ).bind(id).first()

    await env.DB.prepare('DELETE FROM rpc_nodes WHERE id = ?').bind(id).run()

    await logAdminAction(env, adminContext, 'DELETE_RPC_NODE', 'rpc', id, 
      `删除 RPC 节点: ${existing?.name || id}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error deleting RPC node:', error)
    return jsonResponse({ error: 'Failed to delete RPC node' }, 500)
  }
}

/**
 * 测试 RPC 节点
 */
export async function testRpcNode(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const node = await env.DB.prepare(
      'SELECT url FROM rpc_nodes WHERE id = ?'
    ).bind(id).first()

    if (!node) {
      return jsonResponse({ error: 'RPC node not found' }, 404)
    }

    const startTime = Date.now()
    let success = false
    let error: string | null = null

    try {
      const response = await fetch(node.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'getHealth'
        })
      })
      const result = await response.json() as any
      success = result.result === 'ok'
      if (!success) {
        error = result.error?.message || 'Health check failed'
      }
    } catch (e) {
      error = (e as Error).message
    }

    const latency = Date.now() - startTime
    const now = Math.floor(Date.now() / 1000)

    // 更新节点状态
    await env.DB.prepare(`
      UPDATE rpc_nodes 
      SET avg_latency_ms = ?, success_rate = ?, last_health_check = ?, last_error = ?, updated_at = ?
      WHERE id = ?
    `).bind(
      latency,
      success ? 1.0 : 0.0,
      now,
      error,
      now,
      id
    ).run()

    return jsonResponse({ 
      success, 
      latency, 
      error,
      checkedAt: new Date().toISOString()
    })
  } catch (error) {
    console.error('Error testing RPC node:', error)
    return jsonResponse({ error: 'Failed to test RPC node' }, 500)
  }
}

/**
 * 切换主 RPC 节点
 */
export async function switchPrimaryRpc(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as { nodeId: number; network?: string }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)
    const network = body.network || 'mainnet'

    // 取消当前主节点
    await env.DB.prepare(
      'UPDATE rpc_nodes SET is_primary = 0, updated_at = ? WHERE network = ?'
    ).bind(now, network).run()

    // 设置新主节点
    await env.DB.prepare(
      'UPDATE rpc_nodes SET is_primary = 1, updated_at = ? WHERE id = ?'
    ).bind(now, body.nodeId).run()

    // 更新 app_config
    const node = await env.DB.prepare(
      'SELECT url FROM rpc_nodes WHERE id = ?'
    ).bind(body.nodeId).first()

    if (node) {
      await env.DB.prepare(
        "UPDATE app_config SET config_value = ?, updated_at = ? WHERE config_key = 'blockchain.rpc_url'"
      ).bind(node.url, now).run()

      // 同步到 KV
      if (env.KV) {
        await env.KV.put('config:blockchain.rpc_url', node.url)
      }
    }

    await logAdminAction(env, adminContext, 'SWITCH_PRIMARY_RPC', 'rpc', String(body.nodeId), 
      `切换主 RPC 节点`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error switching primary RPC:', error)
    return jsonResponse({ error: 'Failed to switch primary RPC' }, 500)
  }
}

// ============================================
// 智能合约管理
// ============================================

/**
 * 获取智能合约列表
 */
export async function getSmartContracts(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const type = url.searchParams.get('type')

  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    let whereClause = '1=1'
    const params: any[] = []

    if (type) {
      whereClause += ' AND type = ?'
      params.push(type)
    }

    const result = await env.DB.prepare(`
      SELECT * FROM smart_contracts 
      WHERE ${whereClause} 
      ORDER BY type, name
    `).bind(...params).all()

    const contracts = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      programId: row.program_id,
      type: row.type,
      network: row.network,
      version: row.version,
      description: row.description,
      isActive: !!row.is_active,
      isUpgradeable: !!row.is_upgradeable,
      upgradeAuthority: row.upgrade_authority,
      deployedAt: row.deployed_at ? new Date(row.deployed_at * 1000).toISOString() : null,
      createdAt: new Date(row.created_at * 1000).toISOString(),
    }))

    return jsonResponse({ contracts })
  } catch (error) {
    console.error('Error getting smart contracts:', error)
    return jsonResponse({ error: 'Failed to get smart contracts' }, 500)
  }
}

/**
 * 添加智能合约
 */
export async function addSmartContract(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as {
      name: string
      programId: string
      type: 'staking' | 'payment' | 'airdrop' | 'token' | 'nft' | 'other'
      network?: string
      version?: string
      description?: string
      idl?: any
      upgradeAuthority?: string
    }

    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const now = Math.floor(Date.now() / 1000)

    const result = await env.DB.prepare(`
      INSERT INTO smart_contracts 
        (name, program_id, type, network, version, description, idl, upgrade_authority, deployed_by, deployed_at, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      body.name,
      body.programId,
      body.type,
      body.network || 'mainnet',
      body.version || '1.0.0',
      body.description || null,
      body.idl ? JSON.stringify(body.idl) : null,
      body.upgradeAuthority || null,
      adminContext.email,
      now,
      now,
      now
    ).run()

    await logAdminAction(env, adminContext, 'ADD_CONTRACT', 'contract', body.programId, 
      `添加合约: ${body.name} (${body.type})`)

    return jsonResponse({ success: true, id: result.meta?.last_row_id })
  } catch (error: any) {
    if (error.message?.includes('UNIQUE constraint')) {
      return jsonResponse({ error: 'Program ID already exists' }, 400)
    }
    console.error('Error adding smart contract:', error)
    return jsonResponse({ error: 'Failed to add smart contract' }, 500)
  }
}

/**
 * 更新智能合约
 */
export async function updateSmartContract(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as {
      name?: string
      description?: string
      version?: string
      idl?: any
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
    if (body.description !== undefined) {
      updates.push('description = ?')
      params.push(body.description)
    }
    if (body.version !== undefined) {
      updates.push('version = ?')
      params.push(body.version)
    }
    if (body.idl !== undefined) {
      updates.push('idl = ?')
      params.push(JSON.stringify(body.idl))
    }
    if (body.isActive !== undefined) {
      updates.push('is_active = ?')
      params.push(body.isActive ? 1 : 0)
    }

    updates.push('updated_at = ?')
    params.push(now)
    params.push(id)

    await env.DB.prepare(
      `UPDATE smart_contracts SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    await logAdminAction(env, adminContext, 'UPDATE_CONTRACT', 'contract', id, 
      `更新合约配置`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating smart contract:', error)
    return jsonResponse({ error: 'Failed to update smart contract' }, 500)
  }
}

/**
 * 删除智能合约
 */
export async function deleteSmartContract(
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
      'SELECT name, program_id FROM smart_contracts WHERE id = ?'
    ).bind(id).first()

    await env.DB.prepare('DELETE FROM smart_contracts WHERE id = ?').bind(id).run()

    await logAdminAction(env, adminContext, 'DELETE_CONTRACT', 'contract', id, 
      `删除合约: ${existing?.name || id}`)

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error deleting smart contract:', error)
    return jsonResponse({ error: 'Failed to delete smart contract' }, 500)
  }
}

async function resolveRpcUrl(env: any, network: string): Promise<string> {
  const fallback = network === 'devnet' ? 'https://api.devnet.solana.com' : 'https://api.mainnet-beta.solana.com'
  const fromEnv = (env.SOLANA_RPC_URL || '').trim()
  if (fromEnv) return fromEnv
  if (!env.DB) {
    return fallback
  }

  const row = await env.DB.prepare(
    'SELECT url FROM rpc_nodes WHERE network = ? AND is_primary = 1 AND is_active = 1 LIMIT 1'
  ).bind(network === 'mainnet' ? 'mainnet' : 'devnet').first()

  return (row?.url || '').trim() || fallback
}

async function resolveSubscriptionProgramId(env: any, network: string): Promise<string> {
  const fallback = 'GA29CADoAweca3Z3SEHW5Cvyo6Vjh8FYFnPg3Z2rGx9A'
  const fromEnv = (env.SUBSCRIPTION_PROGRAM_ID || '').trim()
  if (fromEnv) return fromEnv

  if (!env.DB) return fallback

  const row = await env.DB.prepare(
    `SELECT program_id FROM smart_contracts
     WHERE network = ? AND is_active = 1
       AND (name = 'subscription' OR name = 'Subscription' OR name = '订阅合约' OR type = 'subscription')
     ORDER BY updated_at DESC
     LIMIT 1`
  ).bind(network === 'mainnet' ? 'mainnet' : 'devnet').first()

  return (row?.program_id || '').trim() || fallback
}

/**
 * 处理区块链配置路由
 */
export async function handleBlockchainRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  // 钱包地址管理
  if (request.method === 'GET' && path === '/admin/blockchain/wallets') {
    return getWalletAddresses(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/blockchain/wallets') {
    return addWalletAddress(request, env, adminContext)
  }

  const walletIdMatch = path.match(/^\/admin\/blockchain\/wallets\/(\d+)$/)
  if (walletIdMatch) {
    if (request.method === 'PUT') {
      return updateWalletAddress(request, env, adminContext, walletIdMatch[1])
    }
    if (request.method === 'DELETE') {
      return deleteWalletAddress(request, env, adminContext, walletIdMatch[1])
    }
  }

  const walletRefreshMatch = path.match(/^\/admin\/blockchain\/wallets\/(\d+)\/refresh$/)
  if (request.method === 'POST' && walletRefreshMatch) {
    return refreshWalletBalance(request, env, adminContext, walletRefreshMatch[1])
  }

  // RPC 节点管理
  if (request.method === 'GET' && path === '/admin/blockchain/rpc') {
    return getRpcNodes(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/blockchain/rpc') {
    return addRpcNode(request, env, adminContext)
  }

  const rpcIdMatch = path.match(/^\/admin\/blockchain\/rpc\/(\d+)$/)
  if (rpcIdMatch) {
    if (request.method === 'PUT') {
      return updateRpcNode(request, env, adminContext, rpcIdMatch[1])
    }
    if (request.method === 'DELETE') {
      return deleteRpcNode(request, env, adminContext, rpcIdMatch[1])
    }
  }

  const rpcTestMatch = path.match(/^\/admin\/blockchain\/rpc\/(\d+)\/test$/)
  if (request.method === 'POST' && rpcTestMatch) {
    return testRpcNode(request, env, adminContext, rpcTestMatch[1])
  }

  if (request.method === 'POST' && path === '/admin/blockchain/rpc/switch') {
    return switchPrimaryRpc(request, env, adminContext)
  }

  // 智能合约管理
  if (request.method === 'GET' && path === '/admin/blockchain/contracts') {
    return getSmartContracts(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/blockchain/contracts') {
    return addSmartContract(request, env, adminContext)
  }

  const contractIdMatch = path.match(/^\/admin\/blockchain\/contracts\/(\d+)$/)
  if (contractIdMatch) {
    if (request.method === 'PUT') {
      return updateSmartContract(request, env, adminContext, contractIdMatch[1])
    }
    if (request.method === 'DELETE') {
      return deleteSmartContract(request, env, adminContext, contractIdMatch[1])
    }
  }

  if (request.method === 'GET' && path === '/admin/blockchain/subscription/status') {
    return getSubscriptionConfigStatus(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/blockchain/subscription/initialize') {
    return initializeSubscriptionConfig(request, env, adminContext)
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
