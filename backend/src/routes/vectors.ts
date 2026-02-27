/**
 * 记忆向量 API
 * 用于 App 云端 RAG（检索增强生成）
 */

import { jsonResponse } from '../index'

// ============================================
// 类型定义
// ============================================

interface MemoryVector {
  memoryId: string
  userId: string
  walletAddress: string
  vectorJson: string
  vectorDimension: number
  model: string
  textPreview: string
  textLength: number
  memoryType: string
  sourceId?: string
  sourceType?: string
  createdAt: number
}

interface CreateVectorRequest {
  walletAddress: string
  memoryId: string
  vector: number[]
  textPreview: string
  textLength: number
  memoryType?: string
  sourceId?: string
  sourceType?: string
  model?: string
}

interface BatchCreateVectorsRequest {
  walletAddress: string
  vectors: {
    memoryId: string
    vector: number[]
    textPreview: string
    textLength: number
    memoryType?: string
    sourceId?: string
    sourceType?: string
  }[]
}

interface SearchVectorsRequest {
  walletAddress: string
  queryVector: number[]
  topK?: number
  threshold?: number
  memoryType?: string
}

// ============================================
// 辅助函数
// ============================================

function getUserIdFromWallet(walletAddress: string): string {
  return `user_${walletAddress.substring(0, 8)}`
}

/**
 * 计算余弦相似度
 */
function cosineSimilarity(a: number[], b: number[]): number {
  if (a.length !== b.length) return 0
  
  let dotProduct = 0
  let normA = 0
  let normB = 0
  
  for (let i = 0; i < a.length; i++) {
    dotProduct += a[i] * b[i]
    normA += a[i] * a[i]
    normB += b[i] * b[i]
  }
  
  if (normA === 0 || normB === 0) return 0
  return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB))
}

// ============================================
// API 处理函数
// ============================================

/**
 * 获取用户所有向量
 */
async function getVectors(request: Request, env: any): Promise<Response> {
  const url = new URL(request.url)
  const walletAddress = url.searchParams.get('wallet')
  const limit = parseInt(url.searchParams.get('limit') || '100')
  const offset = parseInt(url.searchParams.get('offset') || '0')
  const memoryType = url.searchParams.get('type')

  if (!walletAddress) {
    return jsonResponse({ error: 'Missing wallet parameter' }, 400)
  }

  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    let query = `
      SELECT memory_id, user_id, wallet_address, vector_dimension, model,
             text_preview, text_length, memory_type, source_id, source_type, created_at
      FROM memory_vectors 
      WHERE wallet_address = ?
    `
    const params: any[] = [walletAddress]

    if (memoryType) {
      query += ' AND memory_type = ?'
      params.push(memoryType)
    }

    query += ' ORDER BY created_at DESC LIMIT ? OFFSET ?'
    params.push(limit, offset)

    const result = await env.DB.prepare(query).bind(...params).all()

    const vectors = (result.results || []).map((row: any) => ({
      memoryId: row.memory_id,
      userId: row.user_id,
      walletAddress: row.wallet_address,
      vectorDimension: row.vector_dimension,
      model: row.model,
      textPreview: row.text_preview,
      textLength: row.text_length,
      memoryType: row.memory_type,
      sourceId: row.source_id,
      sourceType: row.source_type,
      createdAt: row.created_at,
      // 不返回完整向量，太大了
    }))

    // 获取总数
    let countQuery = 'SELECT COUNT(*) as count FROM memory_vectors WHERE wallet_address = ?'
    const countParams: any[] = [walletAddress]
    if (memoryType) {
      countQuery += ' AND memory_type = ?'
      countParams.push(memoryType)
    }
    const countResult = await env.DB.prepare(countQuery).bind(...countParams).first()

    return jsonResponse({
      vectors,
      total: countResult?.count || 0,
      limit,
      offset,
    })
  } catch (error) {
    console.error('Error getting vectors:', error)
    return jsonResponse({ error: 'Failed to get vectors' }, 500)
  }
}

/**
 * 创建单个向量
 */
async function createVector(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as CreateVectorRequest
    const { walletAddress, memoryId, vector, textPreview, textLength,
            memoryType, sourceId, sourceType, model } = body

    if (!walletAddress || !memoryId || !vector || vector.length === 0) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const now = Math.floor(Date.now() / 1000)

    await env.DB.prepare(`
      INSERT OR REPLACE INTO memory_vectors 
      (memory_id, user_id, wallet_address, vector_json, vector_dimension, model,
       text_preview, text_length, memory_type, source_id, source_type, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      memoryId, userId, walletAddress,
      JSON.stringify(vector),
      vector.length,
      model || 'text-embedding-v3',
      textPreview || '',
      textLength || 0,
      memoryType || 'chat',
      sourceId || null,
      sourceType || null,
      now
    ).run()

    return jsonResponse({
      success: true,
      memoryId,
      dimension: vector.length,
    })
  } catch (error) {
    console.error('Error creating vector:', error)
    return jsonResponse({ error: 'Failed to create vector' }, 500)
  }
}

/**
 * 批量创建向量
 */
async function batchCreateVectors(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as BatchCreateVectorsRequest
    const { walletAddress, vectors } = body

    if (!walletAddress || !vectors || vectors.length === 0) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    const userId = getUserIdFromWallet(walletAddress)
    const now = Math.floor(Date.now() / 1000)
    let created = 0

    for (const v of vectors) {
      await env.DB.prepare(`
        INSERT OR REPLACE INTO memory_vectors 
        (memory_id, user_id, wallet_address, vector_json, vector_dimension, model,
         text_preview, text_length, memory_type, source_id, source_type, created_at)
        VALUES (?, ?, ?, ?, ?, 'text-embedding-v3', ?, ?, ?, ?, ?, ?)
      `).bind(
        v.memoryId, userId, walletAddress,
        JSON.stringify(v.vector),
        v.vector.length,
        v.textPreview || '',
        v.textLength || 0,
        v.memoryType || 'chat',
        v.sourceId || null,
        v.sourceType || null,
        now
      ).run()
      created++
    }

    return jsonResponse({
      success: true,
      created,
    })
  } catch (error) {
    console.error('Error batch creating vectors:', error)
    return jsonResponse({ error: 'Failed to batch create vectors' }, 500)
  }
}

/**
 * 删除向量
 */
async function deleteVector(request: Request, env: any, memoryId: string): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    await env.DB.prepare(
      'DELETE FROM memory_vectors WHERE memory_id = ?'
    ).bind(memoryId).run()

    return jsonResponse({ success: true })
  } catch (error) {
    console.error('Error deleting vector:', error)
    return jsonResponse({ error: 'Failed to delete vector' }, 500)
  }
}

/**
 * 语义搜索（向量相似度）
 */
async function searchVectors(request: Request, env: any): Promise<Response> {
  if (!env.DB) {
    return jsonResponse({ error: 'Database not available' }, 500)
  }

  try {
    const body = await request.json() as SearchVectorsRequest
    const { walletAddress, queryVector, topK = 5, threshold = 0.7, memoryType } = body

    if (!walletAddress || !queryVector || queryVector.length === 0) {
      return jsonResponse({ error: 'Missing required fields' }, 400)
    }

    // 获取用户所有向量
    let query = `
      SELECT memory_id, vector_json, text_preview, text_length, memory_type,
             source_id, source_type, created_at
      FROM memory_vectors 
      WHERE wallet_address = ?
    `
    const params: any[] = [walletAddress]

    if (memoryType) {
      query += ' AND memory_type = ?'
      params.push(memoryType)
    }

    const result = await env.DB.prepare(query).bind(...params).all()

    // 计算相似度并排序
    const similarities: {
      memoryId: string
      similarity: number
      textPreview: string
      textLength: number
      memoryType: string
      sourceId?: string
      sourceType?: string
      createdAt: number
    }[] = []

    for (const row of (result.results || [])) {
      const storedVector = JSON.parse(row.vector_json)
      const similarity = cosineSimilarity(queryVector, storedVector)
      
      if (similarity >= threshold) {
        similarities.push({
          memoryId: row.memory_id,
          similarity,
          textPreview: row.text_preview,
          textLength: row.text_length,
          memoryType: row.memory_type,
          sourceId: row.source_id,
          sourceType: row.source_type,
          createdAt: row.created_at,
        })
      }
    }

    // 按相似度降序排序
    similarities.sort((a, b) => b.similarity - a.similarity)

    // 返回 topK 个结果
    return jsonResponse({
      results: similarities.slice(0, topK),
      total: similarities.length,
      topK,
      threshold,
    })
  } catch (error) {
    console.error('Error searching vectors:', error)
    return jsonResponse({ error: 'Failed to search vectors' }, 500)
  }
}

// ============================================
// 路由处理
// ============================================

export async function handleVectorRoutes(
  request: Request,
  env: any,
  path: string
): Promise<Response | null> {
  // GET /api/v1/vectors - 获取向量列表
  if (request.method === 'GET' && path === '/api/v1/vectors') {
    return getVectors(request, env)
  }

  // POST /api/v1/vectors - 创建单个向量
  if (request.method === 'POST' && path === '/api/v1/vectors') {
    return createVector(request, env)
  }

  // POST /api/v1/vectors/batch - 批量创建向量
  if (request.method === 'POST' && path === '/api/v1/vectors/batch') {
    return batchCreateVectors(request, env)
  }

  // POST /api/v1/vectors/search - 语义搜索
  if (request.method === 'POST' && path === '/api/v1/vectors/search') {
    return searchVectors(request, env)
  }

  // DELETE /api/v1/vectors/:id - 删除向量
  const deleteMatch = path.match(/^\/api\/v1\/vectors\/([^/]+)$/)
  if (request.method === 'DELETE' && deleteMatch) {
    return deleteVector(request, env, deleteMatch[1])
  }

  return null
}
