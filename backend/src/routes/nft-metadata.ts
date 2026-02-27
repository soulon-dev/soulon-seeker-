import { Env } from '../types'
import { jsonResponse } from '../utils/response'

export async function handleNftMetadata(request: Request, env: Env, path: string): Promise<Response | null> {
  if (!path.startsWith('/nft/')) return null
  if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method Not Allowed', { status: 405 })
  if (!env.SHIP_METADATA) return jsonResponse({ error: 'R2 binding missing' }, 500)

  if (path.startsWith('/nft/assets/')) {
    const key = `assets/${path.slice('/nft/assets/'.length)}`
    if (!key || key.endsWith('/')) return jsonResponse({ error: 'Not found' }, 404)
    const obj = await env.SHIP_METADATA.get(key)
    if (!obj) return jsonResponse({ error: 'Not found' }, 404)
    return new Response(obj.body, {
      headers: {
        'Content-Type': obj.httpMetadata?.contentType || 'application/octet-stream',
        'Cache-Control': 'public, max-age=31536000, immutable',
        'Access-Control-Allow-Origin': '*',
      },
    })
  }

  if (path === '/nft/collection.json') {
    const obj = await env.SHIP_METADATA.get('collection.json')
    if (!obj) {
      const fallback = {
        name: 'Seeker Spaceship',
        symbol: 'SEEKER',
        description: 'Seeker Spaceship collection',
        image: `${new URL('/nft/assets/collection.svg', request.url).toString()}`,
      }
      return new Response(JSON.stringify(fallback), {
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'public, max-age=60',
          'Access-Control-Allow-Origin': '*',
        },
      })
    }
    return new Response(obj.body, {
      headers: {
        'Content-Type': obj.httpMetadata?.contentType || 'application/json; charset=utf-8',
        'Cache-Control': 'public, max-age=300',
        'Access-Control-Allow-Origin': '*',
      },
    })
  }

  const m = path.match(/^\/nft\/ships\/(\d{6})\.json$/)
  if (m) {
    const id6 = m[1]
    const id = Number(id6)
    const key = `ships/${id6}.json`
    const obj = await env.SHIP_METADATA.get(key)
    if (!obj) {
      const tplObj = await env.SHIP_METADATA.get('ships/template.json')
      const base = tplObj ? await safeJson(tplObj) : null
      const payload = applyPlaceholders(
        base ?? {
          name: 'Seeker Spaceship #{{ID6}}',
          symbol: 'SEEKER',
          description: 'Seeker Spaceship',
          image: `${new URL('/nft/assets/ship.svg', request.url).toString()}`,
        },
        { id, id6 }
      )
      if (!payload.name) payload.name = `Seeker Spaceship #${id6}`
      return new Response(JSON.stringify(payload), {
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'public, max-age=31536000, immutable',
          'Access-Control-Allow-Origin': '*',
        },
      })
    }
    return new Response(obj.body, {
      headers: {
        'Content-Type': obj.httpMetadata?.contentType || 'application/json; charset=utf-8',
        'Cache-Control': 'public, max-age=31536000, immutable',
        'Access-Control-Allow-Origin': '*',
      },
    })
  }

  return jsonResponse({ error: 'Not found' }, 404)
}

async function safeJson(obj: any): Promise<any | null> {
  try {
    if (typeof obj.json === 'function') return await obj.json()
    if (typeof obj.text === 'function') return JSON.parse(await obj.text())
    return null
  } catch {
    return null
  }
}

function applyPlaceholders(input: any, vars: { id: number; id6: string }): any {
  if (input === null || input === undefined) return input
  if (typeof input === 'string') {
    return input
      .replaceAll('{{ID}}', String(vars.id))
      .replaceAll('{{ID6}}', vars.id6)
  }
  if (Array.isArray(input)) return input.map((v) => applyPlaceholders(v, vars))
  if (typeof input === 'object') {
    const out: any = {}
    for (const [k, v] of Object.entries(input)) out[k] = applyPlaceholders(v, vars)
    return out
  }
  return input
}
