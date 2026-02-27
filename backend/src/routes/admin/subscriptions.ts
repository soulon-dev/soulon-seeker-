/**
 * 管理后台 - 订阅管理 API
 * 所有数据从 D1 数据库读取
 */

import { AdminContext, logAdminAction } from './middleware'

interface SubscriptionPlan {
  id: string
  name: string
  price: number
  currency: string
  durationDays: number
  features: string[]
  isActive: boolean
  createdAt: string
  updatedAt: string
}

interface SubscriptionRecord {
  id: string
  userId: string
  walletAddress: string
  planId: string
  planName: string
  startDate: string
  endDate: string
  status: 'active' | 'expired' | 'cancelled' | 'refunded'
  amount: number
  transactionId?: string
  createdAt: string
}

/**
 * 获取订阅方案列表
 */
export async function getSubscriptionPlans(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (env.DB) {
      const result = await env.DB.prepare(
        'SELECT * FROM subscription_plans ORDER BY price ASC'
      ).all()
      
      if (result.results) {
        const plans = result.results.map((row: any) => ({
          id: row.id,
          name: row.name,
          price: row.price,
          currency: row.currency,
          durationDays: row.duration_days,
          features: row.features ? JSON.parse(row.features) : [],
          isActive: !!row.is_active,
          createdAt: new Date(row.created_at * 1000).toISOString(),
          updatedAt: new Date(row.updated_at * 1000).toISOString(),
        }))
        return new Response(JSON.stringify(plans), {
          headers: { 'Content-Type': 'application/json' },
        })
      }
    }

    // 无数据库时返回空数组
    return new Response(JSON.stringify([]), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting subscription plans:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get subscription plans' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 创建订阅方案
 */
export async function createSubscriptionPlan(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as Omit<SubscriptionPlan, 'id' | 'createdAt' | 'updatedAt'>
    const planId = `plan_${Date.now()}`
    const now = Math.floor(Date.now() / 1000)

    if (env.DB) {
      await env.DB.prepare(
        `INSERT INTO subscription_plans (id, name, price, currency, duration_days, features, is_active, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
      ).bind(
        planId,
        body.name,
        body.price,
        body.currency || 'SOL',
        body.durationDays,
        JSON.stringify(body.features || []),
        body.isActive ? 1 : 0,
        now,
        now
      ).run()
    }

    await logAdminAction(env, adminContext, 'CREATE_SUBSCRIPTION_PLAN', 'subscription_plan', planId, `创建订阅方案: ${body.name}`)

    return new Response(JSON.stringify({ 
      ...body, 
      id: planId, 
      createdAt: new Date(now * 1000).toISOString(),
      updatedAt: new Date(now * 1000).toISOString()
    }), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error creating subscription plan:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to create subscription plan' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 更新订阅方案
 */
export async function updateSubscriptionPlan(
  request: Request,
  env: any,
  adminContext: AdminContext,
  planId: string
): Promise<Response> {
  try {
    const body = await request.json() as Partial<SubscriptionPlan>
    const now = Math.floor(Date.now() / 1000)

    if (env.DB) {
      const updates: string[] = []
      const values: any[] = []

      if (body.name !== undefined) { updates.push('name = ?'); values.push(body.name) }
      if (body.price !== undefined) { updates.push('price = ?'); values.push(body.price) }
      if (body.durationDays !== undefined) { updates.push('duration_days = ?'); values.push(body.durationDays) }
      if (body.features !== undefined) { updates.push('features = ?'); values.push(JSON.stringify(body.features)) }
      if (body.isActive !== undefined) { updates.push('is_active = ?'); values.push(body.isActive ? 1 : 0) }
      
      if (updates.length > 0) {
        updates.push('updated_at = ?')
        values.push(now)
        values.push(planId)
        await env.DB.prepare(`UPDATE subscription_plans SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run()
      }
    }

    await logAdminAction(env, adminContext, 'UPDATE_SUBSCRIPTION_PLAN', 'subscription_plan', planId, `更新订阅方案`)

    return new Response(JSON.stringify({ ...body, id: planId, updatedAt: new Date(now * 1000).toISOString() }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error updating subscription plan:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to update subscription plan' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 删除订阅方案
 */
export async function deleteSubscriptionPlan(
  request: Request,
  env: any,
  adminContext: AdminContext,
  planId: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM subscription_plans WHERE id = ?').bind(planId).run()
    }

    await logAdminAction(env, adminContext, 'DELETE_SUBSCRIPTION_PLAN', 'subscription_plan', planId, `删除订阅方案`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error deleting subscription plan:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to delete subscription plan' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取订阅记录列表
 */
export async function getSubscriptionRecords(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '10')
  const status = url.searchParams.get('status') || ''

  try {
    if (env.DB) {
      let whereClause = '1=1'
      const params: any[] = []
      
      if (status) {
        whereClause += ' AND sr.status = ?'
        params.push(status)
      }

      // 获取总数
      const countResult = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM subscription_records sr WHERE ${whereClause}`
      ).bind(...params).first()
      const total = countResult?.count || 0

      // 获取记录
      const offset = (page - 1) * pageSize
      const dataParams = [...params, pageSize, offset]
      const result = await env.DB.prepare(
        `SELECT sr.*, sp.name as plan_name 
         FROM subscription_records sr 
         LEFT JOIN subscription_plans sp ON sr.plan_id = sp.id 
         WHERE ${whereClause} 
         ORDER BY sr.created_at DESC 
         LIMIT ? OFFSET ?`
      ).bind(...dataParams).all()

      const records = (result.results || []).map((row: any) => ({
        id: row.id,
        userId: row.user_id,
        walletAddress: row.wallet_address,
        planId: row.plan_id,
        planName: row.plan_name || row.plan_id,
        startDate: new Date(row.start_date * 1000).toISOString().split('T')[0],
        endDate: new Date(row.end_date * 1000).toISOString().split('T')[0],
        status: row.status,
        amount: row.amount,
        transactionId: row.transaction_id,
        createdAt: new Date(row.created_at * 1000).toISOString(),
      }))

      return new Response(JSON.stringify({ records, total, page, pageSize }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    return new Response(JSON.stringify({ records: [], total: 0, page, pageSize }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting subscription records:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get subscription records' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取订阅统计
 */
export async function getSubscriptionStats(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const stats = {
      totalSubscribers: 0,
      activeSubscribers: 0,
      monthlyRevenue: 0,
      yearlyRevenue: 0,
      churnRate: 0,
      planBreakdown: [] as any[],
    }

    if (env.DB) {
      // 总订阅者
      const totalResult = await env.DB.prepare(
        `SELECT COUNT(DISTINCT wallet_address) as count FROM subscription_records`
      ).first()
      stats.totalSubscribers = totalResult?.count || 0

      // 活跃订阅者
      const activeResult = await env.DB.prepare(
        `SELECT COUNT(DISTINCT wallet_address) as count FROM subscription_records WHERE status = 'active'`
      ).first()
      stats.activeSubscribers = activeResult?.count || 0

      // 本月收入
      const monthStart = Math.floor(new Date(new Date().getFullYear(), new Date().getMonth(), 1).getTime() / 1000)
      const revenueResult = await env.DB.prepare(
        `SELECT SUM(amount) as total FROM subscription_records WHERE start_date >= ?`
      ).bind(monthStart).first()
      stats.monthlyRevenue = revenueResult?.total || 0
      stats.yearlyRevenue = stats.monthlyRevenue * 12

      // 方案分布
      const breakdownResult = await env.DB.prepare(
        `SELECT plan_id, COUNT(*) as count, SUM(amount) as revenue 
         FROM subscription_records 
         GROUP BY plan_id`
      ).all()
      
      if (breakdownResult.results) {
        // 获取方案名称
        const plans = await env.DB.prepare('SELECT id, name FROM subscription_plans').all()
        const planMap = new Map((plans.results || []).map((p: any) => [p.id, p.name]))
        
        stats.planBreakdown = breakdownResult.results.map((row: any) => ({
          planId: row.plan_id,
          planName: planMap.get(row.plan_id) || row.plan_id,
          count: row.count,
          revenue: row.revenue || 0,
        }))
      }
    }

    return new Response(JSON.stringify(stats), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting subscription stats:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get subscription stats' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

// ============== 自动续费相关 ==============

interface AutoRenewSubscription {
  id: string
  walletAddress: string
  planType: number  // 1=月费, 2=季度, 3=年费
  amountUsdc: number
  periodSeconds: number
  nextPaymentAt: number
  isActive: boolean
  tokenAccountPda: string  // 链上 PDA 地址
  createdAt: number
  updatedAt: number
}

type AutoRenewSwitchState = {
  fromPlanType: number
  toPlanType: number
  toAmountUsdc: number
  toPeriodSeconds: number
  effectiveAt: number
  createdAt: number
  chainScheduled?: boolean
  chainScheduleTx?: string
  chainScheduleAt?: number
  chainScheduleError?: string
  chainScheduleAttempts?: number
  chainScheduleLastAttemptAt?: number
  chainScheduleNextAttemptAt?: number
  chainScheduleGiveUp?: boolean
  chainScheduleGiveUpAt?: number
  chainScheduleAlertedAttempts?: number[]
}

type AutoRenewCancelLockState = {
  lockedUntil: number
  reason: string
  createdAt: number
}

function kvKeySwitch(walletAddress: string): string {
  return `autoRenew:switch:${walletAddress}`
}

function kvKeyCancelLock(walletAddress: string): string {
  return `autoRenew:cancelLock:${walletAddress}`
}

async function kvGetJson<T>(env: any, key: string): Promise<T | null> {
  if (!env.KV) return null
  const raw = await env.KV.get(key)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

async function kvPutJson(env: any, key: string, value: any): Promise<void> {
  if (!env.KV) return
  await env.KV.put(key, JSON.stringify(value))
}

async function kvDelete(env: any, key: string): Promise<void> {
  if (!env.KV) return
  await env.KV.delete(key)
}

function isDowngrade(fromPlanType: number, toPlanType: number): boolean {
  return toPlanType < fromPlanType
}

function isExecutorAuthorized(request: Request, env: any): boolean {
  const required = String(env.SUBSCRIPTION_EXECUTOR_TOKEN || '').trim()
  if (!required) {
    return String(env.ENVIRONMENT || '') === 'development'
  }
  const token = String(request.headers.get('X-Executor-Token') || '').trim()
  return token !== '' && token === required
}

function getPlanChangeRetryConfig(env: any): {
  maxRetries: number
  baseDelaySeconds: number
  maxDelaySeconds: number
  overdueMaxDelaySeconds: number
  alertAttempts: number[]
} {
  const maxRetries = Number(env.SUBSCRIPTION_PLAN_CHANGE_MAX_RETRIES || '10')
  const baseDelaySeconds = Number(env.SUBSCRIPTION_PLAN_CHANGE_BASE_DELAY_SECONDS || '60')
  const maxDelaySeconds = Number(env.SUBSCRIPTION_PLAN_CHANGE_MAX_DELAY_SECONDS || String(6 * 60 * 60))
  const overdueMaxDelaySeconds = Number(env.SUBSCRIPTION_PLAN_CHANGE_OVERDUE_MAX_DELAY_SECONDS || String(15 * 60))
  const alertAttemptsRaw = String(env.SUBSCRIPTION_PLAN_CHANGE_ALERT_ATTEMPTS || '1,3,5')
  const alertAttempts = alertAttemptsRaw
    .split(',')
    .map(s => Number(s.trim()))
    .filter(n => Number.isFinite(n) && n > 0)
    .sort((a, b) => a - b)
  return { maxRetries, baseDelaySeconds, maxDelaySeconds, overdueMaxDelaySeconds, alertAttempts }
}

function computeNextScheduleAttemptAt(now: number, effectiveAt: number, attempts: number, cfg: ReturnType<typeof getPlanChangeRetryConfig>): number {
  const exp = Math.max(0, attempts - 1)
  if (effectiveAt > 0 && now >= effectiveAt) {
    const delay = Math.min(cfg.baseDelaySeconds * Math.pow(2, exp), cfg.overdueMaxDelaySeconds)
    return now + Math.max(30, Math.floor(delay))
  }
  const delay = Math.min(cfg.baseDelaySeconds * Math.pow(2, exp), cfg.maxDelaySeconds)
  return now + Math.max(30, Math.floor(delay))
}

async function notifyPlanChangeScheduleFailure(
  env: any,
  walletAddress: string,
  state: AutoRenewSwitchState,
  attempt: number,
  errorMessage: string
): Promise<void> {
  const cfg = getPlanChangeRetryConfig(env)
  const shouldNotify = cfg.alertAttempts.includes(attempt) || (state.chainScheduleGiveUp && attempt >= cfg.maxRetries)
  if (!shouldNotify) return

  const alerted = state.chainScheduleAlertedAttempts || []
  if (alerted.includes(attempt)) return

  const ctx: AdminContext = {
    adminEmail: 'system@subscription-executor',
    email: 'system@subscription-executor',
    requestId: `executor_${walletAddress}_${attempt}_${Date.now()}`,
    ip: null,
  }

  await logAdminAction(
    env,
    ctx,
    'AUTO_RENEW_PLAN_CHANGE_SCHEDULE_FAILED',
    'auto_renew_switch',
    walletAddress,
    `attempt=${attempt} effectiveAt=${state.effectiveAt} toPlanType=${state.toPlanType} error=${errorMessage}`.slice(0, 1000)
  )

  const webhookUrl = env.SUBSCRIPTION_ALERT_WEBHOOK_URL
  if (webhookUrl) {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    const token = env.SUBSCRIPTION_ALERT_WEBHOOK_TOKEN
    if (token) headers['Authorization'] = `Bearer ${token}`
    try {
      await fetch(webhookUrl, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          type: 'AUTO_RENEW_PLAN_CHANGE_SCHEDULE_FAILED',
          walletAddress,
          attempt,
          effectiveAt: state.effectiveAt,
          toPlanType: state.toPlanType,
          error: errorMessage,
          giveUp: !!state.chainScheduleGiveUp,
          ts: Math.floor(Date.now() / 1000),
        }),
      })
    } catch (e) {
      console.error('Alert webhook failed:', e)
    }
  }
}

type AutoRenewPlanChangeTask = {
  walletAddress: string
  switchState: AutoRenewSwitchState
}

async function listSwitchTasks(env: any, cursor?: string | null, limit?: number): Promise<{ tasks: AutoRenewPlanChangeTask[]; cursor?: string }> {
  if (!env.KV) return { tasks: [] }
  const res = await env.KV.list({ prefix: 'autoRenew:switch:', cursor: cursor || undefined, limit: limit || 100 })
  const tasks: AutoRenewPlanChangeTask[] = []
  for (const key of res.keys || []) {
    const walletAddress = key.name.replace('autoRenew:switch:', '')
    const state = await kvGetJson<AutoRenewSwitchState>(env, key.name)
    if (!state) continue
    tasks.push({ walletAddress, switchState: state })
  }
  return { tasks, cursor: (res as any).cursor }
}

export async function getPendingPaymentsPublic(request: Request, env: any): Promise<Response> {
  if (!isExecutorAuthorized(request, env)) {
    return new Response(JSON.stringify({ error: 'Unauthorized' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    })
  }
  const now = Math.floor(Date.now() / 1000)
  const subscriptions = await getPendingPayments(env)
  const filtered: AutoRenewSubscription[] = []
  for (const sub of subscriptions) {
    const switchState = await kvGetJson<AutoRenewSwitchState>(env, kvKeySwitch(sub.walletAddress))
    if (switchState && !switchState.chainScheduled && !switchState.chainScheduleGiveUp && switchState.effectiveAt <= now) {
      continue
    }
    filtered.push(sub)
  }
  return new Response(JSON.stringify({ subscriptions: filtered }), {
    headers: { 'Content-Type': 'application/json' },
  })
}

export async function getPendingPlanChangesPublic(request: Request, env: any): Promise<Response> {
  if (!isExecutorAuthorized(request, env)) {
    return new Response(JSON.stringify({ error: 'Unauthorized' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  const url = new URL(request.url)
  const cursor = url.searchParams.get('cursor')
  const limit = Number(url.searchParams.get('limit') || '100')
  const onlyUnscheduled = (url.searchParams.get('onlyUnscheduled') || 'true') !== 'false'
  const now = Math.floor(Date.now() / 1000)

  const { tasks, cursor: nextCursor } = await listSwitchTasks(env, cursor, limit)
  const filteredBase = onlyUnscheduled
    ? tasks.filter(t => !t.switchState.chainScheduled && !t.switchState.chainScheduleGiveUp)
    : tasks

  const filtered = filteredBase.filter(t => {
    const nextAt = t.switchState.chainScheduleNextAttemptAt || 0
    return nextAt <= now
  })

  return new Response(JSON.stringify({ tasks: filtered, cursor: nextCursor || null }), {
    headers: { 'Content-Type': 'application/json' },
  })
}

export async function markPlanChangeScheduledPublic(request: Request, env: any): Promise<Response> {
  if (!isExecutorAuthorized(request, env)) {
    return new Response(JSON.stringify({ error: 'Unauthorized' }), {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  const body = await request.json() as { walletAddress?: string; signature?: string; errorMessage?: string }
  const walletAddress = (body.walletAddress || '').trim()
  if (!walletAddress) {
    return new Response(JSON.stringify({ error: 'Missing walletAddress' }), {
      status: 400,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  const state = await kvGetJson<AutoRenewSwitchState>(env, kvKeySwitch(walletAddress))
  if (!state) {
    return new Response(JSON.stringify({ error: 'No pending plan change' }), {
      status: 404,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  const now = Math.floor(Date.now() / 1000)
  const cfg = getPlanChangeRetryConfig(env)
  const prevAttempts = state.chainScheduleAttempts || 0

  if (!body.errorMessage) {
    const nextState: AutoRenewSwitchState = {
      ...state,
      chainScheduled: true,
      chainScheduleTx: body.signature || state.chainScheduleTx,
      chainScheduleAt: now,
      chainScheduleError: undefined,
      chainScheduleAttempts: prevAttempts,
      chainScheduleLastAttemptAt: now,
      chainScheduleNextAttemptAt: 0,
      chainScheduleGiveUp: false,
      chainScheduleGiveUpAt: undefined,
    }
    await kvPutJson(env, kvKeySwitch(walletAddress), nextState)
    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  }

  const attempt = prevAttempts + 1
  const giveUp = attempt >= cfg.maxRetries
  const nextAttemptAt = giveUp
    ? 0
    : computeNextScheduleAttemptAt(now, state.effectiveAt || 0, attempt, cfg)

  const nextState: AutoRenewSwitchState = {
    ...state,
    chainScheduled: false,
    chainScheduleTx: body.signature || state.chainScheduleTx,
    chainScheduleAt: state.chainScheduleAt,
    chainScheduleError: body.errorMessage,
    chainScheduleAttempts: attempt,
    chainScheduleLastAttemptAt: now,
    chainScheduleNextAttemptAt: nextAttemptAt,
    chainScheduleGiveUp: giveUp || state.chainScheduleGiveUp,
    chainScheduleGiveUpAt: giveUp ? now : state.chainScheduleGiveUpAt,
    chainScheduleAlertedAttempts: state.chainScheduleAlertedAttempts || [],
  }
  if (giveUp) {
    await kvDelete(env, kvKeyCancelLock(walletAddress))
  }

  await notifyPlanChangeScheduleFailure(env, walletAddress, nextState, attempt, body.errorMessage)
  const alerted = nextState.chainScheduleAlertedAttempts || []
  if (!alerted.includes(attempt) && (getPlanChangeRetryConfig(env).alertAttempts.includes(attempt) || giveUp)) {
    nextState.chainScheduleAlertedAttempts = [...alerted, attempt]
  }
  await kvPutJson(env, kvKeySwitch(walletAddress), nextState)

  return new Response(JSON.stringify({ success: true }), {
    headers: { 'Content-Type': 'application/json' },
  })
}

/**
 * 获取自动续费订阅列表
 */
export async function getAutoRenewSubscriptions(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const status = url.searchParams.get('status') || 'all'  // all, active, expired, pending
  
  try {
    if (!env.DB) {
      return new Response(JSON.stringify({ subscriptions: [], total: 0 }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    let whereClause = '1=1'
    const params: any[] = []
    const now = Math.floor(Date.now() / 1000)
    
    if (status === 'active') {
      whereClause += ' AND is_active = 1'
    } else if (status === 'expired') {
      whereClause += ' AND is_active = 0'
    } else if (status === 'pending') {
      whereClause += ' AND is_active = 1 AND next_payment_at <= ?'
      params.push(now)
    }

    const result = await env.DB.prepare(
      `SELECT * FROM auto_renew_subscriptions WHERE ${whereClause} ORDER BY next_payment_at ASC`
    ).bind(...params).all()

    const subscriptions = (result.results || []).map((row: any) => ({
      id: row.id,
      walletAddress: row.wallet_address,
      planType: row.plan_type,
      amountUsdc: row.amount_usdc,
      periodSeconds: row.period_seconds,
      nextPaymentAt: row.next_payment_at,
      isActive: !!row.is_active,
      tokenAccountPda: row.token_account_pda,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    }))

    return new Response(JSON.stringify({ 
      subscriptions, 
      total: subscriptions.length,
      pendingCount: subscriptions.filter((s: any) => s.isActive && s.nextPaymentAt <= now).length
    }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting auto-renew subscriptions:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get auto-renew subscriptions' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 创建/更新自动续费订阅（从 APP 调用）
 */
export async function createAutoRenewSubscription(
  request: Request,
  env: any,
  adminContext: AdminContext | null = null
): Promise<Response> {
  try {
    const body = await request.json() as {
      walletAddress: string
      planType?: number
      amountUsdc?: number
      periodSeconds?: number
      tokenAccountPda: string
      firstPaymentAmount?: number  // 首月优惠金额
      action?: 'create_or_update' | 'schedule_change'
      targetPlanType?: number
      targetAmountUsdc?: number
      targetPeriodSeconds?: number
      effectiveAt?: number
    }

    const now = Math.floor(Date.now() / 1000)
    const id = `auto_${body.walletAddress.slice(0, 8)}_${now}`

    if (env.DB) {
      // 检查是否已存在
      const existing = await env.DB.prepare(
        'SELECT id, plan_type, amount_usdc, period_seconds, next_payment_at FROM auto_renew_subscriptions WHERE wallet_address = ? AND is_active = 1'
      ).bind(body.walletAddress).first()

      const action = body.action || 'create_or_update'
      
      // 计划类型映射
      const PLAN_TYPE_MAP: Record<number, string> = {
        1: 'monthly_continuous',
        2: 'quarterly_continuous',
        3: 'yearly_continuous'
      };

      if (action === 'schedule_change') {
        if (!existing) {
          return new Response(JSON.stringify({ error: 'No active subscription to upgrade' }), {
            status: 409,
            headers: { 'Content-Type': 'application/json' },
          })
        }

        const targetPlanType = body.targetPlanType
        const targetAmountUsdc = body.targetAmountUsdc
        const targetPeriodSeconds = body.targetPeriodSeconds

        if (!targetPlanType || !targetAmountUsdc || !targetPeriodSeconds) {
          return new Response(JSON.stringify({ error: 'Missing target plan fields' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' },
          })
        }

        if (isDowngrade(existing.plan_type, targetPlanType)) {
          return new Response(JSON.stringify({ error: 'Downgrade not allowed' }), {
            status: 409,
            headers: { 'Content-Type': 'application/json' },
          })
        }

        const effectiveAt = body.effectiveAt || existing.next_payment_at
        const switchState: AutoRenewSwitchState = {
          fromPlanType: existing.plan_type,
          toPlanType: targetPlanType,
          toAmountUsdc: targetAmountUsdc,
          toPeriodSeconds: targetPeriodSeconds,
          effectiveAt,
          createdAt: now,
          chainScheduled: false,
          chainScheduleAttempts: 0,
          chainScheduleLastAttemptAt: 0,
          chainScheduleNextAttemptAt: now,
          chainScheduleGiveUp: false,
          chainScheduleGiveUpAt: 0,
          chainScheduleAlertedAttempts: [],
        }

        await kvPutJson(env, kvKeySwitch(body.walletAddress), switchState)
        const cancelLock: AutoRenewCancelLockState = {
          lockedUntil: effectiveAt,
          reason: 'upgrade_pending',
          createdAt: now,
        }
        await kvPutJson(env, kvKeyCancelLock(body.walletAddress), cancelLock)

        return new Response(JSON.stringify({
          success: true,
          id: existing.id,
          scheduled: true,
          effectiveAt,
          cancelLockedUntil: cancelLock.lockedUntil,
          current: {
            planType: existing.plan_type,
            amountUsdc: existing.amount_usdc,
            periodSeconds: existing.period_seconds,
            nextPaymentAt: existing.next_payment_at,
          },
          target: {
            planType: targetPlanType,
            amountUsdc: targetAmountUsdc,
            periodSeconds: targetPeriodSeconds,
          },
        }), {
          headers: { 'Content-Type': 'application/json' },
        })
      }

      if (existing) {
        if (body.planType && isDowngrade(existing.plan_type, body.planType)) {
          return new Response(JSON.stringify({ error: 'Downgrade not allowed' }), {
            status: 409,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        // 更新现有订阅
        await env.DB.prepare(
          `UPDATE auto_renew_subscriptions 
           SET plan_type = ?, amount_usdc = ?, period_seconds = ?, 
               next_payment_at = ?, updated_at = ?
           WHERE id = ?`
        ).bind(
          body.planType,
          body.amountUsdc,
          body.periodSeconds,
          now + (body.periodSeconds || 0),
          now,
          existing.id
        ).run()

        // 同步更新 users 表状态
        const planTypeStr = PLAN_TYPE_MAP[body.planType || existing.plan_type] || 'FREE';
        await env.DB.prepare(
          `UPDATE users SET subscription_type = ?, updated_at = ? WHERE wallet_address = ?`
        ).bind(planTypeStr, now, body.walletAddress).run();

        await kvDelete(env, kvKeySwitch(body.walletAddress))
        await kvDelete(env, kvKeyCancelLock(body.walletAddress))

        return new Response(JSON.stringify({ 
          success: true, 
          id: existing.id,
          message: 'Subscription updated',
          nextPaymentAt: now + (body.periodSeconds || 0)
        }), {
          headers: { 'Content-Type': 'application/json' },
        })
      }

      // 创建新订阅
      await env.DB.prepare(
        `INSERT INTO auto_renew_subscriptions 
         (id, wallet_address, plan_type, amount_usdc, period_seconds, next_payment_at, is_active, token_account_pda, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)`
      ).bind(
        id,
        body.walletAddress,
        body.planType,
        body.amountUsdc,
        body.periodSeconds,
        now + (body.periodSeconds || 0),
        body.tokenAccountPda,
        now,
        now
      ).run()

      // 同步更新 users 表状态
      const planTypeStr = PLAN_TYPE_MAP[body.planType || 1] || 'FREE';
      await env.DB.prepare(
        `UPDATE users SET subscription_type = ?, updated_at = ? WHERE wallet_address = ?`
      ).bind(planTypeStr, now, body.walletAddress).run();

      await kvDelete(env, kvKeySwitch(body.walletAddress))
      await kvDelete(env, kvKeyCancelLock(body.walletAddress))
    }

    if (adminContext) {
      await logAdminAction(env, adminContext, 'CREATE_AUTO_RENEW', 'auto_renew_subscription', id, 
        `创建自动续费: ${body.walletAddress}`)
    }

    return new Response(JSON.stringify({ 
      success: true, 
      id,
      nextPaymentAt: now + (body.periodSeconds || 0)
    }), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error creating auto-renew subscription:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to create auto-renew subscription' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 取消自动续费
 */
export async function cancelAutoRenewSubscription(
  request: Request,
  env: any,
  adminContext: AdminContext | null,
  walletAddress: string
): Promise<Response> {
  try {
    const now = Math.floor(Date.now() / 1000)

    const cancelLock = await kvGetJson<AutoRenewCancelLockState>(env, kvKeyCancelLock(walletAddress))
    if (cancelLock && cancelLock.reason === 'upgrade_pending') {
      return new Response(JSON.stringify({
        error: 'Cancel locked',
        lockedUntil: cancelLock.lockedUntil,
      }), {
        status: 409,
        headers: { 'Content-Type': 'application/json' },
      })
    }

    if (env.DB) {
      await env.DB.prepare(
        `UPDATE auto_renew_subscriptions SET is_active = 0, updated_at = ? WHERE wallet_address = ?`
      ).bind(now, walletAddress).run()
    }

    await kvDelete(env, kvKeySwitch(walletAddress))
    await kvDelete(env, kvKeyCancelLock(walletAddress))

    if (adminContext) {
      await logAdminAction(env, adminContext, 'CANCEL_AUTO_RENEW', 'auto_renew_subscription', walletAddress, 
        `取消自动续费: ${walletAddress}`)
    }

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error cancelling auto-renew subscription:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to cancel auto-renew subscription' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

export async function getAutoRenewStatusPublic(
  request: Request,
  env: any,
  walletAddress: string
): Promise<Response> {
  try {
    if (!env.DB) {
      return new Response(JSON.stringify({ error: 'Database not available' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' },
      })
    }

    const sub = await env.DB.prepare(
      'SELECT id, plan_type, amount_usdc, period_seconds, next_payment_at, is_active FROM auto_renew_subscriptions WHERE wallet_address = ? AND is_active = 1'
    ).bind(walletAddress).first()

    const switchState = await kvGetJson<AutoRenewSwitchState>(env, kvKeySwitch(walletAddress))
    const cancelLock = await kvGetJson<AutoRenewCancelLockState>(env, kvKeyCancelLock(walletAddress))

    return new Response(JSON.stringify({
      walletAddress,
      active: !!sub,
      subscription: sub ? {
        id: sub.id,
        planType: sub.plan_type,
        amountUsdc: sub.amount_usdc,
        periodSeconds: sub.period_seconds,
        nextPaymentAt: sub.next_payment_at,
      } : null,
      pendingChange: switchState ? {
        fromPlanType: switchState.fromPlanType,
        toPlanType: switchState.toPlanType,
        toAmountUsdc: switchState.toAmountUsdc,
        toPeriodSeconds: switchState.toPeriodSeconds,
        effectiveAt: switchState.effectiveAt,
        chainScheduled: !!switchState.chainScheduled,
        scheduleAttempts: switchState.chainScheduleAttempts || 0,
        scheduleLastAttemptAt: switchState.chainScheduleLastAttemptAt || 0,
        scheduleNextAttemptAt: switchState.chainScheduleNextAttemptAt || 0,
        scheduleGiveUp: !!switchState.chainScheduleGiveUp,
        scheduleGiveUpAt: switchState.chainScheduleGiveUpAt || 0,
        scheduleError: switchState.chainScheduleError || null,
      } : null,
      cancelLockedUntil: cancelLock ? cancelLock.lockedUntil : 0,
    }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting auto-renew status:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get auto-renew status' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取待执行的自动续费扣款
 * 由定时任务调用
 */
export async function getPendingPayments(
  env: any
): Promise<AutoRenewSubscription[]> {
  const now = Math.floor(Date.now() / 1000)
  
  if (!env.DB) return []

  const result = await env.DB.prepare(
    `SELECT * FROM auto_renew_subscriptions 
     WHERE is_active = 1 AND next_payment_at <= ?
     ORDER BY next_payment_at ASC
     LIMIT 100`
  ).bind(now).all()

  return (result.results || []).map((row: any) => ({
    id: row.id,
    walletAddress: row.wallet_address,
    planType: row.plan_type,
    amountUsdc: row.amount_usdc,
    periodSeconds: row.period_seconds,
    nextPaymentAt: row.next_payment_at,
    isActive: !!row.is_active,
    tokenAccountPda: row.token_account_pda,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  }))
}

/**
 * 记录扣款结果
 */
export async function recordPaymentResult(
  env: any,
  subscriptionId: string,
  success: boolean,
  transactionId?: string,
  errorMessage?: string
): Promise<void> {
  const now = Math.floor(Date.now() / 1000)

  if (!env.DB) return

  if (success) {
    // 更新下次扣款时间
    const sub = await env.DB.prepare(
      'SELECT period_seconds FROM auto_renew_subscriptions WHERE id = ?'
    ).bind(subscriptionId).first()

    if (sub) {
      await env.DB.prepare(
        `UPDATE auto_renew_subscriptions 
         SET next_payment_at = ?, updated_at = ?
         WHERE id = ?`
      ).bind(now + sub.period_seconds, now, subscriptionId).run()
    }
  } else {
    // 失败后暂停订阅
    await env.DB.prepare(
      `UPDATE auto_renew_subscriptions SET is_active = 0, updated_at = ? WHERE id = ?`
    ).bind(now, subscriptionId).run()
  }

  // 记录扣款日志
  await env.DB.prepare(
    `INSERT INTO auto_renew_payment_logs 
     (id, subscription_id, success, transaction_id, error_message, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).bind(
    `log_${subscriptionId}_${now}`,
    subscriptionId,
    success ? 1 : 0,
    transactionId || null,
    errorMessage || null,
    now
  ).run()
}

export async function reportAutoRenewPaymentResultPublic(
  request: Request,
  env: any
): Promise<Response> {
  try {
    if (!env.DB) {
      return new Response(JSON.stringify({ error: 'Database not available' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json' },
      })
    }

    const token = request.headers.get('X-Executor-Token') || ''
    if (env.SUBSCRIPTION_EXECUTOR_TOKEN && env.SUBSCRIPTION_EXECUTOR_TOKEN !== token) {
      return new Response(JSON.stringify({ error: 'Unauthorized' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      })
    }

    const body = await request.json() as {
      subscriptionId: string
      success: boolean
      transactionId?: string
      errorMessage?: string
    }

    if (!body.subscriptionId) {
      return new Response(JSON.stringify({ error: 'Missing subscriptionId' }), {
        status: 400,
        headers: { 'Content-Type': 'application/json' },
      })
    }

    const now = Math.floor(Date.now() / 1000)

    const sub = await env.DB.prepare(
      `SELECT id, wallet_address, plan_type, amount_usdc, period_seconds, next_payment_at
       FROM auto_renew_subscriptions WHERE id = ?`
    ).bind(body.subscriptionId).first()

    if (!sub) {
      return new Response(JSON.stringify({ error: 'Subscription not found' }), {
        status: 404,
        headers: { 'Content-Type': 'application/json' },
      })
    }

    const walletAddress = sub.wallet_address as string

    if (body.success && env.KV) {
      const switchKey = kvKeySwitch(walletAddress)
      const raw = await env.KV.get(switchKey)
      if (raw) {
        try {
          const state = JSON.parse(raw) as AutoRenewSwitchState
          if (state.chainScheduled && state.effectiveAt && state.effectiveAt <= now) {
            await env.DB.prepare(
              `UPDATE auto_renew_subscriptions
               SET plan_type = ?, amount_usdc = ?, period_seconds = ?, updated_at = ?
               WHERE id = ?`
            ).bind(state.toPlanType, state.toAmountUsdc, state.toPeriodSeconds, now, body.subscriptionId).run()
            await env.KV.delete(switchKey)
          }
        } catch {
          await env.KV.delete(switchKey)
        }
      }
    }

    await recordPaymentResult(env, body.subscriptionId, body.success, body.transactionId, body.errorMessage)

    if (body.success && env.KV) {
      await env.KV.delete(kvKeyCancelLock(walletAddress))
    }

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error reporting payment result:', error)
    return new Response(JSON.stringify({ error: 'Failed to report payment result' }), {
      status: 500,
      headers: { 'Content-Type': 'application/json' },
    })
  }
}

/**
 * 获取自动续费扣款日志
 */
export async function getAutoRenewPaymentLogs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')
  const walletAddress = url.searchParams.get('wallet') || ''

  try {
    if (!env.DB) {
      return new Response(JSON.stringify({ logs: [], total: 0, page, pageSize }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    let whereClause = '1=1'
    const params: any[] = []

    if (walletAddress) {
      whereClause += ' AND ars.wallet_address = ?'
      params.push(walletAddress)
    }

    // 获取总数
    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM auto_renew_payment_logs arpl
       JOIN auto_renew_subscriptions ars ON arpl.subscription_id = ars.id
       WHERE ${whereClause}`
    ).bind(...params).first()

    const offset = (page - 1) * pageSize
    const result = await env.DB.prepare(
      `SELECT arpl.*, ars.wallet_address, ars.plan_type, ars.amount_usdc
       FROM auto_renew_payment_logs arpl
       JOIN auto_renew_subscriptions ars ON arpl.subscription_id = ars.id
       WHERE ${whereClause}
       ORDER BY arpl.created_at DESC
       LIMIT ? OFFSET ?`
    ).bind(...params, pageSize, offset).all()

    const logs = (result.results || []).map((row: any) => ({
      id: row.id,
      subscriptionId: row.subscription_id,
      walletAddress: row.wallet_address,
      planType: row.plan_type,
      amountUsdc: row.amount_usdc,
      success: !!row.success,
      transactionId: row.transaction_id,
      errorMessage: row.error_message,
      createdAt: row.created_at,
    }))

    return new Response(JSON.stringify({ 
      logs, 
      total: countResult?.count || 0, 
      page, 
      pageSize 
    }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting payment logs:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get payment logs' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 处理订阅管理路由
 */
export async function handleSubscriptionsRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  if (request.method === 'GET' && path === '/admin/subscriptions/plans') {
    return getSubscriptionPlans(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/subscriptions/plans') {
    return createSubscriptionPlan(request, env, adminContext)
  }

  const updateMatch = path.match(/^\/admin\/subscriptions\/plans\/([^/]+)$/)
  if (request.method === 'PUT' && updateMatch) {
    return updateSubscriptionPlan(request, env, adminContext, updateMatch[1])
  }

  if (request.method === 'DELETE' && updateMatch) {
    return deleteSubscriptionPlan(request, env, adminContext, updateMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/subscriptions/records') {
    return getSubscriptionRecords(request, env, adminContext)
  }

  if (request.method === 'GET' && path === '/admin/subscriptions/stats') {
    return getSubscriptionStats(request, env, adminContext)
  }

  // 自动续费相关路由
  if (request.method === 'GET' && path === '/admin/subscriptions/auto-renew') {
    return getAutoRenewSubscriptions(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/subscriptions/auto-renew') {
    return createAutoRenewSubscription(request, env, adminContext)
  }

  const cancelMatch = path.match(/^\/admin\/subscriptions\/auto-renew\/([^/]+)\/cancel$/)
  if (request.method === 'POST' && cancelMatch) {
    return cancelAutoRenewSubscription(request, env, adminContext, cancelMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/subscriptions/auto-renew/logs') {
    return getAutoRenewPaymentLogs(request, env, adminContext)
  }

  return null
}
