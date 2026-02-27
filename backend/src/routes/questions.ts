/**
 * 主动提问 API（奇遇任务）
 * 用于 App 云端存储主动提问数据
 */

import { jsonResponse } from '../index'

// ============================================
// 类型定义
// ============================================

interface ProactiveQuestion {
  id: string
  userId: string
  walletAddress: string
  questionText: string
  category: string
  subcategory?: string
  status: string
  priority: number
  createdAt: number
  scheduledAt?: number
  notifiedAt?: number
  answeredAt?: number
  expiresAt?: number
  answerText?: string
  personaImpact?: Record<string, number>
  rewardedMemo: number
  contextHint?: string
  followUpQuestionId?: string
}

interface CreateQuestionRequest {
  walletAddress: string
  questionText: string
  category: string
  subcategory?: string
  priority?: number
  scheduledAt?: number
  contextHint?: string
  followUpQuestionId?: string
}

interface AnswerQuestionRequest {
  walletAddress: string
  answerText: string
  personaImpact?: Record<string, number>
  rewardedMemo?: number
}

interface BatchCreateQuestionsRequest {
  walletAddress: string
  questions: {
    id: string
    questionText: string
    category: string
    subcategory?: string
    priority?: number
    scheduledAt?: number
    contextHint?: string
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
 * 获取用户的问题列表
 */
async function getQuestions(request: Request, env: any): Promise<Response> {
  const url = new URL(request.url)
  const walletAddress = url.searchParams.get('wallet')
  const status = url.searchParams.get('status') // PENDING/NOTIFIED/ANSWERED/SKIPPED/EXPIRED
  const limit = parseInt(url.searchParams.get('limit') || '50')
  const offset = parseInt(url.searchParams.get('offset') || '0')

  if (!walletAddress) {
    return jsonResponse({ error: 'Missing wallet parameter' }, 400)
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    let query = `
      SELECT id, user_id, wallet_address, question_text, category, subcategory,
             status, priority, created_at, scheduled_at, notified_at, answered_at,
             expires_at, answer_text, persona_impact, rewarded_memo, context_hint,
             follow_up_question_id
      FROM proactive_questions 
      WHERE wallet_address = ?
    `
    const params: any[] = [walletAddress]

    if (status) {
      query += ' AND status = ?'
      params.push(status)
    }

    query += ' ORDER BY priority DESC, created_at DESC LIMIT ? OFFSET ?'
    params.push(limit, offset)

    const result = await env.DB.prepare(query).bind(...params).all()

    const questions = (result.results || []).map((row: any) => ({
      id: row.id,
      userId: row.user_id,
      walletAddress: row.wallet_address,
      questionText: row.question_text,
      category: row.category,
      subcategory: row.subcategory,
      status: row.status,
      priority: row.priority,
      createdAt: row.created_at,
      scheduledAt: row.scheduled_at,
      notifiedAt: row.notified_at,
      answeredAt: row.answered_at,
      expiresAt: row.expires_at,
      answerText: row.answer_text,
      personaImpact: row.persona_impact ? JSON.parse(row.persona_impact) : null,
      rewardedMemo: row.rewarded_memo || 0,
      contextHint: row.context_hint,
      followUpQuestionId: row.follow_up_question_id,
    }))

    // 获取总数
    let countQuery = 'SELECT COUNT(*) as count FROM proactive_questions WHERE wallet_address = ?'
    const countParams: any[] = [walletAddress]
    if (status) {
      countQuery += ' AND status = ?'
      countParams.push(status)
    }
    const countResult = await env.DB.prepare(countQuery).bind(...countParams).first()

    return jsonResponse({
      questions,
      total: countResult?.count || 0,
      limit,
      offset,
    })
  } catch (error) {
    console.error('Error getting questions:', error)
    return jsonResponse({ error: 'Failed to get questions' }, 500)
  }
}

/**
 * 获取待回答的问题
 */
async function getPendingQuestions(request: Request, env: any): Promise<Response> {
  const url = new URL(request.url)
  const walletAddress = url.searchParams.get('wallet')
  const limit = parseInt(url.searchParams.get('limit') || '10')

  if (!walletAddress) {
    return jsonResponse({ error: 'Missing wallet parameter' }, 400)
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const now = Math.floor(Date.now() / 1000)

    // 获取未过期的待回答问题
    const result = await env.DB.prepare(`
      SELECT id, user_id, wallet_address, question_text, category, subcategory,
             status, priority, created_at, scheduled_at, notified_at, expires_at,
             context_hint, follow_up_question_id
      FROM proactive_questions 
      WHERE wallet_address = ? 
        AND status IN ('PENDING', 'NOTIFIED')
        AND (expires_at IS NULL OR expires_at > ?)
      ORDER BY priority DESC, created_at ASC
      LIMIT ?
    `).bind(walletAddress, now, limit).all()

    const questions = (result.results || []).map((row: any) => ({
      id: row.id,
      userId: row.user_id,
      walletAddress: row.wallet_address,
      questionText: row.question_text,
      category: row.category,
      subcategory: row.subcategory,
      status: row.status,
      priority: row.priority,
      createdAt: row.created_at,
      scheduledAt: row.scheduled_at,
      notifiedAt: row.notified_at,
      expiresAt: row.expires_at,
      contextHint: row.context_hint,
      followUpQuestionId: row.follow_up_question_id,
    }))

    return jsonResponse({
      questions,
      count: questions.length,
    })
  } catch (error) {
    console.error('Error getting pending questions:', error)
    return jsonResponse({ error: 'Failed to get pending questions' }, 500)
  }
}

/**
 * 创建新问题
 */
async function createQuestion(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as CreateQuestionRequest
    const { walletAddress, questionText, category, subcategory, priority,
            scheduledAt, contextHint, followUpQuestionId } = body

    if (!walletAddress || !questionText || !category) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const questionId = `q_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
    const now = Math.floor(Date.now() / 1000)
    const expiresAt = now + (24 * 60 * 60) // 默认 24 小时后过期

    await env.DB.prepare(`
      INSERT INTO proactive_questions 
      (id, user_id, wallet_address, question_text, category, subcategory,
       status, priority, created_at, scheduled_at, expires_at, context_hint,
       follow_up_question_id)
      VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
    `).bind(
      questionId, userId, walletAddress, questionText, category,
      subcategory || null,
      priority || 0,
      now,
      scheduledAt || now,
      expiresAt,
      contextHint || null,
      followUpQuestionId || null
    ).run()

    return jsonResponse({
      success: true,
      question: {
        id: questionId,
        userId,
        walletAddress,
        questionText,
        category,
        subcategory,
        status: 'PENDING',
        priority: priority || 0,
        createdAt: now,
        scheduledAt: scheduledAt || now,
        expiresAt,
      }
    })
  } catch (error) {
    console.error('Error creating question:', error)
    return jsonResponse({ error: 'Failed to create question' }, 500)
  }
}

/**
 * 批量创建问题
 */
async function batchCreateQuestions(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as BatchCreateQuestionsRequest
    const { walletAddress, questions } = body

    if (!walletAddress || !questions || questions.length === 0) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const now = Math.floor(Date.now() / 1000)
    const expiresAt = now + (24 * 60 * 60)
    let created = 0

    for (const q of questions) {
      await env.DB.prepare(`
        INSERT OR REPLACE INTO proactive_questions 
        (id, user_id, wallet_address, question_text, category, subcategory,
         status, priority, created_at, scheduled_at, expires_at, context_hint)
        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
      `).bind(
        q.id, userId, walletAddress, q.questionText, q.category,
        q.subcategory || null,
        q.priority || 0,
        now,
        q.scheduledAt || now,
        expiresAt,
        q.contextHint || null
      ).run()
      created++
    }

    return jsonResponse({
      success: true,
      created,
    })
  } catch (error) {
    console.error('Error batch creating questions:', error)
    return jsonResponse({ error: 'Failed to batch create questions' }, 500)
  }
}

/**
 * 更新问题状态（标记为已通知）
 */
async function markNotified(request: Request, env: any, questionId: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(`
      UPDATE proactive_questions SET
        status = 'NOTIFIED',
        notified_at = ?
      WHERE id = ?
    `).bind(now, questionId).run()

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error marking question as notified:', error)
    return jsonResponse({ error: 'Failed to update question' }, 500)
  }
}

/**
 * 回答问题
 */
async function answerQuestion(request: Request, env: any, questionId: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as AnswerQuestionRequest
    const { answerText, personaImpact, rewardedMemo } = body

    if (!answerText) {
      return jsonResponse({ error: 'Missing answerText' }, 400)
    }

    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(`
      UPDATE proactive_questions SET
        status = 'ANSWERED',
        answered_at = ?,
        answer_text = ?,
        persona_impact = ?,
        rewarded_memo = ?
      WHERE id = ?
    `).bind(
      now,
      answerText,
      personaImpact ? JSON.stringify(personaImpact) : null,
      rewardedMemo || 0,
      questionId
    ).run()

    return jsonResponse({
      success: true,
      answeredAt: now,
      rewardedMemo: rewardedMemo || 0,
    })
  } catch (error) {
    console.error('Error answering question:', error)
    return jsonResponse({ error: 'Failed to answer question' }, 500)
  }
}

/**
 * 跳过问题
 */
async function skipQuestion(request: Request, env: any, questionId: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    await env.DB.prepare(`
      UPDATE proactive_questions SET status = 'SKIPPED' WHERE id = ?
    `).bind(questionId).run()

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error skipping question:', error)
    return jsonResponse({ error: 'Failed to skip question' }, 500)
  }
}

/**
 * 获取今日统计
 */
async function getTodayStats(request: Request, env: any): Promise<Response> {
  const url = new URL(request.url)
  const walletAddress = url.searchParams.get('wallet')

  if (!walletAddress) {
    return jsonResponse({ error: 'Missing wallet parameter' }, 400)
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    // 今日开始时间戳
    const todayStart = Math.floor(new Date().setHours(0, 0, 0, 0) / 1000)

    // 今日已通知数量
    const notifiedResult = await env.DB.prepare(`
      SELECT COUNT(*) as count FROM proactive_questions 
      WHERE wallet_address = ? AND notified_at >= ?
    `).bind(walletAddress, todayStart).first()

    // 今日已回答数量
    const answeredResult = await env.DB.prepare(`
      SELECT COUNT(*) as count FROM proactive_questions 
      WHERE wallet_address = ? AND answered_at >= ?
    `).bind(walletAddress, todayStart).first()

    // 待回答数量
    const now = Math.floor(Date.now() / 1000)
    const pendingResult = await env.DB.prepare(`
      SELECT COUNT(*) as count FROM proactive_questions 
      WHERE wallet_address = ? 
        AND status IN ('PENDING', 'NOTIFIED')
        AND (expires_at IS NULL OR expires_at > ?)
    `).bind(walletAddress, now).first()

    // 获取每日限制
    const limitConfig = await env.DB.prepare(`
      SELECT config_value FROM app_config WHERE config_key = 'questions.daily_limit'
    `).first()
    const dailyLimit = limitConfig ? parseInt(limitConfig.config_value) : 3

    return jsonResponse({
      todayNotified: notifiedResult?.count || 0,
      todayAnswered: answeredResult?.count || 0,
      pending: pendingResult?.count || 0,
      dailyLimit,
      remaining: Math.max(0, dailyLimit - (notifiedResult?.count || 0)),
    })
  } catch (error) {
    console.error('Error getting today stats:', error)
    return jsonResponse({ error: 'Failed to get stats' }, 500)
  }
}

// ============================================
// 路由处理
// ============================================

export async function handleQuestionRoutes(
  request: Request,
  env: any,
  path: string
): Promise<Response | null> {
  // GET /api/v1/questions - 获取问题列表
  if (request.method === 'GET' && path === '/api/v1/questions') {
    return getQuestions(request, env)
  }

  // GET /api/v1/questions/pending - 获取待回答问题
  if (request.method === 'GET' && path === '/api/v1/questions/pending') {
    return getPendingQuestions(request, env)
  }

  // GET /api/v1/questions/stats - 获取今日统计
  if (request.method === 'GET' && path === '/api/v1/questions/stats') {
    return getTodayStats(request, env)
  }

  // POST /api/v1/questions - 创建问题
  if (request.method === 'POST' && path === '/api/v1/questions') {
    return createQuestion(request, env)
  }

  // POST /api/v1/questions/batch - 批量创建问题
  if (request.method === 'POST' && path === '/api/v1/questions/batch') {
    return batchCreateQuestions(request, env)
  }

  // PUT /api/v1/questions/:id/notify - 标记为已通知
  const notifyMatch = path.match(/^\/api\/v1\/questions\/([^/]+)\/notify$/)
  if (request.method === 'PUT' && notifyMatch) {
    return markNotified(request, env, notifyMatch[1])
  }

  // PUT /api/v1/questions/:id/answer - 回答问题
  const answerMatch = path.match(/^\/api\/v1\/questions\/([^/]+)\/answer$/)
  if (request.method === 'PUT' && answerMatch) {
    return answerQuestion(request, env, answerMatch[1])
  }

  // PUT /api/v1/questions/:id/skip - 跳过问题
  const skipMatch = path.match(/^\/api\/v1\/questions\/([^/]+)\/skip$/)
  if (request.method === 'PUT' && skipMatch) {
    return skipQuestion(request, env, skipMatch[1])
  }

  return null
}
