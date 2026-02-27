
import { AdminContext, logAdminAction } from './middleware'
import {
  addShipCandyMachineConfigLines,
  getAdminUmi,
  applyShipSoulboundOracle,
  createShipCandyGuardAndWrap,
  createShipCandyMachineWithGuards,
  createShipSoulboundCollection,
  diagnoseShipAdminSigner,
} from '../../services/ship-core'
import { createUmi } from '@metaplex-foundation/umi-bundle-defaults'
import { createNoopSigner, signerIdentity, unwrapOption, publicKey } from '@metaplex-foundation/umi'
import { mplCore } from '@metaplex-foundation/mpl-core'
import { fetchCandyMachine, mplCandyMachine } from '@metaplex-foundation/mpl-core-candy-machine'
import { Connection, PublicKey } from '@solana/web3.js'
import { getSolanaRpcUrl } from '../../utils/solana-rpc'
import { signConfigValue } from '../../utils/config-signature'

function isBase58String(value: string): boolean {
  return /^[1-9A-HJ-NP-Za-km-z]+$/.test(value)
}

function stripZeroWidth(value: string): string {
  return value.replace(/[\u200B-\u200D\uFEFF]/g, '')
}

async function waitForSignatureFinal(env: any, signature: string, timeoutMs = 90_000): Promise<void> {
  const connection = new Connection(getSolanaRpcUrl(env), { commitment: 'confirmed' } as any)
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const res = await connection.getSignatureStatuses([signature], { searchTransactionHistory: true } as any)
    const st = res?.value?.[0]
    if (st) {
      if (st.err) throw new Error(`Transaction failed: ${JSON.stringify(st.err)}`)
      if (st.confirmationStatus === 'confirmed' || st.confirmationStatus === 'finalized') return
    }
    await new Promise((r) => setTimeout(r, 1500))
  }
  throw new Error('Transaction not confirmed before timeout')
}

const MPL_CORE_CANDY_GUARD_PROGRAM_ID = new PublicKey('CMAGAKJ67e9hRZgfC5SFTbZH8MgEmtqazKXjmkaJjWTJ')

function getReadOnlyUmi(env: any) {
  const rpcUrl = getSolanaRpcUrl(env)
  const noop = createNoopSigner(publicKey('11111111111111111111111111111111'))
  return createUmi(rpcUrl).use(mplCandyMachine()).use(mplCore()).use(signerIdentity(noop))
}

async function getPlainConfigValue(env: any, key: string): Promise<string | null> {
  if (env.DB) {
    const row = await env.DB.prepare(
      'SELECT config_value FROM app_config WHERE config_key = ? AND is_active = 1'
    ).bind(key).first()
    const v = (row?.config_value || '').trim()
    if (v) return v
  }
  if (env.KV) {
    const raw = await env.KV.get(`config:${key}`)
    const v0 = String(raw || '').trim()
    if (!v0) return null
    if (v0.startsWith('{') && v0.endsWith('}')) {
      try {
        const parsed = JSON.parse(v0)
        const v = String(parsed?.value || '').trim()
        if (v) return v
      } catch {}
    }
    return v0
  }
  return null
}

async function setPlainConfigValue(env: any, adminContext: AdminContext, key: string, value: string, now: number): Promise<void> {
  if (!env.DB) return
  const existing = await env.DB.prepare(
    'SELECT config_value FROM app_config WHERE config_key = ?'
  ).bind(key).first()

  if (existing) {
    await env.DB.prepare(
      'UPDATE app_config SET config_value = ?, updated_by = ?, updated_at = ?, is_active = 1 WHERE config_key = ?'
    ).bind(value, adminContext.email, now, key).run()
  } else {
    await env.DB.prepare(
      `INSERT INTO app_config
       (config_key, config_value, value_type, category, sub_category, display_name, description, default_value, is_sensitive, requires_restart, is_active, updated_by, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(
      key,
      value,
      'string',
      'game',
      'ship',
      key,
      key,
      '',
      0,
      0,
      1,
      adminContext.email,
      now,
      now
    ).run()
  }

  if (env.KV) {
    const signature = await signConfigValue(env, key, value)
    const storedValue = signature
      ? JSON.stringify({ value, signature, timestamp: Date.now() })
      : value
    await env.KV.put(`config:${key}`, storedValue)
  }
}

async function getConfigSnapshot(env: any, keys: string[]): Promise<Record<string, string | null>> {
  const out: Record<string, string | null> = {}
  for (const k of keys) {
    out[k] = await getPlainConfigValue(env, k)
  }
  return out
}

type GamePort = {
  id: string
  name: string
  description: string
  coordinates: string
  unlock_level: number
}

type GameGood = {
  id: string
  name: string
  description: string
  base_price: number
  volatility: number
}

type GameDungeon = {
  id: string
  name: string
  description: string
  difficulty_level: number
  max_depth: number
  entry_cost: number
  created_at?: number
  updated_at?: number
}

type TimelineEvent = {
  id: string
  year_ae: number
  title: string
  description: string
  event_type: string
  is_unlocked: number | boolean
  required_season_id: string | null
  created_at?: number
  updated_at?: number
}

/**
 * Get Lore Entries
 */
export async function getLoreEntries(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  const url = new URL(request.url)
  const page = parseInt(url.searchParams.get('page') || '1')
  const pageSize = parseInt(url.searchParams.get('pageSize') || '20')
  const category = url.searchParams.get('category')
  const sourceType = url.searchParams.get('sourceType')

  try {
    if (env.DB) {
      let whereClause = '1=1'
      const params: any[] = []
      
      if (category) {
        whereClause += ' AND category = ?'
        params.push(category)
      }
      if (sourceType) {
        whereClause += ' AND source_type = ?'
        params.push(sourceType)
      }

      const countResult = await env.DB.prepare(
        `SELECT COUNT(*) as count FROM game_lore_entries WHERE ${whereClause}`
      ).bind(...params).first()
      const total = countResult?.count || 0

      const offset = (page - 1) * pageSize
      const dataParams = [...params, pageSize, offset]
      const result = await env.DB.prepare(
        `SELECT * FROM game_lore_entries WHERE ${whereClause} ORDER BY created_at DESC LIMIT ? OFFSET ?`
      ).bind(...dataParams).all()

      const records = (result.results || []).map((row: any) => ({
        id: row.id,
        seasonId: row.season_id,
        title: row.title,
        content: row.content,
        category: row.category,
        sourceType: row.source_type,
        sourceTarget: row.source_target,
        dropChance: row.drop_chance,
        unlockThreshold: row.unlock_threshold,
        createdAt: row.created_at,
        updatedAt: row.updated_at
      }))

      return new Response(JSON.stringify({ records, total, page, pageSize }), {
        headers: { 'Content-Type': 'application/json' },
      })
    }

    return new Response(JSON.stringify({ records: [], total: 0, page, pageSize }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error getting lore entries:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to get lore entries' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * Create Lore Entry
 */
export async function createLoreEntry(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { id, seasonId, title, content, category, sourceType, sourceTarget, dropChance, unlockThreshold } = body

    if (!id || !title || !content) {
        return new Response(
            JSON.stringify({ error: 'Missing required fields' }),
            { status: 400, headers: { 'Content-Type': 'application/json' } }
        )
    }

    if (env.DB) {
      const now = Math.floor(Date.now() / 1000)
      await env.DB.prepare(
        `INSERT INTO game_lore_entries (id, season_id, title, content, category, source_type, source_target, drop_chance, unlock_threshold, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      ).bind(
        id, 
        seasonId || 'season_01', 
        title, 
        content, 
        category || 'MAIN', 
        sourceType || 'CONTRIBUTION', 
        sourceTarget || null, 
        dropChance || 0, 
        unlockThreshold || 0, 
        now, 
        now
      ).run()
    }

    await logAdminAction(env, adminContext, 'CREATE_LORE', 'game', id, `Created lore: ${title}`)

    return new Response(JSON.stringify({ success: true, id }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error creating lore entry:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to create lore entry' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * Update Lore Entry
 */
export async function updateLoreEntry(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { title, content, category, sourceType, sourceTarget, dropChance, unlockThreshold } = body

    if (env.DB) {
      const now = Math.floor(Date.now() / 1000)
      await env.DB.prepare(
        `UPDATE game_lore_entries SET title = ?, content = ?, category = ?, source_type = ?, source_target = ?, drop_chance = ?, unlock_threshold = ?, updated_at = ? WHERE id = ?`
      ).bind(
        title, 
        content, 
        category, 
        sourceType, 
        sourceTarget, 
        dropChance, 
        unlockThreshold, 
        now, 
        id
      ).run()
    }

    await logAdminAction(env, adminContext, 'UPDATE_LORE', 'game', id, `Updated lore: ${title}`)

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error updating lore entry:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to update lore entry' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

/**
 * Delete Lore Entry
 */
export async function deleteLoreEntry(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM game_lore_entries WHERE id = ?').bind(id).run()
    }

    await logAdminAction(env, adminContext, 'DELETE_LORE', 'game', id, 'Deleted lore entry')

    return new Response(JSON.stringify({ success: true }), {
      headers: { 'Content-Type': 'application/json' },
    })
  } catch (error) {
    console.error('Error deleting lore entry:', error)
    return new Response(
      JSON.stringify({ error: 'Failed to delete lore entry' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )
  }
}

export async function getPorts(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ records: [] }), { headers: { 'Content-Type': 'application/json' } })
    const result = await env.DB.prepare('SELECT * FROM game_ports ORDER BY id ASC').all()
    const records = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      description: row.description,
      coordinates: row.coordinates,
      unlockLevel: row.unlock_level
    }))
    return new Response(JSON.stringify({ records }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error getting ports:', error)
    return new Response(JSON.stringify({ error: 'Failed to get ports' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function createPort(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { id, name, description, coordinates, unlockLevel } = body
    if (!id || !name) return new Response(JSON.stringify({ error: 'Missing required fields' }), { status: 400, headers: { 'Content-Type': 'application/json' } })

    if (env.DB) {
      await env.DB.prepare(
        'INSERT INTO game_ports (id, name, description, coordinates, unlock_level) VALUES (?, ?, ?, ?, ?)'
      ).bind(
        id,
        name,
        description || '',
        coordinates || '0,0',
        unlockLevel ?? 1
      ).run()
    }

    await logAdminAction(env, adminContext, 'CREATE_PORT', 'game', id, `Created port: ${name}`)
    return new Response(JSON.stringify({ success: true, id }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error creating port:', error)
    return new Response(JSON.stringify({ error: 'Failed to create port' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function updatePort(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { name, description, coordinates, unlockLevel } = body
    if (env.DB) {
      await env.DB.prepare(
        'UPDATE game_ports SET name = ?, description = ?, coordinates = ?, unlock_level = ? WHERE id = ?'
      ).bind(
        name,
        description || '',
        coordinates || '0,0',
        unlockLevel ?? 1,
        id
      ).run()
    }
    await logAdminAction(env, adminContext, 'UPDATE_PORT', 'game', id, `Updated port: ${name || id}`)
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error updating port:', error)
    return new Response(JSON.stringify({ error: 'Failed to update port' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function deletePort(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM game_ports WHERE id = ?').bind(id).run()
    }
    await logAdminAction(env, adminContext, 'DELETE_PORT', 'game', id, 'Deleted port')
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error deleting port:', error)
    return new Response(JSON.stringify({ error: 'Failed to delete port' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function getGoods(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ records: [] }), { headers: { 'Content-Type': 'application/json' } })
    const result = await env.DB.prepare('SELECT * FROM game_goods ORDER BY id ASC').all()
    const records = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      description: row.description,
      basePrice: row.base_price,
      volatility: row.volatility
    }))
    return new Response(JSON.stringify({ records }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error getting goods:', error)
    return new Response(JSON.stringify({ error: 'Failed to get goods' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function createGood(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { id, name, description, basePrice, volatility } = body
    if (!id || !name || basePrice == null) return new Response(JSON.stringify({ error: 'Missing required fields' }), { status: 400, headers: { 'Content-Type': 'application/json' } })

    if (env.DB) {
      await env.DB.prepare(
        'INSERT INTO game_goods (id, name, description, base_price, volatility) VALUES (?, ?, ?, ?, ?)'
      ).bind(
        id,
        name,
        description || '',
        basePrice,
        volatility ?? 0.1
      ).run()
    }

    await logAdminAction(env, adminContext, 'CREATE_GOOD', 'game', id, `Created good: ${name}`)
    return new Response(JSON.stringify({ success: true, id }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error creating good:', error)
    return new Response(JSON.stringify({ error: 'Failed to create good' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function updateGood(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { name, description, basePrice, volatility } = body
    if (env.DB) {
      await env.DB.prepare(
        'UPDATE game_goods SET name = ?, description = ?, base_price = ?, volatility = ? WHERE id = ?'
      ).bind(
        name,
        description || '',
        basePrice,
        volatility ?? 0.1,
        id
      ).run()
    }
    await logAdminAction(env, adminContext, 'UPDATE_GOOD', 'game', id, `Updated good: ${name || id}`)
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error updating good:', error)
    return new Response(JSON.stringify({ error: 'Failed to update good' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function deleteGood(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM game_goods WHERE id = ?').bind(id).run()
    }
    await logAdminAction(env, adminContext, 'DELETE_GOOD', 'game', id, 'Deleted good')
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error deleting good:', error)
    return new Response(JSON.stringify({ error: 'Failed to delete good' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function getDungeons(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ records: [] }), { headers: { 'Content-Type': 'application/json' } })
    const result = await env.DB.prepare('SELECT * FROM game_dungeons ORDER BY id ASC').all()
    const records = (result.results || []).map((row: any) => ({
      id: row.id,
      name: row.name,
      description: row.description,
      difficultyLevel: row.difficulty_level,
      maxDepth: row.max_depth,
      entryCost: row.entry_cost
    }))
    return new Response(JSON.stringify({ records }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error getting dungeons:', error)
    return new Response(JSON.stringify({ error: 'Failed to get dungeons' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function createDungeon(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { id, name, description, difficultyLevel, maxDepth, entryCost } = body
    if (!id || !name) return new Response(JSON.stringify({ error: 'Missing required fields' }), { status: 400, headers: { 'Content-Type': 'application/json' } })

    if (env.DB) {
      const now = Math.floor(Date.now() / 1000)
      await env.DB.prepare(
        'INSERT INTO game_dungeons (id, name, description, difficulty_level, max_depth, entry_cost, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
      ).bind(
        id,
        name,
        description || '',
        difficultyLevel ?? 1,
        maxDepth ?? 5,
        entryCost ?? 100,
        now,
        now
      ).run()
    }

    await logAdminAction(env, adminContext, 'CREATE_DUNGEON', 'game', id, `Created dungeon: ${name}`)
    return new Response(JSON.stringify({ success: true, id }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error creating dungeon:', error)
    return new Response(JSON.stringify({ error: 'Failed to create dungeon' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function updateDungeon(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { name, description, difficultyLevel, maxDepth, entryCost } = body
    if (env.DB) {
      const now = Math.floor(Date.now() / 1000)
      await env.DB.prepare(
        'UPDATE game_dungeons SET name = ?, description = ?, difficulty_level = ?, max_depth = ?, entry_cost = ?, updated_at = ? WHERE id = ?'
      ).bind(
        name,
        description || '',
        difficultyLevel ?? 1,
        maxDepth ?? 5,
        entryCost ?? 100,
        now,
        id
      ).run()
    }
    await logAdminAction(env, adminContext, 'UPDATE_DUNGEON', 'game', id, `Updated dungeon: ${name || id}`)
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error updating dungeon:', error)
    return new Response(JSON.stringify({ error: 'Failed to update dungeon' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function deleteDungeon(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM game_dungeons WHERE id = ?').bind(id).run()
    }
    await logAdminAction(env, adminContext, 'DELETE_DUNGEON', 'game', id, 'Deleted dungeon')
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error deleting dungeon:', error)
    return new Response(JSON.stringify({ error: 'Failed to delete dungeon' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function getTimelineEvents(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ records: [] }), { headers: { 'Content-Type': 'application/json' } })
    const result = await env.DB.prepare('SELECT * FROM game_timeline_events ORDER BY year_ae ASC').all()
    const records = (result.results || []).map((row: any) => ({
      id: row.id,
      yearAe: row.year_ae,
      title: row.title,
      description: row.description,
      eventType: row.event_type,
      isUnlocked: !!row.is_unlocked,
      requiredSeasonId: row.required_season_id
    }))
    return new Response(JSON.stringify({ records }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error getting timeline events:', error)
    return new Response(JSON.stringify({ error: 'Failed to get timeline events' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function createTimelineEvent(
  request: Request,
  env: any,
  adminContext: AdminContext
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { id, yearAe, title, description, eventType, isUnlocked, requiredSeasonId } = body
    if (!id || !title || yearAe == null) return new Response(JSON.stringify({ error: 'Missing required fields' }), { status: 400, headers: { 'Content-Type': 'application/json' } })

    if (env.DB) {
      const now = Math.floor(Date.now() / 1000)
      await env.DB.prepare(
        `INSERT INTO game_timeline_events (id, year_ae, title, description, event_type, is_unlocked, required_season_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
      ).bind(
        id,
        yearAe,
        title,
        description || '',
        eventType || 'FIXED',
        isUnlocked ? 1 : 0,
        requiredSeasonId || null,
        now,
        now
      ).run()
    }

    await logAdminAction(env, adminContext, 'CREATE_EVENT', 'game', id, `Created event: ${title}`)
    return new Response(JSON.stringify({ success: true, id }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error creating timeline event:', error)
    return new Response(JSON.stringify({ error: 'Failed to create timeline event' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function updateTimelineEvent(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    const body = await request.json() as any
    const { yearAe, title, description, eventType, isUnlocked, requiredSeasonId } = body
    if (env.DB) {
      const now = Math.floor(Date.now() / 1000)
      await env.DB.prepare(
        `UPDATE game_timeline_events
         SET year_ae = ?, title = ?, description = ?, event_type = ?, is_unlocked = ?, required_season_id = ?, updated_at = ?
         WHERE id = ?`
      ).bind(
        yearAe,
        title,
        description || '',
        eventType || 'FIXED',
        isUnlocked ? 1 : 0,
        requiredSeasonId || null,
        now,
        id
      ).run()
    }

    await logAdminAction(env, adminContext, 'UPDATE_EVENT', 'game', id, `Updated event: ${title || id}`)
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error updating timeline event:', error)
    return new Response(JSON.stringify({ error: 'Failed to update timeline event' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

export async function deleteTimelineEvent(
  request: Request,
  env: any,
  adminContext: AdminContext,
  id: string
): Promise<Response> {
  try {
    if (env.DB) {
      await env.DB.prepare('DELETE FROM game_timeline_events WHERE id = ?').bind(id).run()
    }
    await logAdminAction(env, adminContext, 'DELETE_EVENT', 'game', id, 'Deleted event')
    return new Response(JSON.stringify({ success: true }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error deleting timeline event:', error)
    return new Response(JSON.stringify({ error: 'Failed to delete timeline event' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

/**
 * Handle Game Admin Routes
 */
export async function handleGameRoutes(
  request: Request,
  env: any,
  adminContext: AdminContext,
  path: string,
  ctx?: any
): Promise<Response | null> {
  if (request.method === 'GET' && path === '/admin/game/ship/core/candy-machine/wrap/status') {
    const url = new URL(request.url)
    const candyMachineAddress = stripZeroWidth(String(url.searchParams.get('candyMachineAddress') || '')).trim()
    if (!candyMachineAddress) {
      return new Response(JSON.stringify({ error: 'candyMachineAddress required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    }
    if (!isBase58String(candyMachineAddress)) {
      return new Response(JSON.stringify({ error: 'candyMachineAddress invalid (base58)' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    }

    try {
      const umi = getReadOnlyUmi(env)
      const cmPk = publicKey(candyMachineAddress)
      const cm = await fetchCandyMachine(umi as any, cmPk)
      const authority = cm.authority.toString()
      const mintAuthority = cm.mintAuthority.toString()
      const basePk = new PublicKey(authority)
      const [expectedPda] = PublicKey.findProgramAddressSync([Buffer.from('candy_guard'), basePk.toBytes()], MPL_CORE_CANDY_GUARD_PROGRAM_ID)
      const expectedCandyGuardAddress = expectedPda.toBase58()
      const lastWrapSignature = await getPlainConfigValue(env, 'game.ship.core.wrap_signature')

      return new Response(JSON.stringify({
        candyMachineAddress,
        authority,
        mintAuthority,
        expectedCandyGuardAddress,
        isWrapped: mintAuthority === expectedCandyGuardAddress,
        lastWrapSignature: lastWrapSignature || null,
      }), { headers: { 'Content-Type': 'application/json' } })
    } catch (e: any) {
      return new Response(JSON.stringify({ error: String(e?.message || e) }), { status: 500, headers: { 'Content-Type': 'application/json' } })
    }
  }

  if (request.method === 'GET' && path === '/admin/game/ship/core/sale/readiness') {
    const url = new URL(request.url)
    const fromQuery = stripZeroWidth(String(url.searchParams.get('candyMachineAddress') || '')).trim()
    const configuredCandyMachine = await getPlainConfigValue(env, 'game.ship.core.candy_machine')
    const candyMachineAddress = fromQuery || configuredCandyMachine || ''

    if (!candyMachineAddress) {
      return new Response(JSON.stringify({ error: 'candyMachineAddress required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    }
    if (!isBase58String(candyMachineAddress)) {
      return new Response(JSON.stringify({ error: 'candyMachineAddress invalid (base58)' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    }

    const requiredKeys = [
      'game.ship.collection',
      'game.ship.core.candy_machine',
      'game.ship.core.candy_guard_address',
      'game.ship.core.wrap_signature',
      'game.ship.mint.enabled',
      'game.ship.mint.recipient',
      'game.ship.mint.price_lamports',
      'game.ship.mint.start_at',
    ]
    const recommendedKeys: string[] = []

    try {
      const cfg = await getConfigSnapshot(env, [...requiredKeys, ...recommendedKeys])
      const requiredMissing = requiredKeys.filter((k) => !String(cfg[k] || '').trim())
      const recommendedMissing = recommendedKeys.filter((k) => !String(cfg[k] || '').trim())

      const umi = getReadOnlyUmi(env)
      const cmPk = publicKey(candyMachineAddress)
      const cm = await fetchCandyMachine(umi as any, cmPk)
      const authority = cm.authority.toString()
      const mintAuthority = cm.mintAuthority.toString()

      const basePk = new PublicKey(authority)
      const [expectedPda] = PublicKey.findProgramAddressSync([Buffer.from('candy_guard'), basePk.toBytes()], MPL_CORE_CANDY_GUARD_PROGRAM_ID)
      const expectedCandyGuardAddress = expectedPda.toBase58()
      const isWrapped = mintAuthority === expectedCandyGuardAddress

      const mintEnabledRaw = String(cfg['game.ship.mint.enabled'] || '').trim()
      const mintEnabled = mintEnabledRaw === '1' || mintEnabledRaw.toLowerCase() === 'true'
      const startAt = Number(String(cfg['game.ship.mint.start_at'] || '').trim() || '0')
      const priceLamports = Number(String(cfg['game.ship.mint.price_lamports'] || '').trim() || '0')
      const recipient = String(cfg['game.ship.mint.recipient'] || '').trim() || null

      const reasons: string[] = []
      for (const k of requiredMissing) reasons.push(`missing:${k}`)
      if (!isWrapped) reasons.push('not_wrapped')
      if (configuredCandyMachine && fromQuery && configuredCandyMachine !== fromQuery) reasons.push('config_mismatch:game.ship.core.candy_machine')

      const ready = reasons.length === 0

      return new Response(JSON.stringify({
        ready,
        reasons,
        candyMachineAddress,
        config: {
          required: cfg,
          requiredMissing,
          recommendedMissing,
        },
        wrap: {
          authority,
          mintAuthority,
          expectedCandyGuardAddress,
          isWrapped,
          lastWrapSignature: (await getPlainConfigValue(env, 'game.ship.core.wrap_signature')) || null,
        },
        mint: {
          mintEnabled,
          startAt,
          priceLamports,
          recipient,
        },
      }), { headers: { 'Content-Type': 'application/json' } })
    } catch (e: any) {
      return new Response(JSON.stringify({ error: String(e?.message || e) }), { status: 500, headers: { 'Content-Type': 'application/json' } })
    }
  }

  if (request.method === 'GET' && path === '/admin/game/ship/core/admin-signer/status') {
    const diag = diagnoseShipAdminSigner(env)
    return new Response(JSON.stringify({ success: true, ...diag }), { headers: { 'Content-Type': 'application/json' } })
  }

  if (request.method === 'POST' && path === '/admin/game/ship/core/collection/create') {
    return createShipCollectionEndpoint(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/game/ship/core/collection/apply-oracle') {
    return applyShipOracleEndpoint(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/game/ship/core/candy-machine/create') {
    return createShipCandyMachineEndpoint(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/game/ship/core/candy-machine/wrap-guards') {
    return wrapShipCandyMachineWithGuardsEndpoint(request, env, adminContext, ctx)
  }

  if (request.method === 'POST' && path === '/admin/game/ship/core/candy-machine/add-lines') {
    return addShipCandyMachineLinesEndpoint(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/game/ship/core/candy-machine/add-lines-generated') {
    return addShipCandyMachineLinesGeneratedEndpoint(request, env, adminContext, ctx)
  }

  const jobMatch = path.match(/^\/admin\/game\/ship\/core\/jobs\/([^/]+)$/)
  if (request.method === 'GET' && jobMatch) {
    return getShipCoreJob(request, env, adminContext, jobMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/game/ship/metadata/stats') {
    return getShipMetadataStats(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/game/ship/metadata/import') {
    return importShipMetadata(request, env, adminContext)
  }

  if (request.method === 'GET' && path === '/admin/game/lore') {
    return getLoreEntries(request, env, adminContext)
  }

  if (request.method === 'POST' && path === '/admin/game/lore') {
    return createLoreEntry(request, env, adminContext)
  }

  const updateMatch = path.match(/^\/admin\/game\/lore\/([^/]+)$/)
  if (request.method === 'PUT' && updateMatch) {
    return updateLoreEntry(request, env, adminContext, updateMatch[1])
  }

  if (request.method === 'DELETE' && updateMatch) {
    return deleteLoreEntry(request, env, adminContext, updateMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/game/ports') {
    return getPorts(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/game/ports') {
    return createPort(request, env, adminContext)
  }
  const portMatch = path.match(/^\/admin\/game\/ports\/([^/]+)$/)
  if (request.method === 'PUT' && portMatch) {
    return updatePort(request, env, adminContext, portMatch[1])
  }
  if (request.method === 'DELETE' && portMatch) {
    return deletePort(request, env, adminContext, portMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/game/goods') {
    return getGoods(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/game/goods') {
    return createGood(request, env, adminContext)
  }
  const goodMatch = path.match(/^\/admin\/game\/goods\/([^/]+)$/)
  if (request.method === 'PUT' && goodMatch) {
    return updateGood(request, env, adminContext, goodMatch[1])
  }
  if (request.method === 'DELETE' && goodMatch) {
    return deleteGood(request, env, adminContext, goodMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/game/dungeons') {
    return getDungeons(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/game/dungeons') {
    return createDungeon(request, env, adminContext)
  }
  const dungeonMatch = path.match(/^\/admin\/game\/dungeons\/([^/]+)$/)
  if (request.method === 'PUT' && dungeonMatch) {
    return updateDungeon(request, env, adminContext, dungeonMatch[1])
  }
  if (request.method === 'DELETE' && dungeonMatch) {
    return deleteDungeon(request, env, adminContext, dungeonMatch[1])
  }

  if (request.method === 'GET' && path === '/admin/game/events') {
    return getTimelineEvents(request, env, adminContext)
  }
  if (request.method === 'POST' && path === '/admin/game/events') {
    return createTimelineEvent(request, env, adminContext)
  }
  const eventMatch = path.match(/^\/admin\/game\/events\/([^/]+)$/)
  if (request.method === 'PUT' && eventMatch) {
    return updateTimelineEvent(request, env, adminContext, eventMatch[1])
  }
  if (request.method === 'DELETE' && eventMatch) {
    return deleteTimelineEvent(request, env, adminContext, eventMatch[1])
  }

  return null
}

async function createShipCollectionEndpoint(request: Request, env: any, adminContext: AdminContext): Promise<Response> {
  try {
    const body = await request.json() as { name?: string; uri: string; oracleBaseAddress?: string }
    const name = (body.name || 'Seeker Spaceship').trim()
    const uri = (body.uri || '').trim()
    if (!uri) return new Response(JSON.stringify({ error: 'uri required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    const res = await createShipSoulboundCollection({ env, name, uri, oracleBaseAddress: body.oracleBaseAddress })
    await logAdminAction(env, adminContext, 'CREATE_SHIP_COLLECTION', 'game', 'ship_collection', `collection=${res.collectionAddress}`)
    return new Response(JSON.stringify({ success: true, ...res }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error: any) {
    console.error('Error creating ship collection:', error)
    return new Response(JSON.stringify({ error: error?.message || 'Failed to create collection' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function applyShipOracleEndpoint(request: Request, env: any, adminContext: AdminContext): Promise<Response> {
  try {
    const body = await request.json() as { collectionAddress: string; oracleBaseAddress?: string }
    const collectionAddress = (body.collectionAddress || '').trim()
    if (!collectionAddress) return new Response(JSON.stringify({ error: 'collectionAddress required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    const res = await applyShipSoulboundOracle({ env, collectionAddress, oracleBaseAddress: body.oracleBaseAddress })
    await logAdminAction(env, adminContext, 'APPLY_SHIP_ORACLE', 'game', 'ship_collection', `collection=${collectionAddress} sig=${res.signature}`)
    return new Response(JSON.stringify({ success: true, ...res }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error: any) {
    console.error('Error applying ship oracle:', error)
    return new Response(JSON.stringify({ error: error?.message || 'Failed to apply oracle' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function createShipCandyMachineEndpoint(request: Request, env: any, adminContext: AdminContext): Promise<Response> {
  try {
    const body = await request.json() as {
      collectionAddress: string
      itemsAvailable?: number
      prefixName?: string
      nameLength?: number
      prefixUri?: string
      uriLength?: number
      isSequential?: boolean
      mintPriceLamports: number
      mintRecipient: string
      botTaxLamports?: number
      startAt?: number
      mintLimit?: number
      mintLimitId?: number
    }
    const collectionAddress = (body.collectionAddress || '').trim()
    const mintRecipient = (body.mintRecipient || '').trim()
    if (!collectionAddress) return new Response(JSON.stringify({ error: 'collectionAddress required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    if (!mintRecipient) return new Response(JSON.stringify({ error: 'mintRecipient required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    const mintPriceLamports = Number(body.mintPriceLamports)
    if (!Number.isFinite(mintPriceLamports) || mintPriceLamports < 0) {
      return new Response(JSON.stringify({ error: 'mintPriceLamports invalid' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    }
    const res = await createShipCandyMachineWithGuards({
      env,
      collectionAddress,
      itemsAvailable: body.itemsAvailable ?? 100_000,
      configLineSettings: {
        prefixName: body.prefixName ?? 'Seeker Spaceship #',
        nameLength: body.nameLength ?? 6,
        prefixUri: body.prefixUri ?? '',
        uriLength: body.uriLength ?? 200,
        isSequential: body.isSequential ?? false,
      },
      mintPriceLamports,
      mintRecipient,
      botTaxLamports: body.botTaxLamports ?? 10_000_000,
      startAt: body.startAt ?? 0,
      mintLimit: body.mintLimit ?? 1,
      mintLimitId: body.mintLimitId ?? 1,
    })
    await logAdminAction(env, adminContext, 'CREATE_SHIP_CANDY_MACHINE', 'game', 'ship_candy_machine', `cm=${res.candyMachineAddress} guard=${res.candyGuardAddress}`)
    return new Response(JSON.stringify({ success: true, ...res }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error: any) {
    console.error('Error creating ship candy machine:', error)
    return new Response(JSON.stringify({ error: error?.message || 'Failed to create candy machine' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function wrapShipCandyMachineWithGuardsEndpoint(
  request: Request,
  env: any,
  adminContext: AdminContext,
  ctx?: any
): Promise<Response> {
  try {
    const url = new URL(request.url)
    const body = await request.json() as {
      candyMachineAddress: string
      mintPriceLamports: number
      mintRecipient: string
      botTaxLamports?: number
      startAt?: number
      mintLimit?: number
      mintLimitId?: number
    }
    const candyMachineAddress = stripZeroWidth(body.candyMachineAddress || '').trim().replace(/\s+/g, '')
    const mintRecipient = stripZeroWidth(body.mintRecipient || '').trim().replace(/\s+/g, '')
    if (!candyMachineAddress) return new Response(JSON.stringify({ error: 'candyMachineAddress required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    if (!mintRecipient) return new Response(JSON.stringify({ error: 'mintRecipient required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    if (!isBase58String(candyMachineAddress)) return new Response(JSON.stringify({ error: 'candyMachineAddress invalid (base58)' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    if (!isBase58String(mintRecipient)) return new Response(JSON.stringify({ error: 'mintRecipient invalid (base58)' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    const mintPriceLamports = Number(body.mintPriceLamports)
    if (!Number.isFinite(mintPriceLamports) || mintPriceLamports < 0) {
      return new Response(JSON.stringify({ error: 'mintPriceLamports invalid' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    }

    const asyncMode = url.searchParams.get('async') === '1'
    if (asyncMode) {
      if (!env.KV) {
        return new Response(JSON.stringify({ error: 'KV not available' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      }
      if (!ctx?.waitUntil) {
        return new Response(JSON.stringify({ error: 'async not supported' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      }

      const jobId = (globalThis as any).crypto?.randomUUID
        ? (globalThis as any).crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(16).slice(2)}`
      const jobKey = `ship:core:job:${jobId}`
      const createdAt = Date.now()
      const params = {
        candyMachineAddress,
        mintPriceLamports,
        mintRecipient,
        botTaxLamports: body.botTaxLamports ?? 10_000_000,
        startAt: body.startAt ?? 0,
        mintLimit: body.mintLimit ?? 1,
        mintLimitId: body.mintLimitId ?? 1,
      }

      await env.KV.put(jobKey, JSON.stringify({
        id: jobId,
        type: 'wrap_guards',
        status: 'queued',
        createdAt,
        params,
      }))

      ctx.waitUntil((async () => {
        try {
          await env.KV.put(jobKey, JSON.stringify({
            id: jobId,
            type: 'wrap_guards',
            status: 'running',
            createdAt,
            startedAt: Date.now(),
            params,
          }))

          const res = await createShipCandyGuardAndWrap({ env, ...params, confirm: false })
          await env.KV.put(jobKey, JSON.stringify({
            id: jobId,
            type: 'wrap_guards',
            status: 'submitted',
            createdAt,
            startedAt: Date.now(),
            params,
            result: res,
          }))

          await waitForSignatureFinal(env, res.wrapSignature, 90_000)
          const now = Date.now()
          await setPlainConfigValue(env, adminContext, 'game.ship.core.candy_guard_address', res.candyGuardAddress, now)
          await setPlainConfigValue(env, adminContext, 'game.ship.core.wrap_signature', res.wrapSignature, now)
          await logAdminAction(env, adminContext, 'WRAP_SHIP_CANDY_MACHINE_GUARDS', 'game', 'ship_candy_machine', `cm=${candyMachineAddress} guard=${res.candyGuardAddress} sig=${res.wrapSignature}`)

          await env.KV.put(jobKey, JSON.stringify({
            id: jobId,
            type: 'wrap_guards',
            status: 'done',
            createdAt,
            finishedAt: Date.now(),
            params,
            result: res,
          }))
        } catch (e: any) {
          try {
            const message = String(e?.message || e)
            const stack = typeof e?.stack === 'string' ? e.stack : ''
            const mapped = message.includes('Non-base58 character')
              ? `Non-base58 character (likely invalid admin private key format or hidden characters). raw=${message}`
              : message
            await env.KV.put(jobKey, JSON.stringify({
              id: jobId,
              type: 'wrap_guards',
              status: 'error',
              createdAt,
              finishedAt: Date.now(),
              params,
              error: mapped,
              errorStack: stack ? String(stack).split('\n').slice(0, 12).join('\n') : null,
            }))
          } catch {}
        }
      })())

      return new Response(JSON.stringify({ success: true, accepted: true, jobId, jobKey }), { status: 202, headers: { 'Content-Type': 'application/json' } })
    }

    const res = await createShipCandyGuardAndWrap({
      env,
      candyMachineAddress,
      mintPriceLamports,
      mintRecipient,
      botTaxLamports: body.botTaxLamports ?? 10_000_000,
      startAt: body.startAt ?? 0,
      mintLimit: body.mintLimit ?? 1,
      mintLimitId: body.mintLimitId ?? 1,
    })
    await logAdminAction(env, adminContext, 'WRAP_SHIP_CANDY_MACHINE_GUARDS', 'game', 'ship_candy_machine', `cm=${candyMachineAddress} guard=${res.candyGuardAddress}`)
    return new Response(JSON.stringify({ success: true, ...res }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error: any) {
    console.error('Error wrapping ship candy machine guards:', error)
    return new Response(JSON.stringify({ error: error?.message || 'Failed to wrap candy machine guards' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function addShipCandyMachineLinesEndpoint(request: Request, env: any, adminContext: AdminContext): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ error: 'Database not available' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
    const body = await request.json() as { candyMachineAddress: string; index: number; count?: number }
    const candyMachineAddress = (body.candyMachineAddress || '').trim()
    const index = Number(body.index)
    const count = Math.max(1, Math.min(20, Number(body.count ?? 10)))
    if (!candyMachineAddress) return new Response(JSON.stringify({ error: 'candyMachineAddress required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    if (!Number.isFinite(index) || index < 0) return new Response(JSON.stringify({ error: 'index invalid' }), { status: 400, headers: { 'Content-Type': 'application/json' } })

    const umi = getAdminUmi(env)
    const candyMachine = await fetchCandyMachine(umi as any, publicKey(candyMachineAddress))
    const cls = unwrapOption(candyMachine.data.configLineSettings)
    if (!cls) return new Response(JSON.stringify({ error: 'Candy machine has no configLineSettings' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    const nameLength = Number(cls.nameLength)
    const uriLength = Number(cls.uriLength)
    const prefixUri = String(cls.prefixUri || '')
    const replaceItemPattern = (value: string, i: number) => value.replace('$ID+1$', `${i + 1}`).replace('$ID$', `${i}`)
    const rows = await env.DB.prepare(
      'SELECT id, uri FROM game_ship_metadata WHERE is_teaser = 0 ORDER BY id ASC LIMIT ? OFFSET ?'
    ).bind(count, index).all()
    const results = (rows?.results || []) as Array<{ uri: string }>
    const configLines = results.map((r: any, i: number) => {
      const itemIndex = index + i
      const suffixName = String(itemIndex + 1).padStart(nameLength, '0').slice(0, nameLength)
      const fullUri = String(r.uri || '')
      const resolvedPrefixUri = replaceItemPattern(prefixUri, itemIndex)
      const suffixUri = resolvedPrefixUri
        ? (fullUri.startsWith(resolvedPrefixUri) ? fullUri.slice(resolvedPrefixUri.length) : '')
        : fullUri
      if (!suffixUri) {
        throw new Error(`metadata uri does not match prefixUri at index=${itemIndex}`)
      }
      if (suffixUri.length > uriLength) {
        throw new Error(`metadata uri too long at index=${itemIndex}`)
      }
      return { name: suffixName, uri: suffixUri }
    })
    const res = await addShipCandyMachineConfigLines({ env, candyMachineAddress, index, configLines })
    await logAdminAction(env, adminContext, 'ADD_SHIP_CONFIG_LINES', 'game', 'ship_candy_machine', `cm=${candyMachineAddress} index=${index} count=${configLines.length}`)
    return new Response(JSON.stringify({ success: true, inserted: configLines.length, ...res }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error: any) {
    console.error('Error adding ship candy machine lines:', error)
    return new Response(JSON.stringify({ error: error?.message || 'Failed to add config lines' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function addShipCandyMachineLinesGeneratedEndpoint(
  request: Request,
  env: any,
  adminContext: AdminContext,
  ctx?: any
): Promise<Response> {
  try {
    const url = new URL(request.url)
    const body = await request.json() as { candyMachineAddress: string; index: number; count?: number; extension?: string }
    const candyMachineAddress = (body.candyMachineAddress || '').trim()
    const index = Number(body.index)
    const count = Math.max(1, Math.min(20, Number(body.count ?? 10)))
    const extension = (body.extension || '.json').trim() || '.json'
    if (!candyMachineAddress) return new Response(JSON.stringify({ error: 'candyMachineAddress required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    if (!Number.isFinite(index) || index < 0) return new Response(JSON.stringify({ error: 'index invalid' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    if (!extension.startsWith('.')) return new Response(JSON.stringify({ error: 'extension must start with dot' }), { status: 400, headers: { 'Content-Type': 'application/json' } })

    const asyncMode = url.searchParams.get('async') === '1'
    if (asyncMode) {
      if (!env.KV) {
        return new Response(JSON.stringify({ error: 'KV not available' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      }
      if (!ctx?.waitUntil) {
        return new Response(JSON.stringify({ error: 'async not supported' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      }

      const jobId = (globalThis as any).crypto?.randomUUID
        ? (globalThis as any).crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(16).slice(2)}`
      const jobKey = `ship:core:job:${jobId}`
      const createdAt = Date.now()

      await env.KV.put(jobKey, JSON.stringify({
        id: jobId,
        type: 'add_lines_generated',
        status: 'queued',
        createdAt,
        params: { candyMachineAddress, index, count, extension },
      }))

      ctx.waitUntil((async () => {
        try {
          await env.KV.put(jobKey, JSON.stringify({
            id: jobId,
            type: 'add_lines_generated',
            status: 'running',
            createdAt,
            startedAt: Date.now(),
            params: { candyMachineAddress, index, count, extension },
          }))

          const umi = getAdminUmi(env)
          const candyMachine = await fetchCandyMachine(umi as any, publicKey(candyMachineAddress))
          const cls = unwrapOption(candyMachine.data.configLineSettings)
          if (!cls) {
            throw new Error('Candy machine has no configLineSettings')
          }
          const nameLength = Number(cls.nameLength)
          const uriLength = Number(cls.uriLength)

          const configLines = Array.from({ length: count }).map((_, i) => {
            const itemIndex = index + i
            const suffixName = String(itemIndex + 1).padStart(nameLength, '0').slice(0, nameLength)
            const suffixUri = `${String(itemIndex + 1).padStart(6, '0')}${extension}`
            if (suffixUri.length > uriLength) {
              throw new Error(`uri too long for uriLength at index=${itemIndex}`)
            }
            return { name: suffixName, uri: suffixUri }
          })

          const res = await addShipCandyMachineConfigLines({ env, candyMachineAddress, index, configLines })
          await logAdminAction(env, adminContext, 'ADD_SHIP_CONFIG_LINES_GENERATED', 'game', 'ship_candy_machine', `cm=${candyMachineAddress} index=${index} count=${configLines.length}`)

          await env.KV.put(jobKey, JSON.stringify({
            id: jobId,
            type: 'add_lines_generated',
            status: 'done',
            createdAt,
            startedAt: createdAt,
            finishedAt: Date.now(),
            params: { candyMachineAddress, index, count, extension },
            result: { inserted: configLines.length, signature: res.signature },
          }))
        } catch (e: any) {
          try {
            await env.KV.put(jobKey, JSON.stringify({
              id: jobId,
              type: 'add_lines_generated',
              status: 'error',
              createdAt,
              finishedAt: Date.now(),
              params: { candyMachineAddress, index, count, extension },
              error: String(e?.message || e),
            }))
          } catch {}
        }
      })())

      return new Response(JSON.stringify({
        success: true,
        accepted: true,
        jobId,
        jobKey,
      }), { status: 202, headers: { 'Content-Type': 'application/json' } })
    }

    const umi = getAdminUmi(env)
    const candyMachine = await fetchCandyMachine(umi as any, publicKey(candyMachineAddress))
    const cls = unwrapOption(candyMachine.data.configLineSettings)
    if (!cls) return new Response(JSON.stringify({ error: 'Candy machine has no configLineSettings' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    const nameLength = Number(cls.nameLength)
    const uriLength = Number(cls.uriLength)

    const configLines = Array.from({ length: count }).map((_, i) => {
      const itemIndex = index + i
      const suffixName = String(itemIndex + 1).padStart(nameLength, '0').slice(0, nameLength)
      const suffixUri = `${String(itemIndex + 1).padStart(6, '0')}${extension}`
      if (suffixUri.length > uriLength) {
        throw new Error(`uri too long for uriLength at index=${itemIndex}`)
      }
      return { name: suffixName, uri: suffixUri }
    })

    const res = await addShipCandyMachineConfigLines({ env, candyMachineAddress, index, configLines })
    await logAdminAction(env, adminContext, 'ADD_SHIP_CONFIG_LINES_GENERATED', 'game', 'ship_candy_machine', `cm=${candyMachineAddress} index=${index} count=${configLines.length}`)
    return new Response(JSON.stringify({ success: true, inserted: configLines.length, ...res }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error: any) {
    console.error('Error adding ship candy machine lines generated:', error)
    return new Response(JSON.stringify({ error: error?.message || 'Failed to add generated config lines' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function getShipCoreJob(
  _request: Request,
  env: any,
  _adminContext: AdminContext,
  jobId: string
): Promise<Response> {
  if (!env.KV) {
    return new Response(JSON.stringify({ error: 'KV not available' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
  const jobKey = `ship:core:job:${jobId}`
  const raw = await env.KV.get(jobKey)
  if (!raw) {
    return new Response(JSON.stringify({ error: 'not_found' }), { status: 404, headers: { 'Content-Type': 'application/json' } })
  }

  let job: any
  try {
    job = JSON.parse(raw)
  } catch {
    return new Response(raw, { headers: { 'Content-Type': 'application/json' } })
  }

  const status = String(job?.status || '')
  const params = job?.params || {}
  const candyMachineAddress = String(params?.candyMachineAddress || '').trim()
  const index = Number(params?.index)
  const count = Number(params?.count)

  if (status === 'running' && candyMachineAddress && Number.isFinite(index) && Number.isFinite(count)) {
    const signature = await findLatestAddLinesSignature(env, candyMachineAddress, index, count)
    if (signature) {
      const patched = {
        ...job,
        status: 'done',
        finishedAt: Date.now(),
        result: { inserted: count, signature },
        derived: true,
      }
      try {
        await env.KV.put(jobKey, JSON.stringify(patched))
      } catch {}
      return new Response(JSON.stringify(patched), { headers: { 'Content-Type': 'application/json' } })
    }
  }

  return new Response(JSON.stringify(job), { headers: { 'Content-Type': 'application/json' } })
}

async function findLatestAddLinesSignature(
  env: any,
  candyMachineAddress: string,
  index: number,
  count: number
): Promise<string | null> {
  const rpcUrl = getSolanaRpcUrl(env)
  const conn = new Connection(rpcUrl, 'confirmed')
  const cm = new PublicKey(candyMachineAddress)
  const sigs = await conn.getSignaturesForAddress(cm, { limit: 40 })
  const discr = Buffer.from('df32e0e39708736a', 'hex')

  for (const s of sigs) {
    if (s.err) continue
    const tx = await conn.getTransaction(s.signature, { maxSupportedTransactionVersion: 0 })
    if (!tx || tx.meta?.err) continue
    const m: any = tx.transaction.message as any
    const ix = m?.compiledInstructions?.[0]
    if (!ix?.data) continue
    const buf = Buffer.from(ix.data)
    if (buf.length < 16) continue
    if (!buf.subarray(0, 8).equals(discr)) continue
    const gotIndex = buf.readUInt32LE(8)
    const gotCount = buf.readUInt32LE(12)
    if (gotIndex === index && gotCount === count) {
      return s.signature
    }
  }
  return null
}

async function ensureShipMetadataTable(env: any): Promise<void> {
  if (!env.DB) return
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS game_ship_metadata (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      uri TEXT NOT NULL,
      is_teaser INTEGER NOT NULL DEFAULT 0,
      assigned_wallet TEXT,
      assigned_at INTEGER,
      created_at INTEGER DEFAULT (unixepoch()),
      UNIQUE(uri)
    )`
  ).run()
}

async function importShipMetadata(request: Request, env: any, adminContext: AdminContext): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ error: 'Database not available' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
    await ensureShipMetadataTable(env)
    const body = await request.json() as { uris?: string[]; isTeaser?: boolean }
    const uris = Array.isArray(body.uris) ? body.uris.map(u => String(u).trim()).filter(Boolean) : []
    if (uris.length === 0) return new Response(JSON.stringify({ error: 'uris required' }), { status: 400, headers: { 'Content-Type': 'application/json' } })
    const isTeaser = body.isTeaser ? 1 : 0
    let inserted = 0
    for (const uri of uris) {
      const res = await env.DB.prepare('INSERT OR IGNORE INTO game_ship_metadata (uri, is_teaser) VALUES (?, ?)')
        .bind(uri, isTeaser).run()
      if ((res as any)?.meta?.changes) inserted += (res as any).meta.changes
    }
    await logAdminAction(env, adminContext, 'IMPORT_SHIP_METADATA', 'game', 'ship_metadata', `count=${uris.length} inserted=${inserted}`)
    return new Response(JSON.stringify({ success: true, count: uris.length, inserted }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error importing ship metadata:', error)
    return new Response(JSON.stringify({ error: 'Failed to import ship metadata' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}

async function getShipMetadataStats(_request: Request, env: any, _adminContext: AdminContext): Promise<Response> {
  try {
    if (!env.DB) return new Response(JSON.stringify({ error: 'Database not available' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
    await ensureShipMetadataTable(env)
    const total = await env.DB.prepare('SELECT COUNT(1) as c FROM game_ship_metadata').first()
    const assigned = await env.DB.prepare('SELECT COUNT(1) as c FROM game_ship_metadata WHERE assigned_wallet IS NOT NULL').first()
    const teaser = await env.DB.prepare('SELECT COUNT(1) as c FROM game_ship_metadata WHERE is_teaser = 1').first()
    return new Response(JSON.stringify({
      success: true,
      total: (total as any)?.c ?? 0,
      assigned: (assigned as any)?.c ?? 0,
      teaser: (teaser as any)?.c ?? 0
    }), { headers: { 'Content-Type': 'application/json' } })
  } catch (error) {
    console.error('Error getting ship metadata stats:', error)
    return new Response(JSON.stringify({ error: 'Failed to get ship metadata stats' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
  }
}
