/**
 * 管理后台 - 质押管理 API
 * 所有数据从 D1 数据库读取
 */

import { AdminContext, logAdminAction } from './middleware'

interface StakingProject {
  id: string
  name: string
  token: string
  icon: string
  apy: number
  tvl: string
  minStake: string
  maxStake: string
  description: string
  longDescription: string
  status: 'ACTIVE' | 'COMING_SOON' | 'ENDED' | 'FULL'
  lockPeriodDays: number
  riskLevel: string
  features: string[]
  participants: number
  createdAt: string
  updatedAt: string
}

interface StakingRecord {
  id: string
  userId: string
  walletAddress: string
  projectId: string
  projectName: string
  amount: number
  token: string
  startTime: string
  unlockTime: string
  status: string
  rewards: number
  createdAt: string
}

/**
 * 获取质押项目列表
 */
export async function getStakingProjects(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (env.DB) {
      // 检查表是否存在
      try {
        await env.DB.prepare('SELECT 1 FROM staking_projects LIMIT 1').first()
      } catch (e) {
        // 如果表不存在，返回空数组而不是报错
        console.warn('staking_projects table does not exist')
        return new Response(JSON.stringify([]), {
          headers: { 'Content-Type': 'application/json' },
        })
      }

      const result = await env.DB.prepare(
        'SELECT * FROM staking_projects ORDER BY created_at DESC'
      ).all()
      
      if (result.results) {
        const projects = result.results.map((row: any) => ({
          id: row.id,
          name: row.name,
          token: row.token,
          icon: row.icon,
          apy: row.apy,
          tvl: row.tvl,
          minStake: row.min_stake,
          maxStake: row.max_stake,
          description: row.description,
          longDescription: row.long_description,
          status: row.status,
          lockPeriodDays: row.lock_period_days,
          riskLevel: row.risk_level,
          features: row.features ? JSON.parse(row.features) : [],
          participants: row.participants || 0,
          createdAt: new Date(row.created_at * 1000).toISOString(),
          updatedAt: new Date(row.updated_at * 1000).toISOString(),
        }))
        return new Response(JSON.stringify(projects), {
          headers: { 'Content-Type': 'application/json' },
        })
      }
    }

    return new Response(JSON.stringify([]), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting staking projects:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get staking projects' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 创建质押项目
 */
export async function createStakingProject(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as Omit<StakingProject, 'id' | 'participants' | 'createdAt' | 'updatedAt'>
    const projectId = `project_${Date.now()}`
    const now = Math.floor(Date.now() / 1000)

    if (env.DB) {
      await env.DB.prepare(
        `INSERT INTO staking_projects (id, name, token, icon, apy, tvl, min_stake, max_stake, description, long_description, status, lock_period_days, risk_level, features, participants, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)`
      ).bind(
        projectId,
        body.name,
        body.token,
        body.icon,
        body.apy,
        body.tvl,
        body.minStake,
        body.maxStake,
        body.description,
        body.longDescription || '',
        body.status,
        body.lockPeriodDays,
        body.riskLevel,
        JSON.stringify(body.features || []),
        now,
        now
      ).run()
    }

    await logAdminAction(env, adminContext, 'CREATE_STAKING_PROJECT', 'staking_project', projectId, `创建质押项目: ${body.name}`)

    return new Response(JSON.stringify({ 
      ...body, 
      id: projectId,
      participants: 0,
      createdAt: new Date(now * 1000).toISOString(),
      updatedAt: new Date(now * 1000).toISOString()
    }), {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error creating staking project:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to create staking project' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 更新质押项目
 */
export async function updateStakingProject(
  request: Request,
  env: any,
  adminContext: AdminContext,
  projectId: string
): Promise<Response> {
  try {
    const body = await request.json() as Partial<StakingProject>
    const now = Math.floor(Date.now() / 1000)

    if (env.DB) {
      const updates: string[] = []
      const values: any[] = []

      if (body.name !== undefined) { updates.push('name = ?'); values.push(body.name) }
      if (body.apy !== undefined) { updates.push('apy = ?'); values.push(body.apy) }
      if (body.tvl !== undefined) { updates.push('tvl = ?'); values.push(body.tvl) }
      if (body.status !== undefined) { updates.push('status = ?'); values.push(body.status) }
      if (body.lockPeriodDays !== undefined) { updates.push('lock_period_days = ?'); values.push(body.lockPeriodDays) }
      if (body.riskLevel !== undefined) { updates.push('risk_level = ?'); values.push(body.riskLevel) }
      if (body.features !== undefined) { updates.push('features = ?'); values.push(JSON.stringify(body.features)) }
      if (body.minStake !== undefined) { updates.push('min_stake = ?'); values.push(body.minStake) }
      if (body.maxStake !== undefined) { updates.push('max_stake = ?'); values.push(body.maxStake) }
      if (body.description !== undefined) { updates.push('description = ?'); values.push(body.description) }

      if (updates.length > 0) {
        updates.push('updated_at = ?')
        values.push(now)
        values.push(projectId)
        await env.DB.prepare(`UPDATE staking_projects SET ${updates.join(', ')} WHERE id = ?`).bind(...values).run()
      }
    }

    await logAdminAction(env, adminContext, 'UPDATE_STAKING_PROJECT', 'staking_project', projectId, `更新质押项目`)

    return new Response(JSON.stringify({ ...body, id: projectId, updatedAt: new Date(now * 1000).toISOString() }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error updating staking project:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to update staking project' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 删除质押项目
 */
export async function deleteStakingProject(
  request: Request,
  env: any,
  adminContext: AdminContext,
  projectId: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM staking_projects WHERE id = ?').bind(projectId).run()
    }

    await logAdminAction(env, adminContext, 'DELETE_STAKING_PROJECT', 'staking_project', projectId, `删除质押项目`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error deleting staking project:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to delete staking project' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取质押记录列表
 */
export async function getStakingRecords(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '10')
  const projectId = url.searchParams.get('projectId') || ''
  const status = url.searchParams.get('status') || ''

  try {
    if (env.DB) {
      // 检查表是否存在
      try {
        await env.DB.prepare('SELECT 1 FROM staking_records LIMIT 1').first()
      } catch (e) {
        return new Response(JSON.stringify({ records: [], total: 0, page, pageSize }), {
          headers: { 'Content-Type': 'application/json' },
        })
      }

      let whereClause = '1=1'
      const params: any[] = []
      
      if (projectId) {
        whereClause += ' AND sr.project_id = ?'
        params.push(projectId)
      }
      if (status) {
        whereClause += ' AND sr.status = ?'
        params.push(status)
      }

      const countResult = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM staking_records sr WHERE ${whereClause}`
      ).bind(...params).first()
      const total = countResult?.count || 0

      const offset = (page - 1) * pageSize
      const dataParams = [...params, pageSize, offset]
      const result = await env.DB.prepare(
        `SELECT sr.*, sp.name as project_name, sp.token 
         FROM staking_records sr 
         LEFT JOIN staking_projects sp ON sr.project_id = sp.id 
         WHERE ${whereClause} 
         ORDER BY sr.created_at DESC 
         LIMIT ? OFFSET ?`
      ).bind(...dataParams).all()

      const records = (result.results || []).map((row: any) => ({
        id: row.id,
        userId: row.user_id,
        walletAddress: row.wallet_address,
        projectId: row.project_id,
        projectName: row.project_name || row.project_id,
        amount: row.amount,
        token: row.token || 'MEMO',
        startTime: new Date(row.start_time * 1000).toISOString(),
        unlockTime: new Date(row.unlock_time * 1000).toISOString(),
        status: row.status,
        rewards: row.rewards || 0,
        createdAt: new Date(row.created_at * 1000).toISOString(),
      }))

      return new Response(JSON.stringify({ records, total, page, pageSize }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    return new Response(JSON.stringify({ records: [], total: 0, page, pageSize }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting staking records:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get staking records' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取质押统计
 */
export async function getStakingStats(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const stats = {
      totalTVL: 0,
      totalStakers: 0,
      totalRewardsDistributed: 0,
      projectBreakdown: [] as any[],
    }

    if (env.DB) {
      // 从用户表获取总 TVL 和质押者
      const tvlResult = await env.DB.prepare(
        `SELECT SUM(staked_amount) as tvl, COUNT(CASE WHEN staked_amount > 0 THEN 1 END) as stakers FROM users`
      ).first()
      stats.totalTVL = tvlResult?.tvl || 0
      stats.totalStakers = tvlResult?.stakers || 0

      // 从质押记录获取发放奖励
      const rewardsResult = await env.DB.prepare(
        `SELECT SUM(rewards) as total FROM staking_records`
      ).first()
      stats.totalRewardsDistributed = rewardsResult?.total || 0

      // 项目分布
      const breakdownResult = await env.DB.prepare(
        `SELECT sp.id, sp.name, sp.participants,
                (SELECT SUM(amount) FROM staking_records sr WHERE sr.project_id = sp.id AND sr.status = 'active') as tvl
         FROM staking_projects sp`
      ).all()

      if (breakdownResult.results) {
        stats.projectBreakdown = breakdownResult.results.map((row: any) => ({
          projectId: row.id,
          projectName: row.name,
          tvl: row.tvl || 0,
          stakers: row.participants || 0,
        }))
      }
    }

    return new Response(JSON.stringify(stats), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting staking stats:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get staking stats' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 处理质押管理路由
 */
export async function handleStakingRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  if (request.method === 'GET' && path === '/admin/staking/projects') {
    return getStakingProjects(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/staking/projects') {
    return createStakingProject(request, env, adminContext)
  }

  const updateMatch = path.match(/^\/admin\/staking\/projects\/([^/]+)$/)
  if (request.method === 'PUT' && updateMatch) {
    return updateStakingProject(request, env, adminContext, updateMatch[1])
  }

  if (request.method === 'DELETE' && updateMatch) {
    return deleteStakingProject(request, env, adminContext, updateMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/staking/records') {
    return getStakingRecords(request, env, adminContext)
  }

  if (request.method === 'GET' && path === '/admin/staking/stats') {
    return getStakingStats(request, env, adminContext)
  }

  return null
}
