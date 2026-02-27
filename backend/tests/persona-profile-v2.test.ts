import { describe, expect, it } from 'vitest'
import { handlePersonaRoutes } from '../src/routes/persona'

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

    if (sql.includes('FROM user_persona WHERE wallet_address = ?')) {
      return null
    }

    if (sql.includes('FROM user_persona_profile_v2') && sql.includes('SELECT profile_json')) {
      const wallet = p[0]
      const row = this.db.personaV2.get(wallet)
      return row ? { profile_json: row.profile_json, updated_at: row.updated_at } : null
    }

    return null
  }

  async run(): Promise<any> {
    const sql = this.sql
    const p = this.params

    if (sql.trim().startsWith('CREATE TABLE IF NOT EXISTS user_persona_profile_v2')) {
      return { success: true }
    }
    if (sql.trim().startsWith('CREATE INDEX IF NOT EXISTS idx_persona_profile_v2_wallet')) {
      return { success: true }
    }

    if (sql.includes('INSERT INTO user_persona_profile_v2')) {
      const wallet = p[0]
      const profileJson = p[1]
      const updatedAt = p[2]
      this.db.personaV2.set(wallet, { profile_json: profileJson, updated_at: updatedAt })
      return { success: true }
    }

    return { success: true }
  }
}

class MockDB {
  personaV2 = new Map<string, { profile_json: string, updated_at: number }>()
  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('persona profile v2', () => {
  it('stores and returns personaProfileV2 via persona routes', async () => {
    const env: any = { DB: new MockDB() }
    const wallet = 'test_wallet_123'
    const profileV2 = { version: 2, ocean: { openness: { alpha: 1, beta: 1, updatedAt: 1 } }, updatedAt: 1, sampleCount: 1, evidence: [] }

    const putReq = new Request('https://example.com/api/v1/persona/profile-v2', {
      method: 'PUT',
      body: JSON.stringify({ walletAddress: wallet, personaProfileV2: profileV2 }),
      headers: { 'Content-Type': 'application/json' }
    })
    const putRes = await handlePersonaRoutes(putReq, env, '/api/v1/persona/profile-v2')
    expect(putRes?.status).toBe(200)

    const getReq = new Request(`https://example.com/api/v1/persona?wallet=${wallet}`, { method: 'GET' })
    const getRes = await handlePersonaRoutes(getReq, env, '/api/v1/persona')
    expect(getRes?.status).toBe(200)
    const json = await getRes!.json()
    expect(json.persona.personaProfileV2).toBeTruthy()
    expect(json.persona.personaProfileV2.version).toBe(2)
  })
})

