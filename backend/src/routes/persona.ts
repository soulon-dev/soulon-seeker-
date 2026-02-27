/**
 * 人格数据 API
 * 用于 App 云端存储 OCEAN 五大人格数据
 */

import { jsonResponse } from '../index'

// ============================================
// 类型定义
// ============================================

interface PersonaData {
  userId: string
  walletAddress: string
  openness: number
  conscientiousness: number
  extraversion: number
  agreeableness: number
  neuroticism: number
  sampleSize: number
  analyzedAt: number
  syncRate: number
  questionnaireCompleted: boolean
  questionnaireAnswers?: Record<string, any>
  questionnaireCompletedAt?: number
  personaProfileV2?: any
  personaProfileV2UpdatedAt?: number
}

interface UpdatePersonaRequest {
  walletAddress: string
  openness?: number
  conscientiousness?: number
  extraversion?: number
  agreeableness?: number
  neuroticism?: number
  sampleSize?: number
  syncRate?: number
}

interface UpdatePersonaProfileV2Request {
  walletAddress: string
  personaProfileV2: any
}

interface QuestionnaireRequest {
  walletAddress: string
  answers: Record<string, any>
  personaScores: {
    openness: number
    conscientiousness: number
    extraversion: number
    agreeableness: number
    neuroticism: number
  }
}

// ============================================
// 辅助函数
// ============================================

function getUserIdFromWallet(walletAddress: string): string {
  return `user_${walletAddress.substring(0, 8)}`
}

async function ensurePersonaProfileV2Table(env: any) {
  if (!env.DB) return
  await env.DB.prepare(`
    CREATE TABLE IF NOT EXISTS user_persona_profile_v2 (
      wallet_address TEXT PRIMARY KEY,
      profile_json TEXT NOT NULL,
      updated_at INTEGER DEFAULT (unixepoch())
    );
  `).run()
  await env.DB.prepare(`
    CREATE INDEX IF NOT EXISTS idx_persona_profile_v2_wallet ON user_persona_profile_v2(wallet_address);
  `).run()
}

// ============================================
// API 处理函数
// ============================================

/**
 * 获取用户人格数据
 */
async function getPersona(request: Request, env: any): Promise<Response> {
  const url = new URL(request.url)
  const walletAddress = url.searchParams.get('wallet')

  if (!walletAddress) {
    return jsonResponse({ error: 'Missing wallet parameter' }, 400)
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const userId = getUserIdFromWallet(walletAddress)
    await ensurePersonaProfileV2Table(env)

    const persona = await env.DB.prepare(`
      SELECT user_id, wallet_address, openness, conscientiousness, extraversion,
             agreeableness, neuroticism, sample_size, analyzed_at, sync_rate,
             questionnaire_completed, questionnaire_answers, questionnaire_completed_at,
             created_at, updated_at
      FROM user_persona
      WHERE wallet_address = ?
    `).bind(walletAddress).first()

    const personaV2 = await env.DB.prepare(`
      SELECT profile_json, updated_at
      FROM user_persona_profile_v2
      WHERE wallet_address = ?
    `).bind(walletAddress).first()

    if (!persona) {
      // 返回默认值
      return jsonResponse({
        exists: false,
        persona: {
          userId,
          walletAddress,
          openness: 0.5,
          conscientiousness: 0.5,
          extraversion: 0.5,
          agreeableness: 0.5,
          neuroticism: 0.5,
          sampleSize: 0,
          analyzedAt: null,
          syncRate: 0,
          questionnaireCompleted: false,
          questionnaireAnswers: null,
          questionnaireCompletedAt: null,
          personaProfileV2: personaV2?.profile_json ? JSON.parse(personaV2.profile_json) : null,
          personaProfileV2UpdatedAt: personaV2?.updated_at ?? null,
        }
      })
    }

    return jsonResponse({
      exists: true,
      persona: {
        userId: persona.user_id,
        walletAddress: persona.wallet_address,
        openness: persona.openness,
        conscientiousness: persona.conscientiousness,
        extraversion: persona.extraversion,
        agreeableness: persona.agreeableness,
        neuroticism: persona.neuroticism,
        sampleSize: persona.sample_size,
        analyzedAt: persona.analyzed_at,
        syncRate: persona.sync_rate,
        questionnaireCompleted: !!persona.questionnaire_completed,
        questionnaireAnswers: persona.questionnaire_answers 
          ? JSON.parse(persona.questionnaire_answers) 
          : null,
        questionnaireCompletedAt: persona.questionnaire_completed_at,
        personaProfileV2: personaV2?.profile_json ? JSON.parse(personaV2.profile_json) : null,
        personaProfileV2UpdatedAt: personaV2?.updated_at ?? null,
        createdAt: persona.created_at,
        updatedAt: persona.updated_at,
      }
    })
  } catch (error) {
    console.error('Error getting persona:', error)
    return jsonResponse({ error: 'Failed to get persona data' }, 500)
  }
}

/**
 * 更新用户人格数据
 */
async function updatePersona(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as UpdatePersonaRequest
    const { walletAddress, openness, conscientiousness, extraversion, 
            agreeableness, neuroticism, sampleSize, syncRate } = body

    if (!walletAddress) {
      return jsonResponse({ error: 'Missing walletAddress' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const now = Math.floor(Date.now() / 1000)

    // 检查是否存在
    const existing = await env.DB.prepare(
      'SELECT id FROM user_persona WHERE wallet_address = ?'
    ).bind(walletAddress).first()

    if (existing) {
      // 更新现有记录
      const updates: string[] = ['updated_at = ?']
      const params: any[] = [now]

      if (openness !== undefined) {
        updates.push('openness = ?')
        params.push(openness)
      }
      if (conscientiousness !== undefined) {
        updates.push('conscientiousness = ?')
        params.push(conscientiousness)
      }
      if (extraversion !== undefined) {
        updates.push('extraversion = ?')
        params.push(extraversion)
      }
      if (agreeableness !== undefined) {
        updates.push('agreeableness = ?')
        params.push(agreeableness)
      }
      if (neuroticism !== undefined) {
        updates.push('neuroticism = ?')
        params.push(neuroticism)
      }
      if (sampleSize !== undefined) {
        updates.push('sample_size = ?')
        params.push(sampleSize)
      }
      if (syncRate !== undefined) {
        updates.push('sync_rate = ?')
        params.push(syncRate)
      }
      
      updates.push('analyzed_at = ?')
      params.push(now)
      params.push(walletAddress)

      await env.DB.prepare(
        `UPDATE user_persona SET ${updates.join(', ')} WHERE wallet_address = ?`
      ).bind(...params).run()
    } else {
      // 创建新记录
      await env.DB.prepare(`
        INSERT INTO user_persona 
        (user_id, wallet_address, openness, conscientiousness, extraversion,
         agreeableness, neuroticism, sample_size, analyzed_at, sync_rate, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `).bind(
        userId, walletAddress,
        openness ?? 0.5,
        conscientiousness ?? 0.5,
        extraversion ?? 0.5,
        agreeableness ?? 0.5,
        neuroticism ?? 0.5,
        sampleSize ?? 0,
        now,
        syncRate ?? 0,
        now, now
      ).run()
    }

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating persona:', error)
    return jsonResponse({ error: 'Failed to update persona data' }, 500)
  }
}

async function updatePersonaProfileV2(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as UpdatePersonaProfileV2Request
    const { walletAddress, personaProfileV2 } = body

    if (!walletAddress || !personaProfileV2) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    await ensurePersonaProfileV2Table(env)

    const now = Math.floor(Date.now() / 1000)
    const profileJson = typeof personaProfileV2 === 'string' ? personaProfileV2 : JSON.stringify(personaProfileV2)

    await env.DB.prepare(`
      INSERT INTO user_persona_profile_v2 (wallet_address, profile_json, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(wallet_address) DO UPDATE SET
        profile_json = excluded.profile_json,
        updated_at = excluded.updated_at
    `).bind(walletAddress, profileJson, now).run()

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error updating persona profile v2:', error)
    return jsonResponse({ error: 'Failed to update persona profile v2' }, 500)
  }
}

/**
 * 提交问卷答案并计算人格
 */
async function submitQuestionnaire(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as QuestionnaireRequest
    const { walletAddress, answers, personaScores } = body

    if (!walletAddress || !answers || !personaScores) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const now = Math.floor(Date.now() / 1000)

    // 检查是否存在
    const existing = await env.DB.prepare(
      'SELECT id FROM user_persona WHERE wallet_address = ?'
    ).bind(walletAddress).first()

    if (existing) {
      // 更新现有记录
      await env.DB.prepare(`
        UPDATE user_persona SET
          openness = ?,
          conscientiousness = ?,
          extraversion = ?,
          agreeableness = ?,
          neuroticism = ?,
          sample_size = 20,
          analyzed_at = ?,
          sync_rate = 1.0,
          questionnaire_completed = 1,
          questionnaire_answers = ?,
          questionnaire_completed_at = ?,
          updated_at = ?
        WHERE wallet_address = ?
      `).bind(
        personaScores.openness,
        personaScores.conscientiousness,
        personaScores.extraversion,
        personaScores.agreeableness,
        personaScores.neuroticism,
        now,
        JSON.stringify(answers),
        now,
        now,
        walletAddress
      ).run()
    } else {
      // 创建新记录
      await env.DB.prepare(`
        INSERT INTO user_persona 
        (user_id, wallet_address, openness, conscientiousness, extraversion,
         agreeableness, neuroticism, sample_size, analyzed_at, sync_rate,
         questionnaire_completed, questionnaire_answers, questionnaire_completed_at,
         created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 20, ?, 1.0, 1, ?, ?, ?, ?)
      `).bind(
        userId, walletAddress,
        personaScores.openness,
        personaScores.conscientiousness,
        personaScores.extraversion,
        personaScores.agreeableness,
        personaScores.neuroticism,
        now,
        JSON.stringify(answers),
        now,
        now, now
      ).run()
    }

    return jsonResponse({
      success: true,
      persona: {
        openness: personaScores.openness,
        conscientiousness: personaScores.conscientiousness,
        extraversion: personaScores.extraversion,
        agreeableness: personaScores.agreeableness,
        neuroticism: personaScores.neuroticism,
        sampleSize: 20,
        syncRate: 1.0,
        questionnaireCompleted: true,
      }
    })
  } catch (error) {
    console.error('Error submitting questionnaire:', error)
    return jsonResponse({ error: 'Failed to submit questionnaire' }, 500)
  }
}

/**
 * 增量更新人格（基于对话分析）
 */
async function incrementPersona(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as {
      walletAddress: string
      traitAdjustments: {
        openness?: number
        conscientiousness?: number
        extraversion?: number
        agreeableness?: number
        neuroticism?: number
      }
      weight?: number  // 调整权重，默认 0.1
    }

    const { walletAddress, traitAdjustments, weight = 0.1 } = body

    if (!walletAddress || !traitAdjustments) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const now = Math.floor(Date.now() / 1000)

    // 获取当前人格数据
    let persona = await env.DB.prepare(
      'SELECT * FROM user_persona WHERE wallet_address = ?'
    ).bind(walletAddress).first()

    if (!persona) {
      // 创建默认记录
      await env.DB.prepare(`
        INSERT INTO user_persona (user_id, wallet_address, created_at, updated_at)
        VALUES (?, ?, ?, ?)
      `).bind(userId, walletAddress, now, now).run()
      
      persona = {
        openness: 0.5,
        conscientiousness: 0.5,
        extraversion: 0.5,
        agreeableness: 0.5,
        neuroticism: 0.5,
        sample_size: 0,
      }
    }

    // 计算新值（加权平均）
    const newOpenness = persona.openness * (1 - weight) + 
                       (traitAdjustments.openness ?? persona.openness) * weight
    const newConscientiousness = persona.conscientiousness * (1 - weight) + 
                                (traitAdjustments.conscientiousness ?? persona.conscientiousness) * weight
    const newExtraversion = persona.extraversion * (1 - weight) + 
                           (traitAdjustments.extraversion ?? persona.extraversion) * weight
    const newAgreeableness = persona.agreeableness * (1 - weight) + 
                            (traitAdjustments.agreeableness ?? persona.agreeableness) * weight
    const newNeuroticism = persona.neuroticism * (1 - weight) + 
                          (traitAdjustments.neuroticism ?? persona.neuroticism) * weight

    // 更新记录
    await env.DB.prepare(`
      UPDATE user_persona SET
        openness = ?,
        conscientiousness = ?,
        extraversion = ?,
        agreeableness = ?,
        neuroticism = ?,
        sample_size = sample_size + 1,
        analyzed_at = ?,
        updated_at = ?
      WHERE wallet_address = ?
    `).bind(
      Math.max(0, Math.min(1, newOpenness)),
      Math.max(0, Math.min(1, newConscientiousness)),
      Math.max(0, Math.min(1, newExtraversion)),
      Math.max(0, Math.min(1, newAgreeableness)),
      Math.max(0, Math.min(1, newNeuroticism)),
      now, now, walletAddress
    ).run()

    return jsonResponse({
      success: true,
      newPersona: {
        openness: newOpenness,
        conscientiousness: newConscientiousness,
        extraversion: newExtraversion,
        agreeableness: newAgreeableness,
        neuroticism: newNeuroticism,
      }
    })
  } catch (error) {
    console.error('Error incrementing persona:', error)
    return jsonResponse({ error: 'Failed to increment persona' }, 500)
  }
}

// ============================================
// 路由处理
// ============================================

export async function handlePersonaRoutes(
  request: Request,
  env: any,
  path: string
): Promise<Response | null> {
  // GET /api/v1/persona - 获取人格数据
  if (request.method === 'GET' && path === '/api/v1/persona') {
    return getPersona(request, env)
  }

  // PUT /api/v1/persona - 更新人格数据
  if (request.method === 'PUT' && path === '/api/v1/persona') {
    return updatePersona(request, env)
  }

  // PUT /api/v1/persona/profile-v2 - 更新人格画像 V2
  if (request.method === 'PUT' && path === '/api/v1/persona/profile-v2') {
    return updatePersonaProfileV2(request, env)
  }

  // POST /api/v1/persona/questionnaire - 提交问卷
  if (request.method === 'POST' && path === '/api/v1/persona/questionnaire') {
    return submitQuestionnaire(request, env)
  }

  // POST /api/v1/persona/increment - 增量更新
  if (request.method === 'POST' && path === '/api/v1/persona/increment') {
    return incrementPersona(request, env)
  }

  return null
}
