import { describe, expect, it, vi } from 'vitest'
import { handleSupportRoutes } from '../src/routes/support'
import { handleSupportAdminRoutes } from '../src/routes/admin/support'

type BugReportRow = {
  id: string
  wallet_address: string | null
  contact_email: string | null
  severity: string
  description: string
  include_device_info: number
  device_info: string | null
  app_version: string | null
  platform: string | null
  status: string
  estimated_reward: number
  expert_candidate: number
  reward_granted: number | null
  expert_granted: number
  admin_notes: string | null
  processed_at: number | null
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

    if (sql.includes('FROM support_bug_reports') && sql.includes('WHERE id = ?')) {
      const id = p[0]
      const row = this.db.bugReports.find(r => r.id === id)
      if (!row) return null
      return {
        id: row.id,
        walletAddress: row.wallet_address,
        contactEmail: row.contact_email,
        severity: row.severity,
        description: row.description,
        includeDeviceInfo: row.include_device_info,
        deviceInfo: row.device_info,
        appVersion: row.app_version,
        platform: row.platform,
        status: row.status,
        estimatedReward: row.estimated_reward,
        expertCandidate: row.expert_candidate,
        rewardGranted: row.reward_granted,
        expertGranted: row.expert_granted,
        adminNotes: row.admin_notes,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        processedAt: row.processed_at,
      }
    }

    return null
  }

  async all(): Promise<any> {
    const sql = this.sql
    const p = this.params

    if (sql.includes('FROM support_bug_reports') && sql.includes('ORDER BY created_at DESC')) {
      let rows = [...this.db.bugReports]

      if (sql.includes('status = ?')) {
        const idx = p.findIndex(x => typeof x === 'string' && ['OPEN', 'IN_REVIEW', 'RESOLVED', 'CLOSED'].includes(x))
        if (idx >= 0) rows = rows.filter(r => r.status === p[idx])
      }
      if (sql.includes('severity = ?')) {
        const idx = p.findIndex(x => typeof x === 'string' && ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(x))
        if (idx >= 0) rows = rows.filter(r => r.severity === p[idx])
      }
      if (sql.includes('wallet_address = ?')) {
        const wallet = p.find(x => typeof x === 'string' && x.length > 20 && !['LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'OPEN', 'IN_REVIEW', 'RESOLVED', 'CLOSED'].includes(x))
        if (wallet) rows = rows.filter(r => r.wallet_address === wallet)
      }

      rows.sort((a, b) => b.created_at - a.created_at)
      const limit = p[p.length - 2]
      const offset = p[p.length - 1]
      const page = rows.slice(offset, offset + limit)

      return {
        results: page.map(r => ({
          id: r.id,
          walletAddress: r.wallet_address,
          contactEmail: r.contact_email,
          severity: r.severity,
          status: r.status,
          estimatedReward: r.estimated_reward,
          expertCandidate: r.expert_candidate,
          rewardGranted: r.reward_granted,
          expertGranted: r.expert_granted,
          createdAt: r.created_at,
          updatedAt: r.updated_at,
        })),
      }
    }

    return { results: [] }
  }

  async run(): Promise<any> {
    const sql = this.sql
    const p = this.params

    if (sql.includes('INSERT INTO support_bug_reports')) {
      const [
        id,
        walletAddress,
        contactEmail,
        severity,
        description,
        includeDeviceInfo,
        deviceInfo,
        appVersion,
        platform,
        status,
        estimatedReward,
        expertCandidate,
        createdAt,
        updatedAt,
      ] = p

      const row: BugReportRow = {
        id,
        wallet_address: walletAddress,
        contact_email: contactEmail,
        severity,
        description,
        include_device_info: includeDeviceInfo,
        device_info: deviceInfo,
        app_version: appVersion,
        platform,
        status,
        estimated_reward: estimatedReward,
        expert_candidate: expertCandidate,
        reward_granted: null,
        expert_granted: 0,
        admin_notes: null,
        processed_at: null,
        created_at: createdAt,
        updated_at: updatedAt,
      }

      this.db.bugReports.push(row)
      return { success: true }
    }

    if (sql.startsWith('UPDATE support_bug_reports')) {
      const id = p[p.length - 1]
      const row = this.db.bugReports.find(r => r.id === id)
      if (!row) return { success: true }

      const setClause = sql.split('SET')[1].split('WHERE')[0]
      const parts = setClause.split(',').map(s => s.trim()).filter(Boolean)
      const assignments = parts.map(s => s.split('=')[0].trim())

      const values = p.slice(0, p.length - 1)
      for (let i = 0; i < assignments.length; i++) {
        const col = assignments[i]
        const val = values[i]
        switch (col) {
          case 'status':
            row.status = val
            break
          case 'admin_notes':
            row.admin_notes = val
            break
          case 'reward_granted':
            row.reward_granted = val
            break
          case 'expert_granted':
            row.expert_granted = val
            break
          case 'processed_at':
            row.processed_at = val
            break
          case 'updated_at':
            row.updated_at = val
            break
        }
      }

      return { success: true }
    }

    if (sql.includes('INSERT INTO admin_logs')) {
      return { success: true }
    }

    return { success: true }
  }
}

class MockDB {
  bugReports: BugReportRow[] = []
  prepare(sql: string) {
    return new MockStmt(this, sql)
  }
}

describe('support bug reports', () => {
  it('submits bug report to backend storage', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))

    const DB = new MockDB()
    const env: any = { DB }

    const req = new Request('http://localhost/api/v1/support/bug-report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        description: 'Crash when opening settings',
        contactEmail: 'a@b.com',
        walletAddress: 'wallet_1',
        includeDeviceInfo: true,
        deviceInfo: 'Device: x',
        appVersion: '1.0 (1)',
        platform: 'android',
      }),
    })

    const resp = await handleSupportRoutes(req, env, '/api/v1/support/bug-report')
    expect(resp?.status).toBe(200)
    const json = await resp!.json()
    expect(json.success).toBe(true)
    expect(typeof json.id).toBe('string')
    expect(DB.bugReports.length).toBe(1)
    expect(DB.bugReports[0].severity).toBe('UNTRIAGED')
    expect(DB.bugReports[0].estimated_reward).toBe(0)
    expect(DB.bugReports[0].expert_candidate).toBe(0)
  })

  it('admin can list and update bug reports', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-06T00:00:00Z'))

    const DB = new MockDB()
    const env: any = { DB }
    const adminContext: any = { email: 'admin@test', ip: '127.0.0.1', requestId: 'r1' }

    const createReq = new Request('http://localhost/api/v1/support/bug-report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description: 'UI glitch with enough detail', contactEmail: 'a@b.com' }),
    })
    const createResp = await handleSupportRoutes(createReq, env, '/api/v1/support/bug-report')
    const created = await createResp!.json()

    const listReq = new Request('http://localhost/admin/support/bug-reports?limit=50&offset=0', { method: 'GET' })
    const listResp = await handleSupportAdminRoutes(listReq, env, adminContext, '/admin/support/bug-reports')
    expect(listResp?.status).toBe(200)
    const listJson = await listResp!.json()
    expect(listJson.items.length).toBe(1)
    expect(listJson.items[0].id).toBe(created.id)

    const patchReq = new Request(`http://localhost/admin/support/bug-reports/${created.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ severity: 'LOW', status: 'RESOLVED', rewardGranted: 50, expertGranted: false, adminNotes: 'fixed' }),
    })
    const patchResp = await handleSupportAdminRoutes(patchReq, env, adminContext, `/admin/support/bug-reports/${created.id}`)
    expect(patchResp?.status).toBe(200)

    const detailReq = new Request(`http://localhost/admin/support/bug-reports/${created.id}`, { method: 'GET' })
    const detailResp = await handleSupportAdminRoutes(detailReq, env, adminContext, `/admin/support/bug-reports/${created.id}`)
    const detailJson = await detailResp!.json()
    expect(detailJson.item.status).toBe('RESOLVED')
    expect(detailJson.item.rewardGranted).toBe(50)
    expect(detailJson.item.adminNotes).toBe('fixed')
  })
})
