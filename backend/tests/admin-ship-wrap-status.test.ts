import { describe, expect, it, vi } from 'vitest'
import { handleGameRoutes } from '../src/routes/admin/game'

vi.mock('../src/routes/admin/middleware', () => {
  return {
    logAdminAction: vi.fn(async () => {}),
  }
})

vi.mock('../src/utils/config-signature', () => {
  return {
    signConfigValue: vi.fn(async () => 'sig'),
  }
})

vi.mock('../src/services/ship-core', () => {
  return {
    createShipCandyGuardAndWrap: vi.fn(async () => ({
      candyGuardAddress: '9aZ1UJptz8UM244zXaTw6t6TmHyGkh73PjmpPaQfGVDH',
      createGuardSignature: 'wrapSig111111111111111111111111111111111111111111111111111111111111',
      wrapSignature: 'wrapSig111111111111111111111111111111111111111111111111111111111111',
    })),
    addShipCandyMachineConfigLines: vi.fn(),
    getAdminUmi: vi.fn(),
    applyShipSoulboundOracle: vi.fn(),
    createShipCandyMachineWithGuards: vi.fn(),
    createShipSoulboundCollection: vi.fn(),
    diagnoseShipAdminSigner: vi.fn(() => ({ configured: true, ok: true, publicKey: 'X' })),
  }
})

vi.mock('@solana/web3.js', async (importOriginal) => {
  const mod: any = await importOriginal()
  return {
    ...mod,
    Connection: class {
      constructor() {}
      async getSignatureStatuses() {
        return { value: [{ err: null, confirmationStatus: 'finalized' }] }
      }
    }
  }
})

class MockStmt {
  constructor(private db: MockDB, private sql: string, private params: any[] = []) {}
  bind(...params: any[]) {
    return new MockStmt(this.db, this.sql, params)
  }
  async first(): Promise<any> {
    const sql = this.sql
    const p = this.params
    if (sql.startsWith('SELECT config_value FROM app_config WHERE config_key = ?')) {
      const key = p[0]
      const row = this.db.appConfig.find(r => r.config_key === key && r.is_active === 1)
      return row ? { config_value: row.config_value } : null
    }
    return null
  }
  async run(): Promise<any> {
    const sql = this.sql
    const p = this.params
    if (sql.startsWith('UPDATE app_config SET config_value = ?')) {
      const [value, , , key] = p
      const row = this.db.appConfig.find(r => r.config_key === key)
      if (row) row.config_value = value
      return { success: true }
    }
    if (sql.startsWith('INSERT INTO app_config')) {
      const [key, value] = p
      this.db.appConfig.push({ config_key: key, config_value: value, is_active: 1 })
      return { success: true }
    }
    return { success: true }
  }
}

class MockDB {
  appConfig: Array<{ config_key: string; config_value: string; is_active: number }> = []
  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

class MockKV {
  store = new Map<string, string>()
  async put(key: string, value: string) {
    this.store.set(key, value)
  }
  async get(key: string) {
    return this.store.get(key) ?? null
  }
}

describe('admin ship wrap guards job', () => {
  it('writes candy guard and wrap signature to config on success', async () => {
    const env: any = { KV: new MockKV(), DB: new MockDB(), SOLANA_RPC_URL: 'https://example.invalid' }
    const adminContext: any = { email: 'admin@test', ip: '127.0.0.1' }
    const url = 'https://api.soulon.top/admin/game/ship/core/candy-machine/wrap-guards?async=1'
    const req = new Request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        candyMachineAddress: '5bD6eM5WACAGqTWvjf7syYLCuHitFcsjN7DStXKkiwPk',
        mintPriceLamports: 1,
        mintRecipient: 'QEF7NgbtJzWvzt17vWkNYK4Uq3zuiEsyi2xoPGSWCdU',
        botTaxLamports: 1,
        startAt: 0,
        mintLimit: 1,
        mintLimitId: 1,
      }),
    })

    let pending: Promise<any> | null = null
    const ctx: any = { waitUntil: (p: Promise<any>) => { pending = p } }
    const res = await handleGameRoutes(req, env, adminContext, '/admin/game/ship/core/candy-machine/wrap-guards', ctx)
    expect(res?.status).toBe(202)
    expect(pending).toBeTruthy()
    await pending

    const candyGuard = await env.DB.prepare('SELECT config_value FROM app_config WHERE config_key = ? AND is_active = 1')
      .bind('game.ship.core.candy_guard_address').first()
    const wrapSig = await env.DB.prepare('SELECT config_value FROM app_config WHERE config_key = ? AND is_active = 1')
      .bind('game.ship.core.wrap_signature').first()

    expect(candyGuard?.config_value).toBe('9aZ1UJptz8UM244zXaTw6t6TmHyGkh73PjmpPaQfGVDH')
    expect(wrapSig?.config_value).toContain('wrapSig')
  })
})

