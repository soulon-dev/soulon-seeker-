import type { Env } from '../index'

type BugSeverity = 'UNTRIAGED' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
type BugStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'CLOSED'

function jsonResponse(data: any, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function asSeverity(input: any): BugSeverity {
  const v = String(input || '').toUpperCase()
  if (v === 'LOW' || v === 'MEDIUM' || v === 'HIGH' || v === 'CRITICAL' || v === 'UNTRIAGED') return v
  return 'UNTRIAGED'
}

function isEmailLike(s: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)
}

function calculateEstimatedReward(severity: BugSeverity): { amount: number; expertCandidate: boolean } {
  switch (severity) {
    case 'UNTRIAGED':
      return { amount: 0, expertCandidate: false }
    case 'LOW':
      return { amount: 20, expertCandidate: false }
    case 'MEDIUM':
      return { amount: 50, expertCandidate: false }
    case 'HIGH':
      return { amount: 120, expertCandidate: true }
    case 'CRITICAL':
      return { amount: 300, expertCandidate: true }
  }
}

export async function handleSupportRoutes(
  request: Request,
  env: Env,
  path: string
): Promise<Response | null> {
  if (path === '/api/v1/support/bug-report' && request.method === 'POST') {
    try {
      if (!env.DB) {
        return jsonResponse({ error: 'Database not available' }, 500)
      }

      const body = (await request.json()) as any

      const description = String(body?.description || '').trim()
      if (!description) {
        return jsonResponse({ error: 'Missing description' }, 400)
      }
      if (description.length < 10) {
        return jsonResponse({ error: 'Description too short' }, 400)
      }

      const contactEmail = String(body?.contactEmail || '').trim()
      if (!contactEmail) {
        return jsonResponse({ error: 'Missing contactEmail' }, 400)
      }
      if (!isEmailLike(contactEmail)) {
        return jsonResponse({ error: 'Invalid contactEmail' }, 400)
      }

      const walletAddress = String(body?.walletAddress || '').trim()
      const includeDeviceInfo = !!body?.includeDeviceInfo
      const deviceInfo = String(body?.deviceInfo || '').trim()
      const appVersion = String(body?.appVersion || '').trim()
      const platform = String(body?.platform || 'android').trim()
      const severity: BugSeverity = 'UNTRIAGED'
      const { amount: estimatedReward, expertCandidate } = calculateEstimatedReward(severity)

      const id = crypto.randomUUID()
      const now = Math.floor(Date.now() / 1000)


      await env.DB.prepare(
        `INSERT INTO support_bug_reports (
          id, wallet_address, contact_email, severity, description,
          include_device_info, device_info, app_version, platform,
          status, estimated_reward, expert_candidate,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
        .bind(
          id,
          walletAddress || null,
          contactEmail || null,
          severity,
          description,
          includeDeviceInfo ? 1 : 0,
          deviceInfo || null,
          appVersion || null,
          platform || null,
          'OPEN' as BugStatus,
          estimatedReward,
          expertCandidate ? 1 : 0,
          now,
          now
        )
        .run()

      return jsonResponse({ success: true, id })
    } catch (error) {
      console.error('Bug report submit failed:', error)
      return jsonResponse({ error: 'Failed to submit bug report' }, 500)
    }
  }

  return null
}
