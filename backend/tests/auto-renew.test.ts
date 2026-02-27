import { describe, expect, it, vi } from 'vitest'
import { cancelAutoRenewSubscription, createAutoRenewSubscription, markPlanChangeScheduledPublic, reportAutoRenewPaymentResultPublic } from '../src/routes/admin/subscriptions'

class MockKV {
  store = new Map<string, string>()

  async get(key: string): Promise<string | null> {
    return this.store.has(key) ? this.store.get(key)! : null
  }

  async put(key: string, value: string): Promise<void> {
    this.store.set(key, value)
  }

  async delete(key: string): Promise<void> {
    this.store.delete(key)
  }
}

type SubRow = {
  id: string
  wallet_address: string
  plan_type: number
  amount_usdc: number
  period_seconds: number
  next_payment_at: number
  is_active: number
  token_account_pda: string
  created_at: number
  updated_at: number
}

class MockStmt {
  constructor(
    private db: MockDB,
    private sql: string,
    private params: any[] = []
  ) {}

  bind(...params: any[]) {
    return new MockStmt(this.db, this.sql, params)
  }

  async first(): Promise<any> {
    const sql = this.sql
    const p = this.params

    if (sql.includes('FROM auto_renew_subscriptions WHERE wallet_address = ? AND is_active = 1')) {
      const wallet = p[0]
      const row = this.db.subs.find(r => r.wallet_address === wallet && r.is_active === 1)
      return row ? {
        id: row.id,
        plan_type: row.plan_type,
        amount_usdc: row.amount_usdc,
        period_seconds: row.period_seconds,
        next_payment_at: row.next_payment_at,
      } : null
    }

    if (sql.includes('SELECT id, wallet_address, plan_type, amount_usdc, period_seconds, next_payment_at') && sql.includes('WHERE id = ?')) {
      const id = p[0]
      const row = this.db.subs.find(r => r.id === id)
      return row ? {
        id: row.id,
        wallet_address: row.wallet_address,
        plan_type: row.plan_type,
        amount_usdc: row.amount_usdc,
        period_seconds: row.period_seconds,
        next_payment_at: row.next_payment_at,
      } : null
    }

    if (sql.includes('SELECT period_seconds FROM auto_renew_subscriptions WHERE id = ?')) {
      const id = p[0]
      const row = this.db.subs.find(r => r.id === id)
      return row ? { period_seconds: row.period_seconds } : null
    }

    return null
  }

  async run(): Promise<any> {
    const sql = this.sql
    const p = this.params

    if (sql.startsWith('UPDATE auto_renew_subscriptions') && sql.includes('WHERE id = ?') && sql.includes('SET plan_type')) {
      const [planType, amountUsdc, periodSeconds, updatedAt, id] = p
      const row = this.db.subs.find(r => r.id === id)
      if (row) {
        row.plan_type = planType
        row.amount_usdc = amountUsdc
        row.period_seconds = periodSeconds
        row.updated_at = updatedAt
      }
      return { success: true }
    }

    if (sql.startsWith('UPDATE auto_renew_subscriptions') && sql.includes('SET plan_type = ?') && sql.includes('next_payment_at')) {
      const [planType, amountUsdc, periodSeconds, nextPaymentAt, updatedAt, id] = p
      const row = this.db.subs.find(r => r.id === id)
      if (row) {
        row.plan_type = planType
        row.amount_usdc = amountUsdc
        row.period_seconds = periodSeconds
        row.next_payment_at = nextPaymentAt
        row.updated_at = updatedAt
      }
      return { success: true }
    }

    if (sql.startsWith('UPDATE auto_renew_subscriptions') && sql.includes('SET next_payment_at = ?')) {
      const [nextPaymentAt, updatedAt, id] = p
      const row = this.db.subs.find(r => r.id === id)
      if (row) {
        row.next_payment_at = nextPaymentAt
        row.updated_at = updatedAt
      }
      return { success: true }
    }

    if (sql.startsWith('UPDATE auto_renew_subscriptions SET is_active = 0') && sql.includes('WHERE wallet_address = ?')) {
      const [updatedAt, wallet] = p
      for (const row of this.db.subs) {
        if (row.wallet_address === wallet) {
          row.is_active = 0
          row.updated_at = updatedAt
        }
      }
      return { success: true }
    }

    if (sql.startsWith('INSERT INTO auto_renew_payment_logs')) {
      const [id, subscriptionId, success, txId, errMsg, createdAt] = p
      this.db.logs.push({
        id,
        subscription_id: subscriptionId,
        success,
        transaction_id: txId,
        error_message: errMsg,
        created_at: createdAt,
      })
      return { success: true }
    }

    return { success: true }
  }

  async all(): Promise<any> {
    return { results: [] }
  }
}

class MockDB {
  subs: SubRow[] = []
  logs: any[] = []

  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('auto-renew schedule upgrade & payment callback', () => {
  it('schedule_change creates pending switch and cancel lock', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))
    const now = Math.floor(Date.now() / 1000)

    const DB = new MockDB()
    DB.subs.push({
      id: 'sub_1',
      wallet_address: 'wallet_1',
      plan_type: 1,
      amount_usdc: 9.99,
      period_seconds: 30,
      next_payment_at: now + 100,
      is_active: 1,
      token_account_pda: '',
      created_at: now,
      updated_at: now,
    })
    const KV = new MockKV()
    const env: any = { DB, KV }

    const req = new Request('http://test/api/v1/subscription/auto-renew', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        walletAddress: 'wallet_1',
        tokenAccountPda: '',
        action: 'schedule_change',
        targetPlanType: 2,
        targetAmountUsdc: 24.99,
        targetPeriodSeconds: 90,
      }),
    })

    const resp = await createAutoRenewSubscription(req, env, null)
    expect(resp.status).toBe(200)
    const json = await resp.json() as any
    expect(json.scheduled).toBe(true)
    expect(json.cancelLockedUntil).toBe(DB.subs[0].next_payment_at)
    expect(await KV.get('autoRenew:switch:wallet_1')).not.toBeNull()
    expect(await KV.get('autoRenew:cancelLock:wallet_1')).not.toBeNull()
  })

  it('cancel is blocked while upgrade_pending lock exists', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))
    const now = Math.floor(Date.now() / 1000)

    const DB = new MockDB()
    DB.subs.push({
      id: 'sub_1',
      wallet_address: 'wallet_1',
      plan_type: 1,
      amount_usdc: 9.99,
      period_seconds: 30,
      next_payment_at: now + 100,
      is_active: 1,
      token_account_pda: '',
      created_at: now,
      updated_at: now,
    })
    const KV = new MockKV()
    await KV.put('autoRenew:cancelLock:wallet_1', JSON.stringify({ lockedUntil: now + 100, reason: 'upgrade_pending', createdAt: now }))
    const env: any = { DB, KV }

    const req = new Request('http://test/api/v1/subscription/auto-renew/wallet_1/cancel', { method: 'POST' })
    const resp = await cancelAutoRenewSubscription(req, env, null, 'wallet_1')
    expect(resp.status).toBe(409)
  })

  it('payment-result applies pending switch then unlocks cancel', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))
    const now = Math.floor(Date.now() / 1000)

    const DB = new MockDB()
    DB.subs.push({
      id: 'sub_1',
      wallet_address: 'wallet_1',
      plan_type: 1,
      amount_usdc: 9.99,
      period_seconds: 30,
      next_payment_at: now,
      is_active: 1,
      token_account_pda: '',
      created_at: now,
      updated_at: now,
    })
    const KV = new MockKV()
    await KV.put('autoRenew:switch:wallet_1', JSON.stringify({
      fromPlanType: 1,
      toPlanType: 2,
      toAmountUsdc: 24.99,
      toPeriodSeconds: 90,
      effectiveAt: now,
      createdAt: now,
      chainScheduled: true,
      chainScheduleTx: 'sched_tx_1',
      chainScheduleAt: now,
    }))
    await KV.put('autoRenew:cancelLock:wallet_1', JSON.stringify({ lockedUntil: now, reason: 'upgrade_pending', createdAt: now }))

    const env: any = { DB, KV, SUBSCRIPTION_EXECUTOR_TOKEN: 'tok' }
    const req = new Request('http://test/api/v1/subscription/payment-result', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Executor-Token': 'tok' },
      body: JSON.stringify({
        subscriptionId: 'sub_1',
        success: true,
        transactionId: 'tx_1',
      }),
    })

    const resp = await reportAutoRenewPaymentResultPublic(req, env)
    expect(resp.status).toBe(200)

    const row = DB.subs[0]
    expect(row.plan_type).toBe(2)
    expect(row.amount_usdc).toBe(24.99)
    expect(row.period_seconds).toBe(90)
    expect(row.next_payment_at).toBe(now + 90)

    expect(await KV.get('autoRenew:switch:wallet_1')).toBeNull()
    expect(await KV.get('autoRenew:cancelLock:wallet_1')).toBeNull()
    expect(DB.logs.length).toBe(1)
  })

  it('plan change scheduling failure increments retries with backoff', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))
    const now = Math.floor(Date.now() / 1000)

    const DB = new MockDB()
    const KV = new MockKV()
    await KV.put('autoRenew:switch:wallet_1', JSON.stringify({
      fromPlanType: 1,
      toPlanType: 2,
      toAmountUsdc: 24.99,
      toPeriodSeconds: 90,
      effectiveAt: now + 3600,
      createdAt: now,
      chainScheduled: false,
      chainScheduleAttempts: 0,
      chainScheduleNextAttemptAt: now,
    }))

    const env: any = { DB, KV, SUBSCRIPTION_EXECUTOR_TOKEN: 'tok' }
    const req = new Request('http://test/api/v1/subscription/executor/plan-change-scheduled', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Executor-Token': 'tok' },
      body: JSON.stringify({ walletAddress: 'wallet_1', errorMessage: 'rpc error' }),
    })
    const resp = await markPlanChangeScheduledPublic(req, env)
    expect(resp.status).toBe(200)

    const state = JSON.parse((await KV.get('autoRenew:switch:wallet_1'))!) as any
    expect(state.chainScheduled).toBe(false)
    expect(state.chainScheduleAttempts).toBe(1)
    expect(state.chainScheduleNextAttemptAt).toBeGreaterThan(now)
    expect(state.chainScheduleGiveUp).toBeFalsy()
  })

  it('plan change scheduling gives up and unlocks cancel after max retries', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))
    const now = Math.floor(Date.now() / 1000)

    const DB = new MockDB()
    const KV = new MockKV()
    await KV.put('autoRenew:switch:wallet_1', JSON.stringify({
      fromPlanType: 1,
      toPlanType: 2,
      toAmountUsdc: 24.99,
      toPeriodSeconds: 90,
      effectiveAt: now + 3600,
      createdAt: now,
      chainScheduled: false,
      chainScheduleAttempts: 0,
      chainScheduleNextAttemptAt: now,
    }))
    await KV.put('autoRenew:cancelLock:wallet_1', JSON.stringify({ lockedUntil: now + 3600, reason: 'upgrade_pending', createdAt: now }))

    const env: any = {
      DB,
      KV,
      SUBSCRIPTION_EXECUTOR_TOKEN: 'tok',
      SUBSCRIPTION_PLAN_CHANGE_MAX_RETRIES: '2',
      SUBSCRIPTION_PLAN_CHANGE_BASE_DELAY_SECONDS: '1',
    }

    for (let i = 0; i < 2; i++) {
      const req = new Request('http://test/api/v1/subscription/executor/plan-change-scheduled', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Executor-Token': 'tok' },
        body: JSON.stringify({ walletAddress: 'wallet_1', errorMessage: `rpc error ${i}` }),
      })
      const resp = await markPlanChangeScheduledPublic(req, env)
      expect(resp.status).toBe(200)
    }

    const state = JSON.parse((await KV.get('autoRenew:switch:wallet_1'))!) as any
    expect(state.chainScheduleGiveUp).toBe(true)
    expect(await KV.get('autoRenew:cancelLock:wallet_1')).toBeNull()
  })
})
