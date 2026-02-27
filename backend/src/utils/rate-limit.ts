export type RateLimitDecision =
  | { allowed: true }
  | { allowed: false; retryAfterSeconds: number }

async function incrCounter(kv: KVNamespace, key: string, ttlSeconds: number): Promise<number> {
  const currentRaw = await kv.get(key)
  const current = currentRaw ? Number(currentRaw) : 0
  const next = Number.isFinite(current) ? current + 1 : 1
  await kv.put(key, String(next), { expirationTtl: ttlSeconds })
  return next
}

export async function checkRateLimit(
  kv: KVNamespace | undefined,
  key: string,
  limit: number,
  windowSeconds: number
): Promise<RateLimitDecision> {
  if (!kv) return { allowed: true }
  const count = await incrCounter(kv, key, windowSeconds)
  if (count > limit) {
    return { allowed: false, retryAfterSeconds: windowSeconds }
  }
  return { allowed: true }
}

