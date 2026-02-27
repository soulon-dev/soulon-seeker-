/**
 * 管理后台 - 数据分析 API
 */

import { AdminContext, logAdminAction } from './middleware'

interface DashboardStats {
  totalUsers: number
  activeUsers: number
  newUsersToday: number
  totalSubscribers: number
  monthlyRevenue: number
  totalTVL: number
  totalStakers: number
  memoriesCount: number
}

interface UserGrowthData {
  date: string
  users: number
  subscribers: number
}

interface RecentActivity {
  id: string
  type: string
  description: string
  timestamp: string
  userId?: string
}

/**
 * 获取仪表盘统计数据
 */
export async function getDashboardStats(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    // 默认值（无数据库时使用）
    const stats: DashboardStats = {
      totalUsers: 0,
      activeUsers: 0,
      newUsersToday: 0,
      totalSubscribers: 0,
      monthlyRevenue: 0,
      totalTVL: 0,
      totalStakers: 0,
      memoriesCount: 0,
    }

    // 从数据库获取真实数据
    if (env.DB) {
      try {
        // 获取用户总数
        const userCount = await env.DB.prepare(
          'SELECT COUNT(*) as count FROM users'
        ).first()
        if (userCount) {
          stats.totalUsers = userCount.count || 0
        }

        // 获取今日新用户
        const todayStart = Math.floor(new Date().setHours(0, 0, 0, 0) / 1000)
        const newUsers = await env.DB.prepare(
          'SELECT COUNT(*) as count FROM users WHERE created_at >= ?'
        ).bind(todayStart).first()
        if (newUsers) {
          stats.newUsersToday = newUsers.count || 0
        }

        // 获取活跃用户（最近7天）
        const weekAgo = Math.floor(Date.now() / 1000) - 7 * 24 * 60 * 60
        const activeUsers = await env.DB.prepare(
          'SELECT COUNT(*) as count FROM users WHERE last_active_at >= ?'
        ).bind(weekAgo).first()
        if (activeUsers) {
          stats.activeUsers = activeUsers.count || 0
        }

        // 获取订阅用户数
        const subscribers = await env.DB.prepare(
          `SELECT COUNT(*) as count FROM users WHERE subscription_type != 'FREE' AND subscription_type IS NOT NULL`
        ).first()
        if (subscribers) {
          stats.totalSubscribers = subscribers.count || 0
        }

        // 获取质押总额和质押者数量
        const stakingStats = await env.DB.prepare(
          `SELECT SUM(staked_amount) as tvl, COUNT(CASE WHEN staked_amount > 0 THEN 1 END) as stakers FROM users`
        ).first()
        if (stakingStats) {
          stats.totalTVL = stakingStats.tvl || 0
          stats.totalStakers = stakingStats.stakers || 0
        }

        // 获取记忆数量
        const memoriesCount = await env.DB.prepare(
          'SELECT COUNT(*) as count FROM memories'
        ).first()
        if (memoriesCount) {
          stats.memoriesCount = memoriesCount.count || 0
        }

        // 获取本月订阅收入
        const monthStart = Math.floor(new Date(new Date().getFullYear(), new Date().getMonth(), 1).getTime() / 1000)
        const revenue = await env.DB.prepare(
          `SELECT SUM(amount) as total FROM subscription_records WHERE start_date >= ? AND status = 'active'`
        ).bind(monthStart).first()
        if (revenue) {
          stats.monthlyRevenue = revenue.total || 0
        }
      } catch (dbError) {
        console.error('Database query error:', dbError)
        // 返回零值
      }
    }

    return new Response(JSON.stringify(stats), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting dashboard stats:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get dashboard stats' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取用户增长数据
 */
export async function getUserGrowth(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const days = parseInt(url.searchParams.get('days') || '30')

  try {
    // 生成模拟数据
    const data: UserGrowthData[] = []
    const now = new Date()

    for (let i = days - 1; i >= 0; i--) {
      const date = new Date(now)
      date.setDate(date.getDate() - i)
      data.push({
        date: date.toISOString().split('T')[0],
        users: Math.floor(10000 + Math.random() * 3000 + i * 50),
        subscribers: Math.floor(1500 + Math.random() * 500 + i * 10),
      })
    }

    return new Response(JSON.stringify(data), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting user growth:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get user growth data' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取最近活动
 */
export async function getRecentActivities(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const limit = parseInt(url.searchParams.get('limit') || '20')

  try {
    const activities: RecentActivity[] = []

    if (env.DB) {
      // 获取最近注册用户
      const recentUsers = await env.DB.prepare(
        `SELECT id, wallet_address, created_at FROM users ORDER BY created_at DESC LIMIT ?`
      ).bind(Math.ceil(limit / 4)).all()
      
      for (const user of (recentUsers.results || [])) {
        const addr = user.wallet_address || ''
        activities.push({
          id: `user_${user.id}`,
          type: 'user_register',
          description: `新用户注册 ${addr.slice(0, 4)}...${addr.slice(-4)}`,
          timestamp: new Date((user.created_at || 0) * 1000).toISOString(),
          userId: user.id,
        })
      }

      // 获取最近订阅记录
      const recentSubs = await env.DB.prepare(
        `SELECT pt.id, pt.user_id, pt.amount, pt.token, pt.created_at, u.wallet_address, u.subscription_type
         FROM payment_transactions pt 
         LEFT JOIN users u ON pt.user_id = u.id
         WHERE pt.type = 'subscription' 
         ORDER BY pt.created_at DESC LIMIT ?`
      ).bind(Math.ceil(limit / 4)).all()
      
      for (const sub of (recentSubs.results || [])) {
        const addr = sub.wallet_address || ''
        const planType = sub.subscription_type === 'YEARLY' ? '年费' : '月费'
        activities.push({
          id: `sub_${sub.id}`,
          type: 'subscription',
          description: `用户 ${addr.slice(0, 4)}...${addr.slice(-4)} 开通${planType}会员 (${sub.amount} ${sub.token || 'SOL'})`,
          timestamp: new Date((sub.created_at || 0) * 1000).toISOString(),
          userId: sub.user_id,
        })
      }

      // 获取最近积分变动
      const recentMemo = await env.DB.prepare(
        `SELECT mt.id, mt.user_id, mt.type, mt.amount, mt.created_at, u.wallet_address 
         FROM memo_transactions mt 
         LEFT JOIN users u ON mt.user_id = u.id
         ORDER BY mt.created_at DESC LIMIT ?`
      ).bind(Math.ceil(limit / 4)).all()
      
      for (const memo of (recentMemo.results || [])) {
        const addr = memo.wallet_address || ''
        activities.push({
          id: `memo_${memo.id}`,
          type: 'memo',
          description: `用户 ${addr.slice(0, 4)}...${addr.slice(-4)} ${memo.amount > 0 ? '获得' : '消耗'} ${Math.abs(memo.amount)} MEMO`,
          timestamp: new Date((memo.created_at || 0) * 1000).toISOString(),
          userId: memo.user_id,
        })
      }

      // 获取最近 AI 调用
      const recentAi = await env.DB.prepare(
        `SELECT al.id, al.user_id, al.function_type, al.total_tokens, al.created_at, u.wallet_address 
         FROM ai_usage_logs al 
         LEFT JOIN users u ON al.user_id = u.id
         ORDER BY al.created_at DESC LIMIT ?`
      ).bind(Math.ceil(limit / 4)).all()
      
      for (const ai of (recentAi.results || [])) {
        const addr = ai.wallet_address || ''
        activities.push({
          id: `ai_${ai.id}`,
          type: 'ai_call',
          description: `用户 ${addr.slice(0, 4)}...${addr.slice(-4)} AI调用 ${ai.function_type} (${ai.total_tokens} tokens)`,
          timestamp: new Date((ai.created_at || 0) * 1000).toISOString(),
          userId: ai.user_id,
        })
      }

      // 按时间排序
      activities.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())
    }

    return new Response(JSON.stringify(activities.slice(0, limit)), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting recent activities:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get recent activities' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

async function getRequestLogs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ records: [] }), { headers: { 'Content-Type': 'application/json' } })
    const url = new URL(request.url)
    const limit = Math.min(200, Math.max(1, parseInt(url.searchParams.get('limit') || '100')))
    const offset = Math.max(0, parseInt(url.searchParams.get('offset') || '0'))
    const statusMin = url.searchParams.get('statusMin')
    const statusMax = url.searchParams.get('statusMax')
    const pathPrefix = (url.searchParams.get('pathPrefix') || '').trim()
    const minDurationMs = url.searchParams.get('minDurationMs')
    const sinceHours = url.searchParams.get('sinceHours')

    const clauses: string[] = []
    const binds: any[] = []

    if (statusMin != null && statusMin !== '') {
      clauses.push('status >= ?')
      binds.push(parseInt(statusMin))
    }
    if (statusMax != null && statusMax !== '') {
      clauses.push('status <= ?')
      binds.push(parseInt(statusMax))
    }
    if (pathPrefix) {
      clauses.push('path LIKE ?')
      binds.push(`${pathPrefix}%`)
    }
    if (minDurationMs != null && minDurationMs !== '') {
      clauses.push('duration_ms >= ?')
      binds.push(parseInt(minDurationMs))
    }
    if (sinceHours != null && sinceHours !== '') {
      const h = Math.max(1, parseInt(sinceHours))
      const since = Math.floor(Date.now() / 1000) - (h * 3600)
      clauses.push('created_at >= ?')
      binds.push(since)
    }

    const where = clauses.length > 0 ? `WHERE ${clauses.join(' AND ')}` : ''
    const sql = `
      SELECT request_id as requestId, path, method, status, duration_ms as durationMs, error_type as errorType, user_identity as userIdentity, ip, created_at as createdAt
      FROM admin_request_logs
      ${where}
      ORDER BY created_at DESC
      LIMIT ? OFFSET ?
    `
    const result = await env.DB.prepare(sql).bind(...binds, limit, offset).all()
    return new Response(JSON.stringify({ records: result.results || [] }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error getting request logs:', error)
    return new Response(JSON.stringify({ error: 'Failed to get request logs' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function getRequestLogStats(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ stats: {} }), { headers: { 'Content-Type': 'application/json' } })
    const url = new URL(request.url)
    const sinceHours = Math.max(1, parseInt(url.searchParams.get('sinceHours') || '24'))
    const since = Math.floor(Date.now() / 1000) - (sinceHours * 3600)

    const byStatus = await env.DB.prepare(
      `SELECT
         CASE
           WHEN status BETWEEN 200 AND 299 THEN '2xx'
           WHEN status BETWEEN 300 AND 399 THEN '3xx'
           WHEN status BETWEEN 400 AND 499 THEN '4xx'
           WHEN status BETWEEN 500 AND 599 THEN '5xx'
           ELSE 'other'
         END as statusGroup,
         COUNT(*) as count
       FROM admin_request_logs
       WHERE created_at >= ?
       GROUP BY statusGroup`
    ).bind(since).all()

    const slow = await env.DB.prepare(
      `SELECT COUNT(*) as count
       FROM admin_request_logs
       WHERE created_at >= ? AND duration_ms >= 1000`
    ).bind(since).first<{ count: number }>()

    const byError = await env.DB.prepare(
      `SELECT COALESCE(error_type, 'none') as errorType, COUNT(*) as count
       FROM admin_request_logs
       WHERE created_at >= ?
       GROUP BY COALESCE(error_type, 'none')
       ORDER BY count DESC
       LIMIT 20`
    ).bind(since).all()

    const stats = {
      sinceHours,
      statusGroups: (byStatus.results || []).reduce((acc: any, r: any) => { acc[r.statusGroup] = r.count; return acc }, {}),
      slowCount: slow?.count || 0,
      errorTypes: byError.results || []
    }

    return new Response(JSON.stringify({ stats }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error getting request log stats:', error)
    return new Response(JSON.stringify({ error: 'Failed to get request log stats' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

/**
 * 处理 analytics 路由
 */
export async function handleAnalyticsRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  if (request.method === 'GET') {
    if (path === '/admin/analytics/stats') {
      return getDashboardStats(request, env, adminContext)
    }
    if (path === '/admin/analytics/user-growth') {
      return getUserGrowth(request, env, adminContext)
    }
    if (path === '/admin/analytics/activities') {
      return getRecentActivities(request, env, adminContext)
    }
    if (path === '/admin/analytics/requests') {
      return getRequestLogs(request, env, adminContext)
    }
    if (path === '/admin/analytics/requests/stats') {
      return getRequestLogStats(request, env, adminContext)
    }
  }

  return null
}
