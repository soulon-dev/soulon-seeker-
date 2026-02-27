/**
 * 管理后台 - 系统配置 API
 * 所有数据从 D1 数据库和 KV 读取
 */

import { AdminContext, logAdminAction } from './middleware'

/**
 * 获取系统配置列表
 */
export async function getSystemConfigs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (env.DB) {
      const result = await env.DB.prepare(
        'SELECT * FROM system_config ORDER BY key ASC'
      ).all()

      if (result.results) {
        const configs = result.results.map((row: any) => ({
          key: row.key,
          value: row.value,
          description: row.description,
          updatedAt: new Date(row.updated_at * 1000).toISOString(),
        }))
        return new Response(JSON.stringify(configs), {
          headers: { 'Content-Type': 'application/json' },
        })
      }
    }

    return new Response(JSON.stringify([]), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting system configs:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get system configs' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 更新系统配置
 */
export async function updateSystemConfig(
  request: Request,
  env: any,
  adminContext: AdminContext,
  key: string
): Promise<Response> {
  try {
    const body = await request.json() as { value: string }
    const now = Math.floor(Date.now() / 1000)

    if (env.DB) {
      // 检查是否存在
      const existing = await env.DB.prepare(
        'SELECT key FROM system_config WHERE key = ?'
      ).bind(key).first()

      if (existing) {
        await env.DB.prepare(
          'UPDATE system_config SET value = ?, updated_at = ? WHERE key = ?'
        ).bind(body.value, now, key).run()
      } else {
        await env.DB.prepare(
          'INSERT INTO system_config (key, value, updated_at) VALUES (?, ?, ?)'
        ).bind(key, body.value, now).run()
      }
    }

    // 同时更新 KV（用于快速读取）
    if (env.KV) {
      await env.KV.put(`config:${key}`, body.value)
    }

    await logAdminAction(env, adminContext, 'UPDATE_CONFIG', 'config', key, `更新值为: ${body.value}`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error updating system config:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to update system config' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取功能开关列表
 */
export async function getFeatureFlags(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (env.DB) {
      const result = await env.DB.prepare(
        'SELECT * FROM feature_flags ORDER BY key ASC'
      ).all()

      if (result.results) {
        const features = result.results.map((row: any) => ({
          key: row.key,
          enabled: !!row.enabled,
          description: row.description,
          updatedAt: new Date(row.updated_at * 1000).toISOString(),
        }))
        return new Response(JSON.stringify(features), {
          headers: { 'Content-Type': 'application/json' },
        })
      }
    }

    return new Response(JSON.stringify([]), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting feature flags:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get feature flags' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 更新功能开关
 */
export async function updateFeatureFlag(
  request: Request,
  env: any,
  adminContext: AdminContext,
  key: string
): Promise<Response> {
  try {
    const body = await request.json() as { enabled: boolean }
    const now = Math.floor(Date.now() / 1000)

    if (env.DB) {
      const existing = await env.DB.prepare(
        'SELECT key FROM feature_flags WHERE key = ?'
      ).bind(key).first()

      if (existing) {
        await env.DB.prepare(
          'UPDATE feature_flags SET enabled = ?, updated_at = ? WHERE key = ?'
        ).bind(body.enabled ? 1 : 0, now, key).run()
      } else {
        await env.DB.prepare(
          'INSERT INTO feature_flags (key, enabled, updated_at) VALUES (?, ?, ?)'
        ).bind(key, body.enabled ? 1 : 0, now).run()
      }
    }

    // 同时更新 KV
    if (env.KV) {
      await env.KV.put(`feature:${key}`, body.enabled.toString())
    }

    await logAdminAction(env, adminContext, 'UPDATE_FEATURE', 'feature', key, `${body.enabled ? '启用' : '禁用'}功能`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error updating feature flag:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to update feature flag' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取管理员操作日志
 */
export async function getAdminLogs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '10')
  const action = url.searchParams.get('action') || ''
  const adminEmail = url.searchParams.get('adminEmail') || ''

  try {
    if (env.DB) {
      let whereClause = '1=1'
      const params: any[] = []

      if (action) {
        whereClause += ' AND action = ?'
        params.push(action)
      }
      if (adminEmail) {
        whereClause += ' AND admin_email LIKE ?'
        params.push(`%${adminEmail}%`)
      }

      const countResult = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM admin_logs WHERE ${whereClause}`
      ).bind(...params).first()
      const total = countResult?.count || 0

      const offset = (page - 1) * pageSize
      const dataParams = [...params, pageSize, offset]
      const result = await env.DB.prepare(
        `SELECT * FROM admin_logs WHERE ${whereClause} ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(...dataParams).all()

      const logs = (result.results || []).map((row: any) => ({
        id: row.id,
        adminEmail: row.admin_email,
        action: row.action,
        targetType: row.target_type,
        targetId: row.target_id,
        details: row.details,
        ipAddress: row.ip_address,
        createdAt: new Date(row.created_at * 1000).toISOString(),
      }))

      return new Response(JSON.stringify({ logs, total, page, pageSize }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    return new Response(JSON.stringify({ logs: [], total: 0, page, pageSize }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting admin logs:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get admin logs' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 导出数据
 */
export async function exportData(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as { type: string }
    const { type } = body

    await logAdminAction(env, adminContext, 'EXPORT_DATA', 'export', type, `导出 ${type} 数据`)

    // 生成导出数据
    let data: any[] = []
    
    if (env.DB) {
      switch (type) {
        case 'users':
          const users = await env.DB.prepare('SELECT * FROM users ORDER BY created_at DESC').all()
          data = users.results || []
          break
        case 'subscriptions':
          const subs = await env.DB.prepare('SELECT * FROM subscription_records ORDER BY created_at DESC').all()
          data = subs.results || []
          break
        case 'staking':
          const stakes = await env.DB.prepare('SELECT * FROM staking_records ORDER BY created_at DESC').all()
          data = stakes.results || []
          break
      }
    }

    // 返回 JSON 格式（实际生产环境应上传到 R2 并返回下载链接）
    return new Response(JSON.stringify({ 
      success: true,
      type,
      count: data.length,
      data,
      exportedAt: new Date().toISOString()
    }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error exporting data:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to export data' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 处理配置管理路由
 */
export async function handleConfigRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  if (request.method === 'GET' && path === '/admin/config/settings') {
    return getSystemConfigs(request, env, adminContext)
  }

  const settingsMatch = path.match(/^\/admin\/config\/settings\/([^/]+)$/)
  if (request.method === 'PUT' && settingsMatch) {
    return updateSystemConfig(request, env, adminContext, settingsMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/config/features') {
    return getFeatureFlags(request, env, adminContext)
  }

  const featuresMatch = path.match(/^\/admin\/config\/features\/([^/]+)$/)
  if (request.method === 'PUT' && featuresMatch) {
    return updateFeatureFlag(request, env, adminContext, featuresMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/config/logs') {
    return getAdminLogs(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/config/export') {
    return exportData(request, env, adminContext)
  }

  return null
}
