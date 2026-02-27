import { Env, jsonResponse } from '../index'
import { getUserAuth } from '../utils/user-auth'

function decodeBase64ToBytes(dataBase64: string): Uint8Array {
  return Uint8Array.from(Buffer.from(dataBase64, 'base64'))
}

function encodeBytesToBase64(bytes: ArrayBuffer): string {
  return Buffer.from(bytes).toString('base64')
}

function getStorageKey(walletAddress: string, memoryId: string): string {
  return `memory-blobs/${walletAddress}/${memoryId}`
}

export async function handleMemoriesRoutes(request: Request, env: Env, path: string): Promise<Response | null> {
  if (path === '/api/v1/memories/blob' && request.method === 'POST') {
    return handleStoreMemoryBlob(request, env)
  }

  const migratedMatch = path.match(/^\/api\/v1\/memories\/blob\/([^/]+)\/migrated$/)
  if (migratedMatch && request.method === 'POST') {
    return handleMarkMigrated(request, env, migratedMatch[1])
  }

  const match = path.match(/^\/api\/v1\/memories\/blob\/([^/]+)$/)
  if (match && request.method === 'GET') {
    return handleGetMemoryBlob(request, env, match[1])
  }
  if (match && request.method === 'DELETE') {
    return handleDeleteMemoryBlob(request, env, match[1])
  }

  return null
}

async function handleMarkMigrated(request: Request, env: Env, memoryId: string): Promise<Response> {
  const auth = await getUserAuth(request, env)
  if (!auth.ok) return jsonResponse({ error: 'unauthorized', detail: auth.error }, 401)

  const walletAddress = auth.walletAddress
  const body = (await request.json()) as { irysId?: string; deleteBlob?: boolean }
  const irysId = (body.irysId || '').trim()
  const deleteBlob = body.deleteBlob !== false

  if (env.DB) {
    const row = await env.DB.prepare(
      'SELECT irys_id FROM memories WHERE id = ? AND wallet_address = ?'
    ).bind(memoryId, walletAddress).first() as any

    if (row) {
      const updateIrysId = irysId || row.irys_id || ''
      await env.DB.prepare(
        'UPDATE memories SET irys_id = ? WHERE id = ? AND wallet_address = ?'
      ).bind(updateIrysId, memoryId, walletAddress).run()
    }
  }

  if (deleteBlob) {
    await deleteStoredBlob(env, walletAddress, memoryId)
  }

  return jsonResponse({ success: true, memoryId })
}

async function handleDeleteMemoryBlob(request: Request, env: Env, memoryId: string): Promise<Response> {
  const auth = await getUserAuth(request, env)
  if (!auth.ok) return jsonResponse({ error: 'unauthorized', detail: auth.error }, 401)

  await deleteStoredBlob(env, auth.walletAddress, memoryId)
  return jsonResponse({ success: true, memoryId })
}

async function deleteStoredBlob(env: Env, walletAddress: string, memoryId: string): Promise<void> {
  const key = getStorageKey(walletAddress, memoryId)

  if (env.R2) {
    await env.R2.delete(key)
  }

  if (env.KV) {
    await env.KV.delete(`memory_blob:${walletAddress}:${memoryId}`)
  }
}

async function handleStoreMemoryBlob(request: Request, env: Env): Promise<Response> {
  const auth = await getUserAuth(request, env)
  if (!auth.ok) return jsonResponse({ error: 'unauthorized', detail: auth.error }, 401)

  const body = (await request.json()) as {
    walletAddress?: string
    wallet_address?: string
    memoryId?: string
    contentBase64?: string
    content_hash?: string
    contentHash?: string
    metadata?: Record<string, string>
    type?: string
  }

  const bodyWalletAddress = (body.walletAddress || body.wallet_address || '').trim()
  const memoryId = (body.memoryId || '').trim()
  const contentBase64 = (body.contentBase64 || '').trim()
  const contentHash = (body.contentHash || body.content_hash || '').trim()

  if (bodyWalletAddress && bodyWalletAddress !== auth.walletAddress) {
    return jsonResponse({ error: 'forbidden' }, 403)
  }
  const walletAddress = auth.walletAddress
  if (!memoryId || !contentBase64) {
    return jsonResponse({ error: 'Missing required fields', required: ['memoryId', 'contentBase64'] }, 400)
  }

  const bytes = decodeBase64ToBytes(contentBase64)
  const storageKey = getStorageKey(walletAddress, memoryId)

  let storedRef = ''
  if (env.R2) {
    await env.R2.put(storageKey, bytes, {
      httpMetadata: { contentType: 'application/octet-stream' },
      customMetadata: {
        walletAddress,
        memoryId,
        contentHash,
      },
    })
    storedRef = `r2:${storageKey}`
  } else if (env.KV) {
    const kvKey = `memory_blob:${walletAddress}:${memoryId}`
    await env.KV.put(kvKey, contentBase64)
    storedRef = `kv:${kvKey}`
  } else {
    return jsonResponse({ error: 'Server not configured' }, 500)
  }

  if (env.DB) {
    const user = await env.DB.prepare('SELECT id FROM users WHERE wallet_address = ?').bind(walletAddress).first() as any
    const userId = user?.id || `user_${walletAddress.substring(0, 8)}`
    const type = (body.type || body.metadata?.type || body.metadata?.Type || 'text').toString()
    const size = bytes.byteLength
    await env.DB.prepare(
      `INSERT INTO memories (id, user_id, wallet_address, type, irys_id, size)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         user_id = excluded.user_id,
         wallet_address = excluded.wallet_address,
         type = excluded.type,
         irys_id = excluded.irys_id,
         size = excluded.size`
    ).bind(memoryId, userId, walletAddress, type, storedRef, size).run()
  }

  return jsonResponse({
    success: true,
    memoryId,
    storage: storedRef.startsWith('r2:') ? 'r2' : 'kv',
    path: `/api/v1/memories/blob/${memoryId}`,
  })
}

async function handleGetMemoryBlob(request: Request, env: Env, memoryId: string): Promise<Response> {
  const auth = await getUserAuth(request, env)
  if (!auth.ok) return jsonResponse({ error: 'unauthorized', detail: auth.error }, 401)

  const walletAddress = auth.walletAddress
  let ref: string | null = null

  if (env.DB) {
    const row = await env.DB.prepare(
      'SELECT irys_id FROM memories WHERE id = ? AND wallet_address = ?'
    ).bind(memoryId, walletAddress).first() as any
    ref = row?.irys_id || null
  }

  if (!ref) {
    if (env.R2) ref = `r2:${getStorageKey(walletAddress, memoryId)}`
    else if (env.KV) ref = `kv:memory_blob:${walletAddress}:${memoryId}`
  }

  if (!ref) return jsonResponse({ error: 'Not found' }, 404)

  if (ref.startsWith('r2:')) {
    if (!env.R2) return jsonResponse({ error: 'Server not configured' }, 500)
    const key = ref.substring('r2:'.length)
    const obj = await env.R2.get(key)
    if (!obj) return jsonResponse({ error: 'Not found' }, 404)
    const bytes = await obj.arrayBuffer()
    return new Response(bytes, { status: 200, headers: { 'Content-Type': 'application/octet-stream' } })
  }

  if (ref.startsWith('kv:')) {
    if (!env.KV) return jsonResponse({ error: 'Server not configured' }, 500)
    const key = ref.substring('kv:'.length)
    const contentBase64 = await env.KV.get(key)
    if (!contentBase64) return jsonResponse({ error: 'Not found' }, 404)
    const bytes = decodeBase64ToBytes(contentBase64)
    const buffer = new Uint8Array(bytes).buffer
    return new Response(buffer, { status: 200, headers: { 'Content-Type': 'application/octet-stream' } })
  }

  return jsonResponse({ error: 'Not found' }, 404)
}
