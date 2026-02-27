/**
 * 管理后台 - 内容审核 API
 * 所有数据从 D1 数据库读取
 */

import { AdminContext, logAdminAction } from './middleware'

/**
 * 获取记忆列表
 */
export async function getMemories(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '10')
  const isFlagged = url.searchParams.get('isFlagged')
  const userId = url.searchParams.get('userId')

  try {
    if (env.DB) {
      let whereClause = '1=1'
      const params: any[] = []
      
      if (isFlagged !== null && isFlagged !== '') {
        whereClause += ' AND is_flagged = ?'
        params.push(isFlagged === 'true' ? 1 : 0)
      }
      if (userId) {
        whereClause += ' AND user_id = ?'
        params.push(userId)
      }

      const countResult = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM memories WHERE ${whereClause}`
      ).bind(...params).first()
      const total = countResult?.count || 0

      const offset = (page - 1) * pageSize
      const dataParams = [...params, pageSize, offset]
      const result = await env.DB.prepare(
        `SELECT * FROM memories WHERE ${whereClause} ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(...dataParams).all()

      const records = (result.results || []).map((row: any) => ({
        id: row.id,
        userId: row.user_id,
        walletAddress: row.wallet_address,
        type: row.type || 'text',
        preview: `[加密记忆 - Irys ID: ${row.irys_id?.substring(0, 12)}...]`,
        size: row.size || 0,
        createdAt: new Date(row.created_at * 1000).toISOString(),
        isFlagged: !!row.is_flagged,
        flagReason: row.flag_reason,
      }))

      return new Response(JSON.stringify({ records, total, page, pageSize }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    return new Response(JSON.stringify({ records: [], total: 0, page, pageSize }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting memories:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get memories' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取聊天日志列表
 */
export async function getChatLogs(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '10')
  const isFlagged = url.searchParams.get('isFlagged')
  const userId = url.searchParams.get('userId')

  try {
    if (env.DB) {
      let whereClause = '1=1'
      const params: any[] = []
      
      if (isFlagged !== null && isFlagged !== '') {
        whereClause += ' AND is_flagged = ?'
        params.push(isFlagged === 'true' ? 1 : 0)
      }
      if (userId) {
        whereClause += ' AND user_id = ?'
        params.push(userId)
      }

      const countResult = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM chat_logs WHERE ${whereClause}`
      ).bind(...params).first()
      const total = countResult?.count || 0

      const offset = (page - 1) * pageSize
      const dataParams = [...params, pageSize, offset]
      const result = await env.DB.prepare(
        `SELECT * FROM chat_logs WHERE ${whereClause} ORDER BY timestamp DESC LIMIT ? OFFSET ?`
      ).bind(...dataParams).all()

      const records = (result.results || []).map((row: any) => ({
        id: row.id,
        userId: row.user_id,
        walletAddress: row.wallet_address,
        userMessage: row.user_message_preview || '[隐私保护]',
        assistantMessage: '[AI 回复 - 已脱敏]',
        timestamp: new Date(row.timestamp * 1000).toISOString(),
        tokensUsed: row.tokens_used || 0,
        isFlagged: !!row.is_flagged,
        flagReason: row.flag_reason,
      }))

      return new Response(JSON.stringify({ records, total, page, pageSize }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    return new Response(JSON.stringify({ records: [], total: 0, page, pageSize }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting chat logs:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get chat logs' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 获取内容统计
 */
export async function getContentStats(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const stats = {
      totalMemories: 0,
      totalChats: 0,
      flaggedMemories: 0,
      flaggedChats: 0,
      todayMemories: 0,
      todayChats: 0,
    }

    if (env.DB) {
      // 记忆统计
      const memoryStats = await env.DB.prepare(
        `SELECT COUNT(*) as total, SUM(CASE WHEN is_flagged = 1 THEN 1 ELSE 0 END) as flagged FROM memories`
      ).first()
      stats.totalMemories = memoryStats?.total || 0
      stats.flaggedMemories = memoryStats?.flagged || 0

      // 聊天统计
      const chatStats = await env.DB.prepare(
        `SELECT COUNT(*) as total, SUM(CASE WHEN is_flagged = 1 THEN 1 ELSE 0 END) as flagged FROM chat_logs`
      ).first()
      stats.totalChats = chatStats?.total || 0
      stats.flaggedChats = chatStats?.flagged || 0

      // 今日统计
      const todayStart = Math.floor(new Date().setHours(0, 0, 0, 0) / 1000)
      
      const todayMemories = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM memories WHERE created_at >= ?`
      ).bind(todayStart).first()
      stats.todayMemories = todayMemories?.count || 0

      const todayChats = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM chat_logs WHERE timestamp >= ?`
      ).bind(todayStart).first()
      stats.todayChats = todayChats?.count || 0
    }

    return new Response(JSON.stringify(stats), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting content stats:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get content stats' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 标记记忆
 */
export async function flagMemory(
  request: Request,
  env: any,
  adminContext: AdminContext,
  memoryId: string
): Promise<Response> {
  try {
    const body = await request.json() as { reason: string }

    if (env.DB) {
      await env.DB.prepare(
        `UPDATE memories SET is_flagged = 1, flag_reason = ? WHERE id = ?`
      ).bind(body.reason, memoryId).run()
    }

    await logAdminAction(env, adminContext, 'FLAG_MEMORY', 'memory', memoryId, `标记原因: ${body.reason}`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error flagging memory:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to flag memory' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 取消标记记忆
 */
export async function unflagMemory(
  request: Request,
  env: any,
  adminContext: AdminContext,
  memoryId: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare(
        `UPDATE memories SET is_flagged = 0, flag_reason = NULL WHERE id = ?`
      ).bind(memoryId).run()
    }

    await logAdminAction(env, adminContext, 'UNFLAG_MEMORY', 'memory', memoryId, `取消标记`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error unflagging memory:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to unflag memory' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 删除记忆
 */
export async function deleteMemory(
  request: Request,
  env: any,
  adminContext: AdminContext,
  memoryId: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM memories WHERE id = ?').bind(memoryId).run()
    }

    await logAdminAction(env, adminContext, 'DELETE_MEMORY', 'memory', memoryId, '删除记忆')

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error deleting memory:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to delete memory' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 标记聊天
 */
export async function flagChat(
  request: Request,
  env: any,
  adminContext: AdminContext,
  chatId: string
): Promise<Response> {
  try {
    const body = await request.json() as { reason: string }

    if (env.DB) {
      await env.DB.prepare(
        `UPDATE chat_logs SET is_flagged = 1, flag_reason = ? WHERE id = ?`
      ).bind(body.reason, chatId).run()
    }

    await logAdminAction(env, adminContext, 'FLAG_CHAT', 'chat', chatId, `标记原因: ${body.reason}`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error flagging chat:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to flag chat' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * 处理内容管理路由
 */
export async function handleContentRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string
): Promise<Response | null> {
  if (request.method === 'GET' && path === '/admin/content/memories') {
    return getMemories(request, env, adminContext)
  }

  if (request.method === 'GET' && path === '/admin/content/chats') {
    return getChatLogs(request, env, adminContext)
  }

  if (request.method === 'GET' && path === '/admin/content/stats') {
    return getContentStats(request, env, adminContext)
  }

  const flagMatch = path.match(/^\/admin\/content\/memories\/([^/]+)\/flag$/)
  if (request.method === 'POST' && flagMatch) {
    return flagMemory(request, env, adminContext, flagMatch[1])
  }

  const unflagMatch = path.match(/^\/admin\/content\/memories\/([^/]+)\/unflag$/)
  if (request.method === 'POST' && unflagMatch) {
    return unflagMemory(request, env, adminContext, unflagMatch[1])
  }

  const deleteMatch = path.match(/^\/admin\/content\/memories\/([^/]+)$/)
  if (request.method === 'DELETE' && deleteMatch) {
    return deleteMemory(request, env, adminContext, deleteMatch[1])
  }

  const flagChatMatch = path.match(/^\/admin\/content\/chats\/([^/]+)\/flag$/)
  if (request.method === 'POST' && flagChatMatch) {
    return flagChat(request, env, adminContext, flagChatMatch[1])
  }

  return null
}
