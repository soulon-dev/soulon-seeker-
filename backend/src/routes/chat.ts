/**
 * 聊天数据 API
 * 用于 App 云端存储聊天会话和消息
 */

import { jsonResponse } from '../index'

// ============================================
// 类型定义
// ============================================

interface ChatSession {
  id: string
  userId: string
  walletAddress: string
  title: string
  createdAt: number
  updatedAt: number
  messageCount: number
  totalTokens: number
  totalMemoEarned: number
}

interface ChatMessage {
  id: string
  sessionId: string
  userId: string
  walletAddress: string
  text: string
  isUser: boolean
  timestamp: number
  tokensUsed: number
  rewardedMemo: number
  isPersonaRelevant: boolean
  relevanceScore: number
  detectedTraits: string[]
  retrievedMemories: string[]
  isError: boolean
  irysTxId?: string
}

interface CreateSessionRequest {
  walletAddress: string
  title?: string
}

interface CreateMessagesRequest {
  walletAddress: string
  sessionId: string
  messages: {
    id: string
    text: string
    isUser: boolean
    timestamp: number
    tokensUsed?: number
    rewardedMemo?: number
    isPersonaRelevant?: boolean
    relevanceScore?: number
    detectedTraits?: string[]
    retrievedMemories?: string[]
    isError?: boolean
  }[]
}

interface SyncSessionsRequest {
  walletAddress: string
  sessions: {
    id: string
    title: string
    createdAt: number
    updatedAt: number
    messageCount: number
  }[]
}

// ============================================
// 辅助函数
// ============================================

function getUserIdFromWallet(walletAddress: string): string {
  return `user_${walletAddress.substring(0, 8)}`
}

// ============================================
// API 处理函数
// ============================================

/**
 * 获取用户所有聊天会话
 */
async function getSessions(request: Request, env: any): Promise<Response> {
  const url = new URL(request.url)
  const walletAddress = url.searchParams.get('wallet')
  const limit = parseInt(url.searchParams.get('limit') || '50')
  const offset = parseInt(url.searchParams.get('offset') || '0')
  const includeDeleted = url.searchParams.get('includeDeleted') === 'true'

  if (!walletAddress) {
    return jsonResponse({ error: 'Missing wallet parameter' }, 400)
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const userId = getUserIdFromWallet(walletAddress)
    
    let query = `
      SELECT id, user_id, wallet_address, title, created_at, updated_at, 
             message_count, total_tokens, total_memo_earned
      FROM chat_sessions 
      WHERE wallet_address = ?
    `
    if (!includeDeleted) {
      query += ' AND is_deleted = 0'
    }
    query += ' ORDER BY updated_at DESC LIMIT ? OFFSET ?'

    const result = await env.DB.prepare(query)
      .bind(walletAddress, limit, offset)
      .all()

    const sessions = (result.results || []).map((row: any) => ({
      id: row.id,
      userId: row.user_id,
      walletAddress: row.wallet_address,
      title: row.title,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
      messageCount: row.message_count,
      totalTokens: row.total_tokens,
      totalMemoEarned: row.total_memo_earned,
    }))

    // 获取总数
    const countResult = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM chat_sessions WHERE wallet_address = ? ${includeDeleted ? '' : 'AND is_deleted = 0'}`
    ).bind(walletAddress).first()

    return jsonResponse({
      sessions,
      total: countResult?.count || 0,
      limit,
      offset,
    })
  } catch (error) {
    console.error('Error getting sessions:', error)
    return jsonResponse({ error: 'Failed to get sessions' }, 500)
  }
}

/**
 * 创建新会话
 */
async function createSession(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as CreateSessionRequest
    const { walletAddress, title } = body

    if (!walletAddress) {
      return jsonResponse({ error: 'Missing walletAddress' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const sessionId = `session_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(`
      INSERT INTO chat_sessions (id, user_id, wallet_address, title, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `).bind(sessionId, userId, walletAddress, title || '新对话', now, now).run()

    return jsonResponse({
      success: true,
      session: {
        id: sessionId,
        userId,
        walletAddress,
        title: title || '新对话',
        createdAt: now,
        updatedAt: now,
        messageCount: 0,
        totalTokens: 0,
        totalMemoEarned: 0,
      }
    })
  } catch (error) {
    console.error('Error creating session:', error)
    return jsonResponse({ error: 'Failed to create session' }, 500)
  }
}

/**
 * 更新会话
 */
async function updateSession(request: Request, env: any, sessionId: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as { title?: string; isDeleted?: boolean }
    const now = Math.floor(Date.now() / 1000)

    const updates: string[] = ['updated_at = ?']
    const params: any[] = [now]

    if (body.title !== undefined) {
      updates.push('title = ?')
      params.push(body.title)
    }
    if (body.isDeleted !== undefined) {
      updates.push('is_deleted = ?')
      params.push(body.isDeleted ? 1 : 0)
    }

    params.push(sessionId)

    await env.DB.prepare(
      `UPDATE chat_sessions SET ${updates.join(', ')} WHERE id = ?`
    ).bind(...params).run()

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating session:', error)
    return jsonResponse({ error: 'Failed to update session' }, 500)
  }
}

/**
 * 删除会话（软删除）
 */
async function deleteSession(request: Request, env: any, sessionId: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const now = Math.floor(Date.now() / 1000)
    
    // 软删除会话
    await env.DB.prepare(
      'UPDATE chat_sessions SET is_deleted = 1, updated_at = ? WHERE id = ?'
    ).bind(now, sessionId).run()

    // 软删除消息
    await env.DB.prepare(
      'UPDATE chat_messages SET is_deleted = 1 WHERE session_id = ?'
    ).bind(sessionId).run()

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error deleting session:', error)
    return jsonResponse({ error: 'Failed to delete session' }, 500)
  }
}

/**
 * 获取会话的消息
 */
async function getMessages(request: Request, env: any, sessionId: string): Promise<Response> {
  const url = new URL(request.url)
  const limit = parseInt(url.searchParams.get('limit') || '100')
  const offset = parseInt(url.searchParams.get('offset') || '0')
  const afterTimestamp = url.searchParams.get('after') // 用于增量同步

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    let query = `
      SELECT id, session_id, user_id, wallet_address, text, is_user, timestamp,
             tokens_used, rewarded_memo, is_persona_relevant, relevance_score,
             detected_traits, retrieved_memories, is_error, irys_tx_id
      FROM chat_messages 
      WHERE session_id = ? AND is_deleted = 0
    `
    const params: any[] = [sessionId]

    if (afterTimestamp) {
      query += ' AND timestamp > ?'
      params.push(parseInt(afterTimestamp))
    }

    query += ' ORDER BY timestamp ASC LIMIT ? OFFSET ?'
    params.push(limit, offset)

    const result = await env.DB.prepare(query).bind(...params).all()

    const messages = (result.results || []).map((row: any) => ({
      id: row.id,
      sessionId: row.session_id,
      userId: row.user_id,
      walletAddress: row.wallet_address,
      text: row.text,
      isUser: !!row.is_user,
      timestamp: row.timestamp,
      tokensUsed: row.tokens_used,
      rewardedMemo: row.rewarded_memo,
      isPersonaRelevant: !!row.is_persona_relevant,
      relevanceScore: row.relevance_score,
      detectedTraits: row.detected_traits ? JSON.parse(row.detected_traits) : [],
      retrievedMemories: row.retrieved_memories ? JSON.parse(row.retrieved_memories) : [],
      isError: !!row.is_error,
      irysTxId: row.irys_tx_id,
    }))

    return jsonResponse({
      messages,
      sessionId,
      count: messages.length,
    })
  } catch (error) {
    console.error('Error getting messages:', error)
    return jsonResponse({ error: 'Failed to get messages' }, 500)
  }
}

/**
 * 批量创建/同步消息
 */
async function createMessages(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as CreateMessagesRequest
    const { walletAddress, sessionId, messages } = body

    if (!walletAddress || !sessionId || !messages || messages.length === 0) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const now = Math.floor(Date.now() / 1000)

    let totalTokens = 0
    let totalMemo = 0

    // 批量插入消息
    for (const msg of messages) {
      await env.DB.prepare(`
        INSERT OR REPLACE INTO chat_messages 
        (id, session_id, user_id, wallet_address, text, is_user, timestamp,
         tokens_used, rewarded_memo, is_persona_relevant, relevance_score,
         detected_traits, retrieved_memories, is_error, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).bind(
        msg.id,
        sessionId,
        userId,
        walletAddress,
        msg.text,
        msg.isUser ? 1 : 0,
        msg.timestamp,
        msg.tokensUsed || 0,
        msg.rewardedMemo || 0,
        msg.isPersonaRelevant ? 1 : 0,
        msg.relevanceScore || 0,
        JSON.stringify(msg.detectedTraits || []),
        JSON.stringify(msg.retrievedMemories || []),
        msg.isError ? 1 : 0,
        now
      ).run()

      totalTokens += msg.tokensUsed || 0
      totalMemo += msg.rewardedMemo || 0
    }

    // 更新会话统计
    await env.DB.prepare(`
      UPDATE chat_sessions 
      SET message_count = message_count + ?,
          total_tokens = total_tokens + ?,
          total_memo_earned = total_memo_earned + ?,
          updated_at = ?
      WHERE id = ?
    `).bind(messages.length, totalTokens, totalMemo, now, sessionId).run()

    return jsonResponse({
      success: true,
      count: messages.length,
      totalTokens,
      totalMemo,
    })
  } catch (error) {
    console.error('Error creating messages:', error)
    return jsonResponse({ error: 'Failed to create messages' }, 500)
  }
}

/**
 * 批量同步会话（用于初始迁移）
 */
async function syncSessions(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as SyncSessionsRequest
    const { walletAddress, sessions } = body

    if (!walletAddress || !sessions) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    let synced = 0

    for (const session of sessions) {
      // 检查会话是否存在
      const existing = await env.DB.prepare(
        'SELECT id FROM chat_sessions WHERE id = ?'
      ).bind(session.id).first()

      if (existing) {
        // 更新现有会话
        await env.DB.prepare(`
          UPDATE chat_sessions SET
            title = ?,
            updated_at = ?,
            message_count = ?
          WHERE id = ?
        `).bind(session.title, session.updatedAt, session.messageCount, session.id).run()
      } else {
        // 创建新会话
        await env.DB.prepare(`
          INSERT INTO chat_sessions (id, user_id, wallet_address, title, created_at, updated_at, message_count)
          VALUES (?, ?, ?, ?, ?, ?, ?)
        `).bind(
          session.id, userId, walletAddress, session.title,
          session.createdAt, session.updatedAt, session.messageCount
        ).run()
      }
      synced++
    }

    return jsonResponse({
      success: true,
      synced,
    })
  } catch (error) {
    console.error('Error syncing sessions:', error)
    return jsonResponse({ error: 'Failed to sync sessions' }, 500)
  }
}

// ============================================
// 路由处理
// ============================================

export async function handleChatRoutes(
  request: Request,
  env: any,
  path: string
): Promise<Response | null> {
  // GET /api/v1/chat/sessions - 获取会话列表
  if (request.method === 'GET' && path === '/api/v1/chat/sessions') {
    return getSessions(request, env)
  }

  // POST /api/v1/chat/sessions - 创建会话
  if (request.method === 'POST' && path === '/api/v1/chat/sessions') {
    return createSession(request, env)
  }

  // POST /api/v1/chat/sessions/sync - 批量同步会话
  if (request.method === 'POST' && path === '/api/v1/chat/sessions/sync') {
    return syncSessions(request, env)
  }

  // PUT /api/v1/chat/sessions/:id - 更新会话
  const updateMatch = path.match(/^\/api\/v1\/chat\/sessions\/([^/]+)$/)
  if (request.method === 'PUT' && updateMatch) {
    return updateSession(request, env, updateMatch[1])
  }

  // DELETE /api/v1/chat/sessions/:id - 删除会话
  if (request.method === 'DELETE' && updateMatch) {
    return deleteSession(request, env, updateMatch[1])
  }

  // GET /api/v1/chat/sessions/:id/messages - 获取消息
  const messagesMatch = path.match(/^\/api\/v1\/chat\/sessions\/([^/]+)\/messages$/)
  if (request.method === 'GET' && messagesMatch) {
    return getMessages(request, env, messagesMatch[1])
  }

  // POST /api/v1/chat/messages - 批量创建消息
  if (request.method === 'POST' && path === '/api/v1/chat/messages') {
    return createMessages(request, env)
  }

  return null
}
