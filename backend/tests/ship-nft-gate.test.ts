import { describe, expect, it, vi } from 'vitest'
import { handleGameRoutes } from '../src/routes/game'

type UserRow = { id: string; wallet_address: string }
type AppConfigRow = { config_key: string; config_value: string; is_active: number }
type ShipRow = { wallet_address: string; minted_at: number }

vi.mock('../src/utils/solana', () => {
  return {
    verifyGenesisCollectionWithDas: vi.fn(async () => ({ supported: true, hasToken: false, total: 0 })),
  }
})

vi.mock('@solana/web3.js', async (importOriginal) => {
  const mod: any = await importOriginal()
  return {
    ...mod,
    Connection: class {
      constructor() {}
      async getTransaction() {
        return { meta: { err: null } }
      }
    }
  }
})

vi.mock('../src/services/ship-core', () => {
  return {
    buildShipCoreCandyMachineMintTx: vi.fn(async () => ({
      transactionBase64: 'dHhfYmFzZTY0',
      assetAddress: 'Asset11111111111111111111111111111111111',
      candyMachine: 'CandyMachine1111111111111111111111111111111',
    })),
    confirmShipCoreMint: vi.fn(async () => ({ metadataUri: null })),
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

    if (sql.startsWith('SELECT minted_at FROM game_ship_nfts WHERE wallet_address = ?')) {
      const wallet = p[0]
      const row = this.db.shipNfts.find(r => r.wallet_address === wallet)
      return (row ? { minted_at: row.minted_at } : null) as any
    }

    if (sql.startsWith('SELECT 1 as v FROM game_ship_nfts WHERE wallet_address = ?')) {
      const wallet = p[0]
      const row = this.db.shipNfts.find(r => r.wallet_address === wallet)
      return (row ? { v: 1 } : null) as any
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

    if (sql.startsWith('CREATE TABLE IF NOT EXISTS game_ship_nfts')) {
      return { meta: { changes: 0 } }
    }

    if (sql.startsWith('INSERT INTO users')) {
      const id = p[0]
      const wallet = p[1]
      this.db.users.push({ id, wallet_address: wallet })
      return { meta: { changes: 1 } }
    }

    if (sql.includes('INTO game_ship_nfts')) {
      const wallet = p[0]
      const mintedAt = p[1]
      const exists = this.db.shipNfts.some(r => r.wallet_address === wallet)
      if (!exists) this.db.shipNfts.push({ wallet_address: wallet, minted_at: mintedAt })
      return { meta: { changes: exists ? 0 : 1 } }
    }

    if (sql.startsWith('UPDATE game_ship_nfts SET')) {
      return { meta: { changes: 1 } }
    }

    return { meta: { changes: 0 } }
  }
}

class MockDB {
  users: UserRow[] = []
  appConfig: AppConfigRow[] = []
  shipNfts: ShipRow[] = []

  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('game ship nft gate', () => {
  it('blocks game endpoints when NFT is required and not minted', async () => {
    const db = new MockDB()
    db.appConfig.push({ config_key: 'game.ship.require_nft', config_value: '1', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.collection', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.recipient', config_value: 'Recipient111111111111111111111111111111111', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.start_at', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.enabled', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.soulbound.mode', config_value: 'server_record', is_active: 1 })

    const req = new Request('https://example.com/api/v1/game/unknown', { method: 'GET' })
    ;(req as any).walletAddress = 'wallet_1'

    const res = await handleGameRoutes(req, { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' } as any, '/api/v1/game/unknown')
    expect(res?.status).toBe(403)
    const body = await res!.json()
    expect(body.error).toBe('Ship NFT required')
  })

  it('mints record after confirm and then allows entry', async () => {
    const db = new MockDB()
    db.appConfig.push({ config_key: 'game.ship.require_nft', config_value: '1', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.collection', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.recipient', config_value: 'Recipient111111111111111111111111111111111', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.start_at', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.enabled', config_value: '1', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.soulbound.mode', config_value: 'server_record', is_active: 1 })

    const statusReq = new Request('https://example.com/api/v1/game/ship/eligibility', { method: 'GET' })
    ;(statusReq as any).walletAddress = 'wallet_1'
    const statusRes = await handleGameRoutes(statusReq, { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' } as any, '/api/v1/game/ship/eligibility')
    expect(statusRes?.status).toBe(200)
    expect(await statusRes!.json()).toMatchObject({ hasNft: false })

    const mintReq = new Request('https://example.com/api/v1/game/ship/mint/confirm', {
      method: 'POST',
      body: JSON.stringify({ signature: 'mock_sig', assetAddress: 'Asset11111111111111111111111111111111111' }),
      headers: { 'Content-Type': 'application/json' },
    })
    ;(mintReq as any).walletAddress = 'wallet_1'
    const mintRes = await handleGameRoutes(mintReq, { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' } as any, '/api/v1/game/ship/mint/confirm')
    expect(mintRes?.status).toBe(200)
    expect(await mintRes!.json()).toMatchObject({ success: true, hasNft: true })

    const blockedReq = new Request('https://example.com/api/v1/game/unknown', { method: 'GET' })
    ;(blockedReq as any).walletAddress = 'wallet_1'
    const blockedRes = await handleGameRoutes(blockedReq, { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' } as any, '/api/v1/game/unknown')
    expect(blockedRes).toBeNull()
  })

  it('rejects mint endpoints when mint is disabled', async () => {
    const db = new MockDB()
    db.appConfig.push({ config_key: 'game.ship.require_nft', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.collection', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.recipient', config_value: 'Recipient111111111111111111111111111111111', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.start_at', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.enabled', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.soulbound.mode', config_value: 'server_record', is_active: 1 })

    const txReq = new Request('https://example.com/api/v1/game/ship/mint/tx', { method: 'POST', body: '{}' })
    ;(txReq as any).walletAddress = 'wallet_1'
    const txRes = await handleGameRoutes(txReq, { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' } as any, '/api/v1/game/ship/mint/tx')
    expect(txRes?.status).toBe(403)
    expect(await txRes!.json()).toMatchObject({ error: 'Mint disabled' })

    const confirmReq = new Request('https://example.com/api/v1/game/ship/mint/confirm', {
      method: 'POST',
      body: JSON.stringify({ signature: 'mock_sig' }),
      headers: { 'Content-Type': 'application/json' },
    })
    ;(confirmReq as any).walletAddress = 'wallet_1'
    const confirmRes = await handleGameRoutes(confirmReq, { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' } as any, '/api/v1/game/ship/mint/confirm')
    expect(confirmRes?.status).toBe(403)
    expect(await confirmRes!.json()).toMatchObject({ error: 'Mint disabled' })
  })
})
