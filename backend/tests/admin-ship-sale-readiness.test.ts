import { describe, expect, it, vi } from 'vitest'
import { PublicKey } from '@solana/web3.js'
import { handleGameRoutes } from '../src/routes/admin/game'

vi.mock('@metaplex-foundation/umi-bundle-defaults', () => {
  return {
    createUmi: () => {
      const umi: any = {
        identity: null,
        use(plugin: any) {
          if (typeof plugin === 'function') plugin(umi)
          return umi
        },
      }
      return umi
    },
  }
})

vi.mock('../src/routes/admin/middleware', () => {
  return {
    logAdminAction: vi.fn(async () => {}),
  }
})

vi.mock('../src/services/ship-core', () => {
  return {
    addShipCandyMachineConfigLines: vi.fn(),
    getAdminUmi: vi.fn(),
    applyShipSoulboundOracle: vi.fn(),
    createShipCandyGuardAndWrap: vi.fn(),
    createShipCandyMachineWithGuards: vi.fn(),
    createShipSoulboundCollection: vi.fn(),
    diagnoseShipAdminSigner: vi.fn(() => ({ configured: true, ok: true, publicKey: 'X' })),
  }
})

const mockFetchCandyMachine = vi.fn(async () => ({ authority: { toString: () => '' }, mintAuthority: { toString: () => '' } }))
vi.mock('@metaplex-foundation/mpl-core-candy-machine', () => {
  return {
    fetchCandyMachine: (...args: any[]) => mockFetchCandyMachine(...args),
    mplCandyMachine: () => () => {},
  }
})

vi.mock('@metaplex-foundation/mpl-core', () => {
  return { mplCore: () => () => {} }
})

class MockStmt {
  constructor(private db: MockDB, private sql: string, private params: any[] = []) {}
  bind(...params: any[]) {
    return new MockStmt(this.db, this.sql, params)
  }
  async first(): Promise<any> {
    const sql = this.sql
    const p = this.params
    if (sql.startsWith('SELECT config_value FROM app_config WHERE config_key = ? AND is_active = 1')) {
      const key = p[0]
      const row = this.db.appConfig.find(r => r.config_key === key && r.is_active === 1)
      return row ? { config_value: row.config_value } : null
    }
    if (sql.startsWith('SELECT config_value FROM app_config WHERE config_key = ?')) {
      const key = p[0]
      const row = this.db.appConfig.find(r => r.config_key === key)
      return row ? { config_value: row.config_value } : null
    }
    return null
  }
  async run(): Promise<any> {
    return { success: true }
  }
}

class MockDB {
  appConfig: Array<{ config_key: string; config_value: string; is_active: number }> = []
  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('admin ship sale readiness', () => {
  it('returns not ready when missing required config and not wrapped', async () => {
    const env: any = { DB: new MockDB(), KV: null, SOLANA_RPC_URL: 'https://example.invalid' }
    const adminContext: any = { email: 'admin@test', ip: '127.0.0.1' }
    const cm = '5bD6eM5WACAGqTWvjf7syYLCuHitFcsjN7DStXKkiwPk'

    mockFetchCandyMachine.mockResolvedValueOnce({
      authority: { toString: () => 'QEF7NgbtJzWvzt17vWkNYK4Uq3zuiEsyi2xoPGSWCdU' },
      mintAuthority: { toString: () => 'QEF7NgbtJzWvzt17vWkNYK4Uq3zuiEsyi2xoPGSWCdU' },
    })

    const req = new Request(`https://api.soulon.top/admin/game/ship/core/sale/readiness?candyMachineAddress=${cm}`, { method: 'GET' })
    const res = await handleGameRoutes(req, env, adminContext, '/admin/game/ship/core/sale/readiness')
    expect(res?.status).toBe(200)
    const body = await res!.json()
    expect(body.ready).toBe(false)
    expect(body.reasons).toContain('not_wrapped')
    expect(body.config.requiredMissing.length).toBeGreaterThan(0)
  })

  it('returns ready when required config present and mintAuthority equals expected candy guard', async () => {
    const env: any = { DB: new MockDB(), KV: null, SOLANA_RPC_URL: 'https://example.invalid' }
    const adminContext: any = { email: 'admin@test', ip: '127.0.0.1' }
    const cm = '5bD6eM5WACAGqTWvjf7syYLCuHitFcsjN7DStXKkiwPk'
    const authority = 'QEF7NgbtJzWvzt17vWkNYK4Uq3zuiEsyi2xoPGSWCdU'
    const programId = new PublicKey('CMAGAKJ67e9hRZgfC5SFTbZH8MgEmtqazKXjmkaJjWTJ')
    const [expectedPda] = PublicKey.findProgramAddressSync([Buffer.from('candy_guard'), new PublicKey(authority).toBytes()], programId)
    const expectedCandyGuardAddress = expectedPda.toBase58()

    env.DB.appConfig.push(
      { config_key: 'game.ship.collection', config_value: '46pcSL5gmjBrPqGKFaLbbCmR6iVuLJbnQy13h111111', is_active: 1 },
      { config_key: 'game.ship.core.candy_machine', config_value: cm, is_active: 1 },
      { config_key: 'game.ship.core.candy_guard_address', config_value: expectedCandyGuardAddress, is_active: 1 },
      { config_key: 'game.ship.core.wrap_signature', config_value: 'sig', is_active: 1 },
      { config_key: 'game.ship.mint.enabled', config_value: '1', is_active: 1 },
      { config_key: 'game.ship.mint.recipient', config_value: authority, is_active: 1 },
      { config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 },
      { config_key: 'game.ship.mint.start_at', config_value: '0', is_active: 1 },
    )

    mockFetchCandyMachine.mockResolvedValueOnce({
      authority: { toString: () => authority },
      mintAuthority: { toString: () => expectedCandyGuardAddress },
    })

    const req = new Request(`https://api.soulon.top/admin/game/ship/core/sale/readiness?candyMachineAddress=${cm}`, { method: 'GET' })
    const res = await handleGameRoutes(req, env, adminContext, '/admin/game/ship/core/sale/readiness')
    expect(res?.status).toBe(200)
    const body = await res!.json()
    expect(body.ready).toBe(true)
    expect(body.wrap.isWrapped).toBe(true)
    expect(body.wrap.expectedCandyGuardAddress).toBe(expectedCandyGuardAddress)
  })
})
