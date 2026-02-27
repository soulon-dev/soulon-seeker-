import type { Env } from '../index'
import { jsonResponse } from '../index'

type TranslateItem = { key: string; text: string }

function stripJsonFence(s: string): string {
  const t = String(s || '').trim()
  if (t.startsWith('```')) {
    return t.replace(/^```[a-zA-Z]*\n?/, '').replace(/```$/, '').trim()
  }
  return t
}

function getLangName(code: string): string {
  switch (code) {
    case 'zh':
      return 'Chinese (Simplified)'
    case 'en':
      return 'English'
    case 'ja':
      return 'Japanese'
    case 'ko':
      return 'Korean'
    case 'es':
      return 'Spanish'
    case 'fr':
      return 'French'
    case 'de':
      return 'German'
    case 'pt':
      return 'Portuguese'
    case 'ru':
      return 'Russian'
    case 'ar':
      return 'Arabic'
    default:
      return code
  }
}

async function getQwenConfig(env: Env): Promise<{ apiKey: string; endpoint: string } | null> {
  if (!env.DB) return null

  let apiKey = (env.QWEN_API_KEY && env.QWEN_API_KEY.trim().length > 0) ? env.QWEN_API_KEY.trim() : undefined
  let endpointUrl: string | undefined

  const qwenRow = await env.DB.prepare(
    `SELECT encrypted_key, endpoint_url
     FROM api_keys
     WHERE service = 'qwen' AND is_active = 1
     ORDER BY is_primary DESC, updated_at DESC, created_at DESC
     LIMIT 1`
  ).first()

  if (qwenRow) {
    endpointUrl = (qwenRow.endpoint_url as string) || undefined
  }

  if (!apiKey) {
    if (qwenRow && qwenRow.encrypted_key) {
      try {
        apiKey = atob(qwenRow.encrypted_key as string)
      } catch {
        return null
      }
    }
  }

  if (!apiKey) return null

  return {
    apiKey,
    endpoint: endpointUrl || 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions',
  }
}

async function translateBatch(items: TranslateItem[], targetLang: string, env: Env): Promise<Record<string, string> | null> {
  const config = await getQwenConfig(env)
  if (!config) return null

  const langName = getLangName(targetLang)
  const user = [
    `Translate the following UI strings from English to ${langName}.`,
    `Return strictly a JSON object mapping each item's key to the translated string.`,
    `Preserve literal \\n sequences, punctuation, and placeholders like %s, %d, and %% exactly.`,
    `Do not add explanations or formatting; output JSON only.`,
    `Items JSON: ${JSON.stringify(items)}`,
  ].join('\n')

  const proxyBody = {
    model: 'qwen-turbo',
    messages: [
      { role: 'system', content: 'You are a precise UI localization translator.' },
      { role: 'user', content: user },
    ],
    temperature: 0,
    max_tokens: 4000,
    stream: false,
  }

  const upstreamResponse = await fetch(config.endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${config.apiKey}`,
    },
    body: JSON.stringify(proxyBody),
  })

  if (!upstreamResponse.ok) {
    const text = await upstreamResponse.text()
    console.error('i18n translate upstream failed:', upstreamResponse.status, text.slice(0, 500))
    return null
  }

  const json = (await upstreamResponse.json()) as any
  const content = json?.choices?.[0]?.message?.content
  if (!content) return null

  try {
    const obj = JSON.parse(stripJsonFence(content))
    if (!obj || typeof obj !== 'object') return null
    return obj as Record<string, string>
  } catch (e) {
    console.error('i18n translate parse failed:', e)
    return null
  }
}

export async function handleI18nRoutes(request: Request, env: Env, path: string): Promise<Response | null> {
  if (path === '/api/v1/i18n/translate' && request.method === 'POST') {
    try {
      if (!env.DB) return jsonResponse({ error: 'Database not available' }, 500)

      const body = (await request.json()) as any
      const targetLang = String(body?.targetLang || '').trim().toLowerCase()
      const items = Array.isArray(body?.items) ? (body.items as any[]).map((x) => ({
        key: String(x?.key || ''),
        text: String(x?.text || ''),
      })) : []

      if (!targetLang) return jsonResponse({ error: 'Missing targetLang' }, 400)
      if (!items.length) return jsonResponse({ error: 'Missing items' }, 400)
      if (items.length > 50) return jsonResponse({ error: 'Too many items (max 50)' }, 400)

      const supported = new Set(['zh', 'en', 'ja', 'ko', 'es', 'fr', 'de', 'pt', 'ru', 'ar'])
      if (!supported.has(targetLang)) return jsonResponse({ error: 'Unsupported targetLang' }, 400)

      const result = await translateBatch(items, targetLang, env)
      if (!result) return jsonResponse({ error: 'Translation failed' }, 502)

      return jsonResponse({ translations: result })
    } catch (error) {
      console.error('i18n translate failed:', error)
      return jsonResponse({ error: 'Translation failed' }, 500)
    }
  }

  return null
}

