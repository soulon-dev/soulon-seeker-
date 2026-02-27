import { describe, expect, it, vi } from 'vitest'
import { handleGameRoutes } from '../src/routes/game'

vi.mock('../src/utils/solana', () => {
  return {
    verifyGenesisCollectionWithDas: vi.fn(async () => ({ supported: true, hasToken: false, total: 0 })),
  }
})

const deferred = <T,>() => {
  let resolve!: (v: T) => void
  let reject!: (e: any) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const buildTxDeferred = deferred<any>()
const mockBuildTx = vi.fn(async () => buildTxDeferred.promise)

vi.mock('../src/services/ship-core', () => {
  return {
    buildShipCoreCandyMachineMintTx: (...args: any[]) => mockBuildTx(...args),
    confirmShipCoreMint: vi.fn(async () => ({ metadataUri: null })),
  }
})

vi.mock('@solana/web3.js', async (importOriginal) => {
  const mod: any = await importOriginal()
  return {
    ...mod,
    Connection: class {
      constructor() {}
      async getTransaction() {
        return null
      }
    },
  }
})

type UserRow = { id: string; wallet_address: string }
type AppConfigRow = { config_key: string; config_value: string; is_active: number }
type ShipRow = { wallet_address: string; minted_at: number; mint_signature?: string | null; asset_address?: string | null; metadata_uri?: string | null }
type WalletRateRow = { wallet_address: string; last_tx_at: number | null; last_confirm_at: number | null }
type IpRateRow = { ip: string; last_at: number }
type LockRow = { wallet_address: string; locked_until: number }
type TxCacheRow = { wallet_address: string; created_at: number; transaction_base64: string; asset_address: string; candy_machine: string; start_at: number }

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
      const wallet = String(p[0])
      const row = this.db.users.find(r => r.wallet_address === wallet)
      return (row ? { id: row.id } : null) as any
    }

    if (sql.startsWith('SELECT minted_at FROM game_ship_nfts WHERE wallet_address = ?')) {
      const wallet = String(p[0])
      const row = this.db.shipNfts.find(r => r.wallet_address === wallet)
      return (row ? { minted_at: row.minted_at } : null) as any
    }

    if (sql.startsWith('SELECT minted_at, mint_signature, asset_address, metadata_uri FROM game_ship_nfts WHERE wallet_address = ?')) {
      const wallet = String(p[0])
      const row = this.db.shipNfts.find(r => r.wallet_address === wallet)
      return (row ? { minted_at: row.minted_at, mint_signature: row.mint_signature ?? null, asset_address: row.asset_address ?? null, metadata_uri: row.metadata_uri ?? null } : null) as any
    }

    if (sql.startsWith('SELECT config_value FROM app_config WHERE config_key = ?')) {
      const key = String(p[0])
      const row = this.db.appConfig.find(r => r.config_key === key && r.is_active === 1)
      return (row ? { config_value: row.config_value } : null) as any
    }

    if (sql.startsWith('SELECT last_tx_at, last_confirm_at FROM game_ship_mint_rate_limits')) {
      const wallet = String(p[0])
      const row = this.db.walletRates.find(r => r.wallet_address === wallet)
      return (row ? { last_tx_at: row.last_tx_at, last_confirm_at: row.last_confirm_at } : null) as any
    }

    if (sql.startsWith('SELECT last_at FROM game_ship_ip_rate_limits')) {
      const ip = String(p[0])
      const row = this.db.ipRates.find(r => r.ip === ip)
      return (row ? { last_at: row.last_at } : null) as any
    }

    if (sql.startsWith('SELECT locked_until FROM game_ship_mint_tx_locks')) {
      const wallet = String(p[0])
      const row = this.db.locks.find(r => r.wallet_address === wallet)
      return (row ? { locked_until: row.locked_until } : null) as any
    }

    if (sql.startsWith('SELECT created_at, transaction_base64, asset_address, candy_machine, start_at FROM game_ship_mint_tx_cache')) {
      const wallet = String(p[0])
      const row = this.db.txCache.find(r => r.wallet_address === wallet)
      return (row
        ? {
            created_at: row.created_at,
            transaction_base64: row.transaction_base64,
            asset_address: row.asset_address,
            candy_machine: row.candy_machine,
            start_at: row.start_at,
          }
        : null) as any
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

    if (sql.startsWith('CREATE TABLE IF NOT EXISTS')) return { meta: { changes: 0 } }
    if (sql.startsWith('ALTER TABLE')) return { meta: { changes: 0 } }

    if (sql.startsWith('INSERT INTO users')) {
      const id = String(p[0])
      const wallet = String(p[1])
      this.db.users.push({ id, wallet_address: wallet })
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('INSERT INTO game_ship_mint_rate_limits')) {
      const wallet = String(p[0])
      const lastTx = p[1] == null ? null : Number(p[1])
      const lastConfirm = p[2] == null ? null : Number(p[2])
      this.db.walletRates.push({ wallet_address: wallet, last_tx_at: lastTx, last_confirm_at: lastConfirm })
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('UPDATE game_ship_mint_rate_limits SET last_tx_at')) {
      const lastTx = Number(p[0])
      const wallet = String(p[1])
      const row = this.db.walletRates.find(r => r.wallet_address === wallet)
      if (row) row.last_tx_at = lastTx
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('UPDATE game_ship_mint_rate_limits SET last_confirm_at')) {
      const lastConfirm = Number(p[0])
      const wallet = String(p[1])
      const row = this.db.walletRates.find(r => r.wallet_address === wallet)
      if (row) row.last_confirm_at = lastConfirm
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('INSERT INTO game_ship_ip_rate_limits')) {
      const ip = String(p[0])
      const lastAt = Number(p[1])
      this.db.ipRates.push({ ip, last_at: lastAt })
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('UPDATE game_ship_ip_rate_limits SET last_at')) {
      const lastAt = Number(p[0])
      const ip = String(p[1])
      const row = this.db.ipRates.find(r => r.ip === ip)
      if (row) row.last_at = lastAt
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('INSERT INTO game_ship_mint_tx_locks')) {
      const wallet = String(p[0])
      const lockedUntil = Number(p[1])
      this.db.locks.push({ wallet_address: wallet, locked_until: lockedUntil })
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('UPDATE game_ship_mint_tx_locks SET locked_until = 0')) {
      const wallet = String(p[0])
      const row = this.db.locks.find(r => r.wallet_address === wallet)
      if (row) row.locked_until = 0
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('UPDATE game_ship_mint_tx_locks SET locked_until')) {
      const lockedUntil = Number(p[0])
      const wallet = String(p[1])
      const row = this.db.locks.find(r => r.wallet_address === wallet)
      if (row) row.locked_until = lockedUntil
      return { meta: { changes: 1 } }
    }

    if (sql.startsWith('INSERT INTO game_ship_mint_tx_cache')) {
      const wallet = String(p[0])
      const createdAt = Number(p[1])
      const tx = String(p[2])
      const asset = String(p[3])
      const cm = String(p[4])
      const startAt = Number(p[5])
      const row = this.db.txCache.find(r => r.wallet_address === wallet)
      if (!row) {
        this.db.txCache.push({ wallet_address: wallet, created_at: createdAt, transaction_base64: tx, asset_address: asset, candy_machine: cm, start_at: startAt })
      } else {
        row.created_at = createdAt
        row.transaction_base64 = tx
        row.asset_address = asset
        row.candy_machine = cm
        row.start_at = startAt
      }
      return { meta: { changes: 1 } }
    }

    return { meta: { changes: 0 } }
  }
}

class MockDB {
  users: UserRow[] = []
  appConfig: AppConfigRow[] = []
  shipNfts: ShipRow[] = []
  walletRates: WalletRateRow[] = []
  ipRates: IpRateRow[] = []
  locks: LockRow[] = []
  txCache: TxCacheRow[] = []

  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('ship mint concurrency guards', () => {
  it('rate limits mint/tx per wallet', async () => {
    const db = new MockDB()
    db.appConfig.push({ config_key: 'game.ship.require_nft', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.collection', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.core.candy_machine', config_value: 'CM', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.recipient', config_value: 'Recipient', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.start_at', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.enabled', config_value: '1', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.soulbound.mode', config_value: 'server_record', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.teaser_uri', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.reveal_mode', config_value: '0', is_active: 1 })

    const env: any = { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' }
    const nowSpy = vi.spyOn(Date, 'now').mockReturnValue(1_000_000)

    buildTxDeferred.resolve({
      transactionBase64: 'dHhfYmFzZTY0',
      assetAddress: 'Asset11111111111111111111111111111111111',
      candyMachine: 'CandyMachine1111111111111111111111111111111',
    })

    const req1 = new Request('https://example.com/api/v1/game/ship/mint/tx', {
      method: 'POST',
      body: '{}',
      headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '1.1.1.1' },
    })
    ;(req1 as any).walletAddress = 'wallet_1'
    const res1 = await handleGameRoutes(req1, env, '/api/v1/game/ship/mint/tx')
    expect(res1?.status).toBe(200)

    const req2 = new Request('https://example.com/api/v1/game/ship/mint/tx', {
      method: 'POST',
      body: '{}',
      headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '1.1.1.1' },
    })
    ;(req2 as any).walletAddress = 'wallet_1'
    const res2 = await handleGameRoutes(req2, env, '/api/v1/game/ship/mint/tx')
    expect(res2?.status).toBe(429)
    expect(res2?.headers.get('Retry-After')).toBeTruthy()

    nowSpy.mockRestore()
  })

  it('returns Busy when mint/tx lock is held (even if rate limit passed)', async () => {
    const db = new MockDB()
    db.appConfig.push({ config_key: 'game.ship.require_nft', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.collection', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.core.candy_machine', config_value: 'CM', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.recipient', config_value: 'Recipient', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.start_at', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.enabled', config_value: '1', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.soulbound.mode', config_value: 'server_record', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.teaser_uri', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.reveal_mode', config_value: '0', is_active: 1 })

    const env: any = { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' }

    const nowSpy = vi.spyOn(Date, 'now').mockImplementation(() => 1_000_000)

    const hold = deferred<any>()
    mockBuildTx.mockImplementationOnce(async () => hold.promise)

    const req1 = new Request('https://example.com/api/v1/game/ship/mint/tx', {
      method: 'POST',
      body: '{}',
      headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '2.2.2.2' },
    })
    ;(req1 as any).walletAddress = 'wallet_lock'
    const p1 = handleGameRoutes(req1, env, '/api/v1/game/ship/mint/tx')

    for (let i = 0; i < 20 && db.locks.length === 0; i++) {
      await Promise.resolve()
    }
    nowSpy.mockImplementation(() => 1_003_000)

    const req2 = new Request('https://example.com/api/v1/game/ship/mint/tx', {
      method: 'POST',
      body: '{}',
      headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '2.2.2.2' },
    })
    ;(req2 as any).walletAddress = 'wallet_lock'
    const res2 = await handleGameRoutes(req2, env, '/api/v1/game/ship/mint/tx')
    expect(res2?.status).toBe(429)
    const body2 = await res2!.json()
    expect(['Busy', 'Rate limited']).toContain(body2.error)
    expect(db.locks[0]?.locked_until).toBeGreaterThan(1003)

    hold.resolve({
      transactionBase64: 'dHhfYmFzZTY0',
      assetAddress: 'Asset11111111111111111111111111111111111',
      candyMachine: 'CandyMachine1111111111111111111111111111111',
    })
    const res1 = await p1
    expect(res1?.status).toBe(200)

    nowSpy.mockRestore()
  })

  it('rate limits mint/confirm per wallet when repeated quickly', async () => {
    const db = new MockDB()
    db.appConfig.push({ config_key: 'game.ship.require_nft', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.collection', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.core.candy_machine', config_value: 'CM', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.recipient', config_value: 'Recipient', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.price_lamports', config_value: '50000000', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.start_at', config_value: '0', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.mint.enabled', config_value: '1', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.soulbound.mode', config_value: 'server_record', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.teaser_uri', config_value: '', is_active: 1 })
    db.appConfig.push({ config_key: 'game.ship.metadata.reveal_mode', config_value: '0', is_active: 1 })

    const env: any = { DB: db, KV: null, SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com' }
    const nowSpy = vi.spyOn(Date, 'now').mockReturnValue(1_000_000)

    const req1 = new Request('https://example.com/api/v1/game/ship/mint/confirm', {
      method: 'POST',
      body: JSON.stringify({ signature: 'sig_1', assetAddress: 'asset_1' }),
      headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '3.3.3.3' },
    })
    ;(req1 as any).walletAddress = 'wallet_confirm'
    const res1 = await handleGameRoutes(req1, env, '/api/v1/game/ship/mint/confirm')
    expect(res1?.status).toBe(400)

    const req2 = new Request('https://example.com/api/v1/game/ship/mint/confirm', {
      method: 'POST',
      body: JSON.stringify({ signature: 'sig_2', assetAddress: 'asset_2' }),
      headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': '3.3.3.3' },
    })
    ;(req2 as any).walletAddress = 'wallet_confirm'
    const res2 = await handleGameRoutes(req2, env, '/api/v1/game/ship/mint/confirm')
    expect(res2?.status).toBe(429)
    expect(res2?.headers.get('Retry-After')).toBeTruthy()

    nowSpy.mockRestore()
  })
})
