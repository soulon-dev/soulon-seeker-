import { describe, expect, it, vi } from 'vitest'
import { PublicKey } from '@solana/web3.js'
import { addWalletAddress } from '../src/routes/admin/blockchain'

vi.mock('../src/services/subscription-chain', async (importOriginal) => {
  const actual: any = await importOriginal()
  return {
    ...actual,
    updateSubscriptionProgramConfigOnChain: vi.fn(async (params: any) => ({
      signature: 'mock_sig_tx',
      confirmedAdmin: params.field === 'admin' ? params.newValue : new PublicKey(new Uint8Array(32).fill(9)).toBase58(),
      confirmedExecutor: params.field === 'executor' ? params.newValue : new PublicKey(new Uint8Array(32).fill(8)).toBase58(),
      confirmedRecipient: params.field === 'recipient' ? params.newValue : new PublicKey(new Uint8Array(32).fill(7)).toBase58(),
    })),
  }
})

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

type WalletRow = {
  id: number
  name: string
  address: string
  type: string
  network: string
  description: string | null
  is_active: number
  created_by: string | null
  created_at: number
  updated_at: number
}

type AppConfigRow = {
  config_key: string
  config_value: string
  category: string
  is_active: number
  updated_by: string | null
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

    if (sql.startsWith('SELECT id FROM wallet_addresses WHERE address = ?')) {
      const address = p[0]
      const row = this.db.wallets.find(r => r.address === address)
      return row ? { id: row.id } : null
    }

    if (sql.startsWith('SELECT config_value FROM app_config WHERE config_key = ?')) {
      const key = p[0]
      const row = this.db.appConfig.find(r => r.config_key === key)
      return row ? { config_value: row.config_value } : null
    }

    return null
  }

  async all(): Promise<any> {
    return { results: [] }
  }

  async run(): Promise<any> {
    const sql = this.sql
    const p = this.params

    if (sql.startsWith('UPDATE wallet_addresses SET is_active = 0')) {
      const [updatedAt, type, network, excludeId] = p
      for (const row of this.db.wallets) {
        if (row.id !== excludeId && row.type === type && row.network === network && row.is_active === 1) {
          row.is_active = 0
          row.updated_at = updatedAt
        }
      }
      return { success: true }
    }

    if (sql.includes('INSERT INTO wallet_addresses')) {
      const [name, address, type, network, description, isActive, createdBy, createdAt, updatedAt] = p
      const id = ++this.db.walletId
      this.db.wallets.push({
        id,
        name,
        address,
        type,
        network,
        description,
        is_active: isActive,
        created_by: createdBy,
        created_at: createdAt,
        updated_at: updatedAt,
      })
      return { meta: { last_row_id: id } }
    }

    if (sql.startsWith('UPDATE wallet_addresses SET is_active = 1')) {
      const [updatedAt, id] = p
      const row = this.db.wallets.find(r => r.id === id)
      if (row) {
        row.is_active = 1
        row.updated_at = updatedAt
      }
      return { success: true }
    }

    if (sql.startsWith('DELETE FROM wallet_addresses WHERE id = ?')) {
      const [id] = p
      this.db.wallets = this.db.wallets.filter(r => r.id !== id)
      return { success: true }
    }

    if (sql.includes('INSERT INTO config_change_history')) {
      this.db.configHistory.push({ params: p })
      return { success: true }
    }

    if (sql.startsWith('UPDATE app_config SET config_value = ?')) {
      const [value, updatedBy, updatedAt, key] = p
      const row = this.db.appConfig.find(r => r.config_key === key)
      if (row) {
        row.config_value = value
        row.updated_by = updatedBy
        row.updated_at = updatedAt
        row.is_active = 1
      }
      return { success: true }
    }

    if (sql.includes('INSERT INTO app_config')) {
      const [
        key,
        value,
        _valueType,
        category,
        _subCategory,
        _displayName,
        _description,
        _defaultValue,
        _isSensitive,
        _requiresRestart,
        isActive,
        updatedBy,
        createdAt,
        updatedAt,
      ] = p
      this.db.appConfig.push({
        config_key: key,
        config_value: value,
        category,
        is_active: isActive,
        updated_by: updatedBy,
        created_at: createdAt,
        updated_at: updatedAt,
      })
      return { success: true }
    }

    if (sql.includes('INSERT INTO admin_logs')) {
      this.db.adminLogs.push({ params: p })
      return { success: true }
    }

    return { success: true }
  }
}

class MockDB {
  walletId = 0
  wallets: WalletRow[] = []
  appConfig: AppConfigRow[] = []
  configHistory: any[] = []
  adminLogs: any[] = []

  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('wallet address management', () => {
  it('adding recipient wallet syncs blockchain.recipient_wallet to app_config and KV', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))

    const DB = new MockDB()
    const KV = new MockKV()
    const env: any = {
      DB,
      KV,
      SOLANA_RPC_URL: 'https://api.devnet.solana.com',
      SUBSCRIPTION_PROGRAM_ID: new PublicKey(new Uint8Array(32).fill(4)).toBase58(),
      SUBSCRIPTION_ADMIN_PRIVATE_KEY: 'dummy',
    }
    const adminContext: any = { adminEmail: 'admin@test', email: 'admin@test', requestId: 'r1', ip: '127.0.0.1' }

    const recipientAddress = new PublicKey(new Uint8Array(32).fill(1)).toBase58()
    const req = new Request('http://test/admin/blockchain/wallets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Recipient',
        address: recipientAddress,
        type: 'recipient',
        network: 'devnet',
      }),
    })

    const resp = await addWalletAddress(req, env, adminContext)
    expect(resp.status).toBe(200)

    expect(DB.wallets.length).toBe(1)
    expect(DB.wallets[0].type).toBe('recipient')
    expect(DB.wallets[0].network).toBe('devnet')
    expect(DB.wallets[0].is_active).toBe(1)

    const cfg = DB.appConfig.find(r => r.config_key === 'blockchain.recipient_wallet')
    expect(cfg?.config_value).toBe(recipientAddress)

    const signed = await KV.get('config:blockchain.recipient_wallet')
    expect(signed).not.toBeNull()
    expect(signed!).toContain(`"value":"${recipientAddress}"`)
    expect(signed!).toContain('mock_sig_blockchain.recipient_wallet_')
  })

  it('adding a new recipient wallet deactivates previous and records config history', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))

    const now = Math.floor(Date.now() / 1000)
    const DB = new MockDB()
    const KV = new MockKV()
    const env: any = {
      DB,
      KV,
      SOLANA_RPC_URL: 'https://api.mainnet-beta.solana.com',
      SUBSCRIPTION_PROGRAM_ID: new PublicKey(new Uint8Array(32).fill(4)).toBase58(),
      SUBSCRIPTION_ADMIN_PRIVATE_KEY: 'dummy',
    }
    const adminContext: any = { adminEmail: 'admin@test', email: 'admin@test', requestId: 'r1', ip: '127.0.0.1' }

    const oldRecipient = new PublicKey(new Uint8Array(32).fill(2)).toBase58()
    const newRecipient = new PublicKey(new Uint8Array(32).fill(3)).toBase58()
    DB.wallets.push({
      id: 1,
      name: 'Recipient1',
      address: oldRecipient,
      type: 'recipient',
      network: 'mainnet',
      description: null,
      is_active: 1,
      created_by: 'admin@test',
      created_at: now,
      updated_at: now,
    })
    DB.walletId = 1
    DB.appConfig.push({
      config_key: 'blockchain.recipient_wallet',
      config_value: oldRecipient,
      category: 'blockchain',
      is_active: 1,
      updated_by: 'admin@test',
      created_at: now,
      updated_at: now,
    })

    const req = new Request('http://test/admin/blockchain/wallets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: 'Recipient2',
        address: newRecipient,
        type: 'recipient',
        network: 'mainnet',
      }),
    })

    const resp = await addWalletAddress(req, env, adminContext)
    expect(resp.status).toBe(200)

    const active = DB.wallets.filter(w => w.type === 'recipient' && w.network === 'mainnet' && w.is_active === 1)
    expect(active.length).toBe(1)
    expect(active[0].address).toBe(newRecipient)

    const cfg = DB.appConfig.find(r => r.config_key === 'blockchain.recipient_wallet')
    expect(cfg?.config_value).toBe(newRecipient)
    expect(DB.configHistory.length).toBe(1)
  })
})
