import { Env, jsonResponse } from '../index'

export async function handleJupiterProxy(request: Request, env: Env, path: string): Promise<Response> {
  const apiKey = (env.JUPITER_API_KEY || '').trim()
  if (!apiKey) {
    return jsonResponse({ error: 'JUPITER_API_KEY not configured' }, 500)
  }

  const url = new URL(request.url)
  const method = request.method.toUpperCase()

  let upstream: URL | null = null

  if (path === '/api/v1/jupiter/price/v3') {
    if (method !== 'GET') return jsonResponse({ error: 'method_not_allowed' }, 405)
    upstream = new URL(`https://api.jup.ag/price/v3${url.search}`)
  } else if (path.startsWith('/api/v1/jupiter/ultra/v1/')) {
    const subPath = path.slice('/api/v1/jupiter/ultra/v1/'.length)
    const allowed = ['order', 'execute', 'balances', 'shield']
    if (!allowed.some((p) => subPath === p || subPath.startsWith(`${p}/`))) {
      return jsonResponse({ error: 'not_allowed' }, 403)
    }
    if (subPath === 'execute' && method !== 'POST') return jsonResponse({ error: 'method_not_allowed' }, 405)
    if (subPath !== 'execute' && method !== 'GET') return jsonResponse({ error: 'method_not_allowed' }, 405)
    upstream = new URL(`https://api.jup.ag/ultra/v1/${subPath}${url.search}`)
  } else {
    return jsonResponse({ error: 'not_found' }, 404)
  }

  const headers = new Headers()
  headers.set('Accept', request.headers.get('Accept') || 'application/json')
  const acceptLanguage = request.headers.get('Accept-Language')
  if (acceptLanguage) headers.set('Accept-Language', acceptLanguage)
  headers.set('x-api-key', apiKey)

  let body: ArrayBuffer | undefined
  if (method === 'POST') {
    const contentType = request.headers.get('Content-Type') || 'application/json'
    headers.set('Content-Type', contentType)
    body = await request.arrayBuffer()
  }

  const resp = await fetch(upstream.toString(), {
    method,
    headers,
    body,
  })

  const respHeaders = new Headers()
  const ct = resp.headers.get('Content-Type')
  if (ct) respHeaders.set('Content-Type', ct)
  const cacheControl = resp.headers.get('Cache-Control')
  if (cacheControl) respHeaders.set('Cache-Control', cacheControl)

  return new Response(resp.body, { status: resp.status, headers: respHeaders })
}

