import { describe, expect, it, vi } from 'vitest'
import { handleGameRoutes } from '../src/routes/game'

vi.mock('../src/utils/solana', () => {
  return {
    verifyGenesisCollectionWithDas: vi.fn(async () => ({ supported: true, hasToken: false, total: 0 })),
  }
})

class MockStmt {
  constructor(
    private db: MockDB,
    private sql: string,
    private params: any[] = []
  ) {}

  bind(...params: any[]) {
    return new MockStmt(this.db, this.sql, params)
  }

  async first<T = any>(): Promise<T | null> {
    const sql = this.sql
    const p = this.params

    if (sql.startsWith('SELECT id FROM users WHERE wallet_address = ?')) {
      const wallet = p[0]
      const row = this.db.users.find(r => r.wallet_address === wallet)
      return (row ? { id: row.id } : null) as any
    }

    if (sql.startsWith('SELECT COUNT(1) AS c FROM game_ship_mint_notify_subscriptions')) {
      return ({ c: this.db.notifySubs.length } as any)
    }

    if (sql.startsWith('SELECT config_value FROM app_config WHERE config_key = ?')) {
      const key = p[0]
      const row = this.db.appConfig.find(r => r.config_key === key && r.is_active === 1)
      return (row ? { config_value: row.config_value } : null) as any
    }

    return null
  }

  async all<T = any>(): Promise<{ results: T[] }> {
    const sql = this.sql
    const p = this.params

    if (sql.includes('FROM app_config') && sql.includes('config_key IN')) {
      const keys = p.map(String)
      const rows = this.db.appConfig
        .filter(r => r.is_active === 1 && keys.includes(r.config_key))
        .map(r => ({ configKey: r.config_key, configValue: r.config_value })) as any
      return { results: rows }
    }

    return { results: [] as any }
  }

  async run(): Promise<any> {
    const sql = this.sql
    const p = this.params

    if (sql.startsWith('CREATE TABLE IF NOT EXISTS game_ship_nfts')) return { meta: { changes: 0 } }
    if (sql.startsWith('CREATE TABLE IF NOT EXISTS game_ship_metadata')) return { meta: { changes: 0 } }
    if (sql.startsWith('CREATE TABLE IF NOT EXISTS game_ship_mint_notify_subscriptions')) return { meta: { changes: 0 } }
    if (sql.startsWith('ALTER TABLE game_ship_nfts ADD COLUMN')) return { meta: { changes: 0 } }

    if (sql.startsWith('INSERT INTO users')) {
      const id = p[0]
      const wallet = p[1]
      this.db.users.push({ id, wallet_address: wallet })
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('INSERT INTO game_ship_mint_notify_subscriptions')) {
      const wallet = String(p[0])
      const startAt = Number(p[1])
      const now = Number(p[2])
      const existing = this.db.notifySubs.find(r => r.wallet_address === wallet)
      if (!existing) {
        this.db.notifySubs.push({ wallet_address: wallet, start_at: startAt, created_at: now, updated_at: now })
        return { meta: { changes: 1 } }
      }
      existing.start_at = startAt
      existing.updated_at = now
      return { meta: { changes: 1 } }
    }

    return { meta: { changes: 0 } }
  }
}

type UserRow = { id: string; wallet_address: string }
type AppConfigRow = { config_key: string; config_value: string; is_active: number }
type NotifyRow = { wallet_address: string; start_at: number; created_at: number; updated_at: number }

class MockDB {
  users: UserRow[] = []
  appConfig: AppConfigRow[] = []
  notifySubs: NotifyRow[] = []

  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('ship mint notify queueCount', () => {
  it('counts unique notification subscribers and does not decrease', async () => {
    const db = new MockDB()
    db.appConfig.push({ config_key: 'game.ship.require_nft', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.collection', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.core.candy_machine', config_value: 'CM', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.recipient', config_value: 'Recipient', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.start_at', config_value: '999999999', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.enabled', config_value: '1', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.soulbound.mode', config_value: 'server_record', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.teaser_uri', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.reveal_mode', config_value: '0', is_active: 1 })

    const env: any = { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' }

    const sub1 = new Request('https://example.com/api/v1/game/ship/mint/notify/subscribe', {
      method: 'POST',
      body: JSON.stringify({ startAt: 999999999 }),
      headers: { 'Content-Type': 'application/json' },
    })
    ;(sub1 as any).walletAddress = 'wallet_1'
    const sub1Res = await handleGameRoutes(sub1, env, '/api/v1/game/ship/mint/notify/subscribe')
    expect(sub1Res?.status).toBe(200)
    expect(await sub1Res!.json()).toMatchObject({ success: true, queueCount: 1 })

    const sub1Again = new Request('https://example.com/api/v1/game/ship/mint/notify/subscribe', {
      method: 'POST',
      body: JSON.stringify({ startAt: 999999999 }),
      headers: { 'Content-Type': 'application/json' },
    })
    ;(sub1Again as any).walletAddress = 'wallet_1'
    const sub1AgainRes = await handleGameRoutes(sub1Again, env, '/api/v1/game/ship/mint/notify/subscribe')
    expect(sub1AgainRes?.status).toBe(200)
    expect(await sub1AgainRes!.json()).toMatchObject({ success: true, queueCount: 1 })

    const sub2 = new Request('https://example.com/api/v1/game/ship/mint/notify/subscribe', {
      method: 'POST',
      body: JSON.stringify({ startAt: 999999999 }),
      headers: { 'Content-Type': 'application/json' },
    })
    ;(sub2 as any).walletAddress = 'wallet_2'
    const sub2Res = await handleGameRoutes(sub2, env, '/api/v1/game/ship/mint/notify/subscribe')
    expect(sub2Res?.status).toBe(200)
    expect(await sub2Res!.json()).toMatchObject({ success: true, queueCount: 2 })

    const statusReq = new Request('https://example.com/api/v1/game/ship/eligibility', { method: 'GET' })
    ;(statusReq as any).walletAddress = 'wallet_1'
    const statusRes = await handleGameRoutes(statusReq, env, '/api/v1/game/ship/eligibility')
    expect(statusRes?.status).toBe(200)
    expect(await statusRes!.json()).toMatchObject({ queueCount: 2 })
  })
})

