/**
 * 数据同步 API
 * 用于接收来自 Android App 的数据同步请求
 */

import { jsonResponse } from '../index'

/**
 * 获取 AI 服务配置（包括 API 密钥）
 * 从数据库中获取主 AI API 密钥并解密
 */
async function getAiConfig(env: any): Promise<{
  qwenApiKey: string | null
  qwenEndpoint: string | null
  embeddingApiKey: string | null
  embeddingEndpoint: string | null
}> {
  if (!env.DB) {
    console.log('getAiConfig: No DB available')
    return {
      qwenApiKey: null,
      qwenEndpoint: null,
      embeddingApiKey: null,
      embeddingEndpoint: null,
    }
  }

  try {
    // 获取主 Qwen API 密钥
    const qwenKey = await env.DB.prepare(
      `SELECT encrypted_key, endpoint_url FROM api_keys 
       WHERE service = 'qwen' AND is_active = 1
       ORDER BY is_primary DESC, updated_at DESC, created_at DESC
       LIMIT 1`
    ).first()

    console.log('getAiConfig: qwenKey found:', qwenKey ? 'yes' : 'no')
    if (qwenKey) {
      console.log('getAiConfig: encrypted_key length:', qwenKey.encrypted_key?.length)
    }

    // 获取 Embedding API 密钥（可能与 Qwen 相同，也可能是独立的）
    const embeddingKey = await env.DB.prepare(
      `SELECT encrypted_key, endpoint_url FROM api_keys 
       WHERE service = 'embedding' AND is_active = 1
       ORDER BY is_primary DESC, updated_at DESC, created_at DESC
       LIMIT 1`
    ).first()

    // 解密密钥（简单的 base64 解码，生产环境应使用更安全的方案）
    const decryptKey = (encrypted: string): string => {
      try {
        const decoded = atob(encrypted)
        console.log('getAiConfig: decrypted key length:', decoded.length, 'prefix:', decoded.substring(0, 5))
        return decoded
      } catch (e) {
        console.error('getAiConfig: decryption failed:', e)
        return encrypted // 如果解密失败，返回原始值（可能未加密）
      }
    }

    // 默认端点
    const DEFAULT_QWEN_ENDPOINT = 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions'
    const DEFAULT_EMBEDDING_ENDPOINT = 'https://dashscope-intl.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding'
    
    // 验证端点是否正确
    const validateQwenEndpoint = (url: string | null): string => {
      if (!url) return DEFAULT_QWEN_ENDPOINT
      // 如果配置的是 Embedding 端点，使用默认 Chat 端点
      if (url.includes('embeddings')) {
        console.warn('Qwen endpoint configured as embedding endpoint, using default')
        return DEFAULT_QWEN_ENDPOINT
      }
      return url
    }
    
    const validateEmbeddingEndpoint = (url: string | null): string => {
      if (!url) return DEFAULT_EMBEDDING_ENDPOINT
      // 如果配置的是 Chat 端点，使用默认 Embedding 端点
      if (url.includes('chat/completions')) {
        console.warn('Embedding endpoint configured as chat endpoint, using default')
        return DEFAULT_EMBEDDING_ENDPOINT
      }
      return url
    }
    
    const qwenApiKey = qwenKey?.encrypted_key ? decryptKey(qwenKey.encrypted_key) : null
    const embeddingApiKey = embeddingKey?.encrypted_key ? decryptKey(embeddingKey.encrypted_key) : qwenApiKey
    
    console.log('getAiConfig: final qwenApiKey length:', qwenApiKey?.length, 'embeddingApiKey length:', embeddingApiKey?.length)
    
    return {
      qwenApiKey,
      qwenEndpoint: validateQwenEndpoint(qwenKey?.endpoint_url),
      embeddingApiKey,
      embeddingEndpoint: validateEmbeddingEndpoint(embeddingKey?.endpoint_url),
    }
  } catch (error) {
    console.error('Error getting AI config:', error)
    return {
      qwenApiKey: null,
      qwenEndpoint: null,
      embeddingApiKey: null,
      embeddingEndpoint: null,
    }
  }
}

interface SyncUserRequest {
  walletAddress: string
  memoBalance: number
  currentTier: number
  subscriptionType: string
  subscriptionExpiry?: number
  stakedAmount: number
  totalTokensUsed: number
  memoriesCount: number
}

interface SyncSubscriptionRequest {
  walletAddress: string
  planId: string
  startDate: number
  endDate: number
  amount: number
  transactionId?: string
}

function normalizeEpochToMs(value: number | undefined | null): number | null {
  if (!value || !Number.isFinite(value) || value <= 0) return null
  return value < 1_000_000_000_000 ? Math.floor(value * 1000) : Math.floor(value)
}

interface SyncStakingRequest {
  walletAddress: string
  projectId: string
  amount: number
  startTime: number
  unlockTime: number
  status: string
}

interface LogChatRequest {
  walletAddress: string
  userMessagePreview: string
  tokensUsed: number
}

interface LogMemoryRequest {
  walletAddress: string
  irysId: string
  type: string
  size: number
}

/**
 * 同步用户数据
 */
async function syncUser(request: Request, env: any): Promise<Response> {
  try {
    const body = await request.json() as SyncUserRequest
    const { walletAddress, memoBalance, currentTier, subscriptionType, subscriptionExpiry, stakedAmount, totalTokensUsed, memoriesCount } = body

    if (!walletAddress) {
      return jsonResponse({ error: 'Missing walletAddress' }, 400)
    }

    // 如果有 D1 数据库，插入或更新用户数据
    if (env.DB) {
      const userId = `user_${walletAddress.substring(0, 8)}`
      
      // 检查用户是否存在
      const existing = await env.DB.prepare(
        'SELECT id FROM users WHERE wallet_address = ?'
      ).bind(walletAddress).first()

      if (existing) {
        // 更新现有用户
        await env.DB.prepare(`
          UPDATE users SET
            memo_balance = ?,
            current_tier = ?,
            subscription_type = ?,
            subscription_expiry = ?,
            staked_amount = ?,
            total_tokens_used = ?,
            memories_count = ?,
            last_active_at = ?
          WHERE wallet_address = ?
        `).bind(
          memoBalance,
          currentTier,
          subscriptionType,
          subscriptionExpiry || null,
          stakedAmount,
          totalTokensUsed,
          memoriesCount,
          Math.floor(Date.now() / 1000),
          walletAddress
        ).run()
      } else {
        // 插入新用户
        await env.DB.prepare(`
          INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, subscription_expiry, staked_amount, total_tokens_used, memories_count)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        `).bind(
          userId,
          walletAddress,
          memoBalance,
          currentTier,
          subscriptionType,
          subscriptionExpiry || null,
          stakedAmount,
          totalTokensUsed,
          memoriesCount
        ).run()
      }

      console.log(`User synced: ${walletAddress}`)
    }

    return jsonResponse({ success: true, message: 'User data synced' })
  } catch (error) {
    console.error('Error syncing user:', error)
    return jsonResponse({ error: 'Failed to sync user data' }, 500)
  }
}

/**
 * 获取用户配置（供 App 拉取后台修改）
 */
async function getUserProfile(walletAddress: string, env: any): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const user = await env.DB.prepare(
      `SELECT id, wallet_address, memo_balance, current_tier, subscription_type, 
        subscription_expiry, staked_amount, is_founder, is_expert, is_banned,
        total_tokens_used, memories_count, created_at, last_active_at
       FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first()

    if (!user) {
      return jsonResponse({ exists: false }, 200)
    }

    // 获取配额覆盖（如果有）
    const quotaOverride = await env.DB.prepare(
      `SELECT monthly_token_limit, daily_conversation_limit, custom_multiplier, expires_at, reason
       FROM user_quota_overrides WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)`
    ).bind(user.id, Math.floor(Date.now() / 1000)).first()

    return jsonResponse({
      exists: true,
      profile: {
        id: user.id,
        walletAddress: user.wallet_address,
        memoBalance: user.memo_balance || 0,
        currentTier: user.current_tier || 1,
        subscriptionType: user.subscription_type || 'FREE',
        subscriptionExpiry: user.subscription_expiry,
        stakedAmount: user.staked_amount || 0,
        isFounder: !!user.is_founder,
        isExpert: !!user.is_expert,
        isBanned: !!user.is_banned,
        totalTokensUsed: user.total_tokens_used || 0,
        memoriesCount: user.memories_count || 0,
        createdAt: user.created_at,
        lastActiveAt: user.last_active_at,
      },
      quotaOverride: quotaOverride ? {
        monthlyTokenLimit: quotaOverride.monthly_token_limit,
        dailyConversationLimit: quotaOverride.daily_conversation_limit,
        customMultiplier: quotaOverride.custom_multiplier,
        expiresAt: quotaOverride.expires_at,
        reason: quotaOverride.reason,
      } : null,
    })
  } catch (error) {
    console.error('Error getting user profile:', error)
    return jsonResponse({ error: 'Failed to get user profile' }, 500)
  }
}

/**
 * 获取完整用户数据（一次性拉取，用于 App 启动时同步）
 */
async function getFullUserProfile(walletAddress: string, env: any): Promise<Response> {
  try {
    if (!env.DB) {
      return jsonResponse({ error: 'Database not available' }, 500)
    }

    const userId = `user_${walletAddress.substring(0, 8)}`

    // 1. 获取用户基本信息
    const user = await env.DB.prepare(
      `SELECT * FROM users WHERE wallet_address = ?`
    ).bind(walletAddress).first()

    if (!user) {
      return jsonResponse({ exists: false }, 200)
    }

    await env.DB.prepare(`
      CREATE TABLE IF NOT EXISTS user_persona_profile_v2 (
        wallet_address TEXT PRIMARY KEY,
        profile_json TEXT NOT NULL,
        updated_at INTEGER DEFAULT (unixepoch())
      );
    `).run()

    // 2. 获取人格数据
    const persona = await env.DB.prepare(
      `SELECT openness, conscientiousness, extraversion, agreeableness, neuroticism,
              sample_size, analyzed_at, sync_rate, questionnaire_completed,
              questionnaire_completed_at, questionnaire_answers
       FROM user_persona WHERE wallet_address = ?`
    ).bind(walletAddress).first()

    const personaV2 = await env.DB.prepare(
      `SELECT profile_json, updated_at
       FROM user_persona_profile_v2 WHERE wallet_address = ?`
    ).bind(walletAddress).first()

    // 3. 获取最近的聊天会话（最近 20 个）
    const sessions = await env.DB.prepare(
      `SELECT id, title, created_at, updated_at, message_count, total_tokens, total_memo_earned
       FROM chat_sessions WHERE wallet_address = ? AND is_deleted = 0
       ORDER BY updated_at DESC LIMIT 20`
    ).bind(walletAddress).all()

    // 4. 获取最近的积分交易（最近 50 条）
    const transactions = await env.DB.prepare(
      `SELECT id, type, amount, balance_before, balance_after, source, description, created_at
       FROM memo_transactions WHERE wallet_address = ?
       ORDER BY created_at DESC LIMIT 50`
    ).bind(walletAddress).all()

    // 5. 获取待回答的奇遇问题
    const now = Math.floor(Date.now() / 1000)
    const pendingQuestions = await env.DB.prepare(
      `SELECT id, question_text, category, status, priority, created_at, scheduled_at, expires_at
       FROM proactive_questions 
       WHERE wallet_address = ? AND status IN ('PENDING', 'NOTIFIED') AND (expires_at IS NULL OR expires_at > ?)
       ORDER BY priority DESC LIMIT 10`
    ).bind(walletAddress, now).all()

    // 6. 获取今日统计
    const today = new Date().toISOString().split('T')[0]
    const dailyStats = await env.DB.prepare(
      `SELECT dialogue_count, tokens_used, memo_earned, questions_answered, has_first_chat
       FROM user_daily_stats WHERE wallet_address = ? AND stat_date = ?`
    ).bind(walletAddress, today).first()

    // 7. 获取签到记录（最近 7 天）
    const checkins = await env.DB.prepare(
      `SELECT checkin_date, streak_day, memo_earned
       FROM user_checkins WHERE wallet_address = ?
       ORDER BY checkin_date DESC LIMIT 7`
    ).bind(walletAddress).all()

    // 8. 获取配额覆盖
    const quotaOverride = await env.DB.prepare(
      `SELECT monthly_token_limit, daily_conversation_limit, custom_multiplier, expires_at
       FROM user_quota_overrides WHERE user_id = ? AND (expires_at IS NULL OR expires_at > ?)`
    ).bind(user.id, now).first()

    // 9. 获取向量统计
    const vectorStats = await env.DB.prepare(
      `SELECT COUNT(*) as count FROM memory_vectors WHERE wallet_address = ?`
    ).bind(walletAddress).first()

    return jsonResponse({
      exists: true,
      syncedAt: now,
      
      // 用户基本信息
      profile: {
        id: user.id,
        walletAddress: user.wallet_address,
        memoBalance: user.memo_balance || 0,
        currentTier: user.current_tier || 1,
        subscriptionType: user.subscription_type || 'FREE',
        subscriptionExpiry: user.subscription_expiry,
        stakedAmount: user.staked_amount || 0,
        isFounder: !!user.is_founder,
        isExpert: !!user.is_expert,
        isBanned: !!user.is_banned,
        totalTokensUsed: user.total_tokens_used || 0,
        memoriesCount: user.memories_count || 0,
        createdAt: user.created_at,
        lastActiveAt: user.last_active_at,
      },
      
      // 人格数据
      persona: persona ? {
        openness: persona.openness,
        conscientiousness: persona.conscientiousness,
        extraversion: persona.extraversion,
        agreeableness: persona.agreeableness,
        neuroticism: persona.neuroticism,
        sampleSize: persona.sample_size,
        analyzedAt: persona.analyzed_at,
        syncRate: persona.sync_rate,
        questionnaireCompleted: !!persona.questionnaire_completed,
        questionnaireCompletedAt: persona.questionnaire_completed_at,
        questionnaireAnswers: persona.questionnaire_answers ? JSON.parse(persona.questionnaire_answers) : null,
        personaProfileV2: personaV2?.profile_json ? JSON.parse(personaV2.profile_json) : null,
        personaProfileV2UpdatedAt: personaV2?.updated_at ?? null,
      } : null,
      
      // 聊天会话
      chatSessions: (sessions.results || []).map((s: any) => ({
        id: s.id,
        title: s.title,
        createdAt: s.created_at,
        updatedAt: s.updated_at,
        messageCount: s.message_count,
        totalTokens: s.total_tokens,
        totalMemoEarned: s.total_memo_earned,
      })),
      
      // 积分交易
      memoTransactions: (transactions.results || []).map((t: any) => ({
        id: t.id,
        type: t.type,
        amount: t.amount,
        balanceBefore: t.balance_before,
        balanceAfter: t.balance_after,
        source: t.source,
        description: t.description,
        createdAt: t.created_at,
      })),
      
      // 奇遇问题
      pendingQuestions: (pendingQuestions.results || []).map((q: any) => ({
        id: q.id,
        questionText: q.question_text,
        category: q.category,
        status: q.status,
        priority: q.priority,
        createdAt: q.created_at,
        scheduledAt: q.scheduled_at,
        expiresAt: q.expires_at,
      })),
      
      // 今日统计
      todayStats: dailyStats ? {
        dialogueCount: dailyStats.dialogue_count,
        tokensUsed: dailyStats.tokens_used,
        memoEarned: dailyStats.memo_earned,
        questionsAnswered: dailyStats.questions_answered,
        hasFirstChat: !!dailyStats.has_first_chat,
      } : {
        dialogueCount: 0,
        tokensUsed: 0,
        memoEarned: 0,
        questionsAnswered: 0,
        hasFirstChat: false,
      },
      
      // 签到记录
      checkins: (checkins.results || []).map((c: any) => ({
        date: c.checkin_date,
        streakDay: c.streak_day,
        memoEarned: c.memo_earned,
      })),
      
      // 配额覆盖
      quotaOverride: quotaOverride ? {
        monthlyTokenLimit: quotaOverride.monthly_token_limit,
        dailyConversationLimit: quotaOverride.daily_conversation_limit,
        customMultiplier: quotaOverride.custom_multiplier,
        expiresAt: quotaOverride.expires_at,
      } : null,
      
      // 向量统计
      vectorCount: vectorStats?.count || 0,
      
      // AI 服务配置（从后台获取主 API 密钥）
      aiConfig: await getAiConfig(env),
    })
  } catch (error) {
    console.error('Error getting full user profile:', error)
    return jsonResponse({ error: 'Failed to get full user profile' }, 500)
  }
}

/**
 * 同步订阅记录
 */
async function syncSubscription(request: Request, env: any): Promise<Response> {
  try {
    const body = await request.json() as SyncSubscriptionRequest
    const { walletAddress, planId, startDate, endDate, amount, transactionId } = body

    if (!walletAddress || !planId) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    if (env.DB) {
      let subscriptionRecordsReady = (globalThis as any).__subscriptionRecordsReady as boolean | undefined
      if (!subscriptionRecordsReady) {
        await env.DB.prepare(
          `CREATE TABLE IF NOT EXISTS subscription_records (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            wallet_address TEXT NOT NULL,
            plan_id TEXT NOT NULL,
            start_date INTEGER,
            end_date INTEGER,
            amount REAL,
            transaction_id TEXT,
            status TEXT DEFAULT 'active',
            created_at INTEGER DEFAULT (unixepoch())
          )`
        ).run()
        ;(globalThis as any).__subscriptionRecordsReady = true
      }

      const nowMs = Date.now()
      const startMs = normalizeEpochToMs(startDate) || nowMs
      const endMs = normalizeEpochToMs(endDate)
        || (startMs + 30 * 24 * 60 * 60 * 1000)

      // 查找用户 ID
      const user = await env.DB.prepare(
        'SELECT id FROM users WHERE wallet_address = ?'
      ).bind(walletAddress).first()

      const nowSec = Math.floor(Date.now() / 1000)
      const userId = (user?.id as string | undefined) || `user_${walletAddress.substring(0, 8)}`
      if (!user?.id) {
        await env.DB.prepare(
          `INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, created_at, last_active_at)
           VALUES (?, ?, 0, 1, 'FREE', ?, ?)
           ON CONFLICT(wallet_address) DO NOTHING`
        ).bind(userId, walletAddress, nowSec, nowSec).run()
      }
      const recordId = `sub_${Date.now()}_${Math.random().toString(36).substring(7)}`

      await env.DB.prepare(`
        INSERT INTO subscription_records (id, user_id, wallet_address, plan_id, start_date, end_date, amount, transaction_id, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active')
      `).bind(
        recordId,
        userId,
        walletAddress,
        planId,
        startMs,
        endMs,
        amount,
        transactionId || null
      ).run()

      // 更新用户的订阅状态
      await env.DB.prepare(`
        UPDATE users SET subscription_type = ?, subscription_expiry = ? WHERE wallet_address = ?
      `).bind(planId.toUpperCase(), endMs, walletAddress).run()

      console.log(`Subscription synced: ${walletAddress} -> ${planId}`)
    }

    return jsonResponse({ success: true, message: 'Subscription synced' })
  } catch (error) {
    console.error('Error syncing subscription:', error)
    return jsonResponse(
      { error: 'Failed to sync subscription', message: (error as Error).message },
      500
    )
  }
}

/**
 * 同步质押记录
 */
async function syncStaking(request: Request, env: any): Promise<Response> {
  try {
    const body = await request.json() as SyncStakingRequest
    const { walletAddress, projectId, amount, startTime, unlockTime, status } = body

    if (!walletAddress || !projectId) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    if (env.DB) {
      const user = await env.DB.prepare(
        'SELECT id FROM users WHERE wallet_address = ?'
      ).bind(walletAddress).first()

      const userId = user?.id || `user_${walletAddress.substring(0, 8)}`
      const recordId = `stake_${Date.now()}_${Math.random().toString(36).substring(7)}`

      // 检查是否已有该项目的质押记录
      const existing = await env.DB.prepare(
        'SELECT id FROM staking_records WHERE wallet_address = ? AND project_id = ? AND status = ?'
      ).bind(walletAddress, projectId, 'active').first()

      if (existing) {
        // 更新现有记录
        await env.DB.prepare(`
          UPDATE staking_records SET amount = ?, unlock_time = ?, status = ? WHERE id = ?
        `).bind(amount, unlockTime, status, existing.id).run()
      } else {
        // 插入新记录
        await env.DB.prepare(`
          INSERT INTO staking_records (id, user_id, wallet_address, project_id, amount, start_time, unlock_time, status)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        `).bind(
          recordId,
          userId,
          walletAddress,
          projectId,
          amount,
          startTime,
          unlockTime,
          status
        ).run()
      }

      // 更新用户的质押总额
      await env.DB.prepare(`
        UPDATE users SET staked_amount = ? WHERE wallet_address = ?
      `).bind(amount, walletAddress).run()

      // 更新项目参与人数
      await env.DB.prepare(`
        UPDATE staking_projects SET participants = participants + 1 WHERE id = ?
      `).bind(projectId).run()

      console.log(`Staking synced: ${walletAddress} -> ${projectId}: ${amount}`)
    }

    return jsonResponse({ success: true, message: 'Staking synced' })
  } catch (error) {
    console.error('Error syncing staking:', error)
    return jsonResponse({ error: 'Failed to sync staking' }, 500)
  }
}

/**
 * 记录聊天日志
 */
async function logChat(request: Request, env: any): Promise<Response> {
  try {
    const body = await request.json() as LogChatRequest
    const { walletAddress, userMessagePreview, tokensUsed } = body

    if (!walletAddress) {
      return jsonResponse({ error: 'Missing walletAddress' }, 400)
    }

    if (env.DB) {
      const user = await env.DB.prepare(
        'SELECT id FROM users WHERE wallet_address = ?'
      ).bind(walletAddress).first()

      const userId = user?.id || `user_${walletAddress.substring(0, 8)}`
      const chatId = `chat_${Date.now()}_${Math.random().toString(36).substring(7)}`

      await env.DB.prepare(`
        INSERT INTO chat_logs (id, user_id, wallet_address, user_message_preview, tokens_used)
        VALUES (?, ?, ?, ?, ?)
      `).bind(
        chatId,
        userId,
        walletAddress,
        userMessagePreview,
        tokensUsed
      ).run()

      // 更新用户的 Token 使用量
      await env.DB.prepare(`
        UPDATE users SET total_tokens_used = total_tokens_used + ? WHERE wallet_address = ?
      `).bind(tokensUsed, walletAddress).run()
    }

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error logging chat:', error)
    return jsonResponse({ error: 'Failed to log chat' }, 500)
  }
}

/**
 * 记录记忆上传
 */
async function logMemory(request: Request, env: any): Promise<Response> {
  try {
    const body = await request.json() as LogMemoryRequest
    const { walletAddress, irysId, type, size } = body

    if (!walletAddress || !irysId) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    if (env.DB) {
      const user = await env.DB.prepare(
        'SELECT id FROM users WHERE wallet_address = ?'
      ).bind(walletAddress).first()

      const userId = user?.id || `user_${walletAddress.substring(0, 8)}`
      const memoryId = `mem_${Date.now()}_${Math.random().toString(36).substring(7)}`

      await env.DB.prepare(`
        INSERT INTO memories (id, user_id, wallet_address, irys_id, type, size)
        VALUES (?, ?, ?, ?, ?, ?)
      `).bind(
        memoryId,
        userId,
        walletAddress,
        irysId,
        type,
        size
      ).run()

      // 更新用户的记忆数量
      await env.DB.prepare(`
        UPDATE users SET memories_count = memories_count + 1 WHERE wallet_address = ?
      `).bind(walletAddress).run()
    }

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error logging memory:', error)
    return jsonResponse({ error: 'Failed to log memory' }, 500)
  }
}

/**
 * 处理同步路由
 */
export async function handleSyncRoutes(request: Request, env: any, path: string): Promise<Response> {
  // CORS 预检
  if (request.method === 'OPTIONS') {
    return new Response(null, {
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
      },
    })
  }

  // GET 请求 - 获取用户数据
  if (request.method === 'GET') {
    const url = new URL(request.url)
    const walletAddress = url.searchParams.get('wallet')
    
    if (!walletAddress) {
      return jsonResponse({ error: 'Missing wallet parameter' }, 400)
    }
    
    // GET /api/v1/users/profile?wallet=xxx - 获取用户配置
    if (path === '/api/v1/users/profile') {
      const response = await getUserProfile(walletAddress, env)
      const newHeaders = new Headers(response.headers)
      newHeaders.set('Access-Control-Allow-Origin', '*')
      return new Response(response.body, { status: response.status, headers: newHeaders })
    }
    
    // GET /api/v1/users/full-profile?wallet=xxx - 获取完整用户数据（一次性拉取）
    if (path === '/api/v1/users/full-profile') {
      const response = await getFullUserProfile(walletAddress, env)
      const newHeaders = new Headers(response.headers)
      newHeaders.set('Access-Control-Allow-Origin', '*')
      return new Response(response.body, { status: response.status, headers: newHeaders })
    }
    
    return jsonResponse({ error: 'Not found' }, 404)
  }

  if (request.method !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405)
  }

  let response: Response

  switch (path) {
    case '/api/v1/users/sync':
      response = await syncUser(request, env)
      break
    case '/api/v1/subscriptions/sync':
      response = await syncSubscription(request, env)
      break
    case '/api/v1/staking/sync':
      response = await syncStaking(request, env)
      break
    case '/api/v1/chats/log':
      response = await logChat(request, env)
      break
    case '/api/v1/memories/log':
      response = await logMemory(request, env)
      break
    default:
      return jsonResponse({ error: 'Not found' }, 404)
  }

  // 添加 CORS 头
  const newHeaders = new Headers(response.headers)
  newHeaders.set('Access-Control-Allow-Origin', '*')
  return new Response(response.body, {
    status: response.status,
    headers: newHeaders,
  })
}
