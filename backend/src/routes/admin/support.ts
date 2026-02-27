import { AdminContext, logAdminAction } from './middleware'

function jsonResponse(data: any, status: number = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function clampInt(v: string | null, def: number, min: number, max: number): number {
  const n = v ? parseInt(v, 10) : def
  if (Number.isNaN(n)) return def
  return Math.max(min, Math.min(max, n))
}

export async function handleSupportAdminRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  if (request.method === 'GET' && path === '/admin/support/bug-reports') {
    try {
      const url = new URL(request.url)
      const status = url.searchParams.get('status')
      const severity = url.searchParams.get('severity')
      const wallet = url.searchParams.get('wallet')
      const limit = clampInt(url.searchParams.get('limit'), 50, 1, 200)
      const offset = clampInt(url.searchParams.get('offset'), 0, 0, 10_000)

      const where: string[] = ['1=1']
      const params: any[] = []

      if (status) {
        where.push('status = ?')
        params.push(status)
      }
      if (severity) {
        where.push('severity = ?')
        params.push(severity)
      }
      if (wallet) {
        where.push('wallet_address = ?')
        params.push(wallet)
      }

      const sql =
        `SELECT id, wallet_address as walletAddress, contact_email as contactEmail, severity, status,
                estimated_reward as estimatedReward, expert_candidate as expertCandidate,
                reward_granted as rewardGranted, expert_granted as expertGranted,
                created_at as createdAt, updated_at as updatedAt
         FROM support_bug_reports
         WHERE ${where.join(' AND ')}
         ORDER BY created_at DESC
         LIMIT ? OFFSET ?`

      const result = await env.DB.prepare(sql).bind(...params, limit, offset).all()
      const items = (result.results || []).map((r: any) => ({
        id: r.id,
        walletAddress: r.walletAddress,
        contactEmail: r.contactEmail,
        severity: r.severity,
        status: r.status,
        estimatedReward: r.estimatedReward,
        expertCandidate: !!r.expertCandidate,
        rewardGranted: r.rewardGranted,
        expertGranted: !!r.expertGranted,
        createdAt: r.createdAt,
        updatedAt: r.updatedAt,
      }))

      return jsonResponse({ items, limit, offset })
    } catch (error) {
      console.error('Error listing bug reports:', error)
      return jsonResponse({ error: 'Failed to list bug reports' }, 500)
    }
  }

  const detailMatch = path.match(/^\/admin\/support\/bug-reports\/([^/]+)$/)
  if (request.method === 'GET' && detailMatch) {
    try {
      const id = detailMatch[1]
      const row = await env.DB.prepare(
        `SELECT id, wallet_address as walletAddress, contact_email as contactEmail, severity, description,
                include_device_info as includeDeviceInfo, device_info as deviceInfo, app_version as appVersion,
                platform, status, estimated_reward as estimatedReward, expert_candidate as expertCandidate,
                reward_granted as rewardGranted, expert_granted as expertGranted, admin_notes as adminNotes,
                created_at as createdAt, updated_at as updatedAt, processed_at as processedAt
         FROM support_bug_reports
         WHERE id = ?
         LIMIT 1`
      ).bind(id).first()

      if (!row) {
        return jsonResponse({ error: 'Not Found' }, 404)
      }

      return jsonResponse({
        item: {
          id: (row as any).id,
          walletAddress: (row as any).walletAddress,
          contactEmail: (row as any).contactEmail,
          severity: (row as any).severity,
          description: (row as any).description,
          includeDeviceInfo: !!(row as any).includeDeviceInfo,
          deviceInfo: (row as any).deviceInfo,
          appVersion: (row as any).appVersion,
          platform: (row as any).platform,
          status: (row as any).status,
          estimatedReward: (row as any).estimatedReward,
          expertCandidate: !!(row as any).expertCandidate,
          rewardGranted: (row as any).rewardGranted,
          expertGranted: !!(row as any).expertGranted,
          adminNotes: (row as any).adminNotes,
          createdAt: (row as any).createdAt,
          updatedAt: (row as any).updatedAt,
          processedAt: (row as any).processedAt,
        },
      })
    } catch (error) {
      console.error('Error getting bug report:', error)
      return jsonResponse({ error: 'Failed to get bug report' }, 500)
    }
  }

  if (request.method === 'PATCH' && detailMatch) {
    try {
      const id = detailMatch[1]
      const body = (await request.json()) as any

      const now = Math.floor(Date.now() / 1000)
      const severity = body?.severity != null ? String(body.severity) : null
      const status = body?.status != null ? String(body.status) : null
      const adminNotes = body?.adminNotes != null ? String(body.adminNotes) : null
      const rewardGranted = body?.rewardGranted != null ? Number(body.rewardGranted) : null
      const expertGranted = body?.expertGranted != null ? (body.expertGranted ? 1 : 0) : null

      const updates: string[] = []
      const params: any[] = []

      if (severity !== null) {
        updates.push('severity = ?')
        params.push(severity)
      }
      if (status !== null) {
        updates.push('status = ?')
        params.push(status)
        if (status === 'RESOLVED' || status === 'CLOSED') {
          updates.push('processed_at = ?')
          params.push(now)
        }
      }
      if (adminNotes !== null) {
        updates.push('admin_notes = ?')
        params.push(adminNotes)
      }
      if (rewardGranted !== null && !Number.isNaN(rewardGranted)) {
        updates.push('reward_granted = ?')
        params.push(Math.floor(rewardGranted))
      }
      if (expertGranted !== null) {
        updates.push('expert_granted = ?')
        params.push(expertGranted)
      }

      if (!updates.length) {
        return jsonResponse({ error: 'No updates' }, 400)
      }

      updates.push('updated_at = ?')
      params.push(now)

      const res = await env.DB.prepare(
        `UPDATE support_bug_reports
         SET ${updates.join(', ')}
         WHERE id = ?`
      ).bind(...params, id).run()

      await logAdminAction(env, adminContext, 'UPDATE_BUG_REPORT', 'bug_report', id, 'update')

      return jsonResponse({ success: true, result: res })
    } catch (error) {
      console.error('Error updating bug report:', error)
      return jsonResponse({ error: 'Failed to update bug report' }, 500)
    }
  }

  return null
}
