
import { Connection } from '@solana/web3.js';
import { createUmi } from '@metaplex-foundation/umi-bundle-defaults';
import { createNoopSigner, publicKey, signerIdentity } from '@metaplex-foundation/umi';
import { mplCore } from '@metaplex-foundation/mpl-core';
import { fetchCandyMachine, mplCandyMachine } from '@metaplex-foundation/mpl-core-candy-machine';
import { Env } from '../types';
import { jsonResponse } from '../utils/response';
import { verifyGenesisCollectionWithDas } from '../utils/solana';
import { buildShipCoreCandyMachineMintTx, confirmShipCoreMint } from '../services/ship-core';
import { getSolanaRpcUrl } from '../utils/solana-rpc';

async function getShipQueueCount(env: Env, candyMachineAddress: string): Promise<number | null> {
  try {
    if (!candyMachineAddress) return null
    const rpcUrl = getSolanaRpcUrl(env)
    const umi = createUmi(rpcUrl)
      .use(mplCandyMachine())
      .use(mplCore())
      .use(signerIdentity(createNoopSigner(publicKey('11111111111111111111111111111111'))))
    const cm = await fetchCandyMachine(umi as any, publicKey(candyMachineAddress))
    const raw = (cm as any).itemsRedeemed
    if (typeof raw === 'number') return raw
    if (typeof raw === 'bigint') return Number(raw)
    if (raw && typeof raw.toString === 'function') return Number(raw.toString())
    return null
  } catch {
    return null
  }
}

async function ensureShipMintNotifyTable(env: Env): Promise<void> {
  if (!env.DB) return
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS game_ship_mint_notify_subscriptions (
      wallet_address TEXT PRIMARY KEY,
      start_at INTEGER NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )`
  ).run()
}

async function getShipMintNotifyCount(env: Env): Promise<number | null> {
  try {
    if (!env.DB) return null
    await ensureShipMintNotifyTable(env)
    const row = await env.DB.prepare('SELECT COUNT(1) AS c FROM game_ship_mint_notify_subscriptions').first<{ c: number }>()
    return Number((row as any)?.c ?? 0)
  } catch {
    return null
  }
}

function getClientIp(request: Request): string | null {
  const ip = (request.headers.get('CF-Connecting-IP') || request.headers.get('X-Forwarded-For') || '').split(',')[0].trim()
  return ip ? ip : null
}

async function ensureShipMintConcurrencyTables(env: Env): Promise<void> {
  if (!env.DB) return
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS game_ship_mint_rate_limits (
      wallet_address TEXT PRIMARY KEY,
      last_tx_at INTEGER,
      last_confirm_at INTEGER
    )`
  ).run()
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS game_ship_ip_rate_limits (
      ip TEXT PRIMARY KEY,
      last_at INTEGER
    )`
  ).run()
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS game_ship_mint_tx_locks (
      wallet_address TEXT PRIMARY KEY,
      locked_until INTEGER NOT NULL
    )`
  ).run()
  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS game_ship_mint_tx_cache (
      wallet_address TEXT PRIMARY KEY,
      created_at INTEGER NOT NULL,
      transaction_base64 TEXT NOT NULL,
      asset_address TEXT NOT NULL,
      candy_machine TEXT NOT NULL,
      start_at INTEGER NOT NULL
    )`
  ).run()
}

async function checkAndTouchWalletRateLimit(env: Env, walletAddress: string, kind: 'tx' | 'confirm', cooldownSec: number): Promise<{ ok: boolean; retryAfterSec?: number }> {
  if (!env.DB) return { ok: true }
  await ensureShipMintConcurrencyTables(env)
  const row = await env.DB.prepare('SELECT last_tx_at, last_confirm_at FROM game_ship_mint_rate_limits WHERE wallet_address = ?')
    .bind(walletAddress).first<{ last_tx_at: number | null; last_confirm_at: number | null }>()
  const now = Math.floor(Date.now() / 1000)
  const last = kind === 'tx' ? Number((row as any)?.last_tx_at ?? 0) : Number((row as any)?.last_confirm_at ?? 0)
  if (last > 0 && now - last < cooldownSec) {
    return { ok: false, retryAfterSec: cooldownSec - (now - last) }
  }
  if (!row) {
    await env.DB.prepare('INSERT INTO game_ship_mint_rate_limits (wallet_address, last_tx_at, last_confirm_at) VALUES (?, ?, ?)')
      .bind(walletAddress, kind === 'tx' ? now : null, kind === 'confirm' ? now : null).run()
  } else if (kind === 'tx') {
    await env.DB.prepare('UPDATE game_ship_mint_rate_limits SET last_tx_at = ? WHERE wallet_address = ?')
      .bind(now, walletAddress).run()
  } else {
    await env.DB.prepare('UPDATE game_ship_mint_rate_limits SET last_confirm_at = ? WHERE wallet_address = ?')
      .bind(now, walletAddress).run()
  }
  return { ok: true }
}

async function checkAndTouchIpRateLimit(env: Env, ip: string | null, cooldownMs: number): Promise<{ ok: boolean; retryAfterMs?: number }> {
  if (!env.DB) return { ok: true }
  if (!ip) return { ok: true }
  await ensureShipMintConcurrencyTables(env)
  const row = await env.DB.prepare('SELECT last_at FROM game_ship_ip_rate_limits WHERE ip = ?').bind(ip).first<{ last_at: number | null }>()
  const now = Date.now()
  const last = Number((row as any)?.last_at ?? 0)
  if (last > 0 && now - last < cooldownMs) {
    return { ok: false, retryAfterMs: cooldownMs - (now - last) }
  }
  if (!row) {
    await env.DB.prepare('INSERT INTO game_ship_ip_rate_limits (ip, last_at) VALUES (?, ?)').bind(ip, now).run()
  } else {
    await env.DB.prepare('UPDATE game_ship_ip_rate_limits SET last_at = ? WHERE ip = ?').bind(now, ip).run()
  }
  return { ok: true }
}

async function tryAcquireMintTxLock(env: Env, walletAddress: string, lockSec: number): Promise<{ ok: boolean; retryAfterSec?: number }> {
  if (!env.DB) return { ok: true }
  await ensureShipMintConcurrencyTables(env)
  const now = Math.floor(Date.now() / 1000)
  const row = await env.DB.prepare('SELECT locked_until FROM game_ship_mint_tx_locks WHERE wallet_address = ?')
    .bind(walletAddress).first<{ locked_until: number }>()
  const lockedUntil = Number((row as any)?.locked_until ?? 0)
  if (lockedUntil > now) {
    return { ok: false, retryAfterSec: lockedUntil - now }
  }
  if (!row) {
    await env.DB.prepare('INSERT INTO game_ship_mint_tx_locks (wallet_address, locked_until) VALUES (?, ?)')
      .bind(walletAddress, now + lockSec).run()
  } else {
    await env.DB.prepare('UPDATE game_ship_mint_tx_locks SET locked_until = ? WHERE wallet_address = ?')
      .bind(now + lockSec, walletAddress).run()
  }
  return { ok: true }
}

async function releaseMintTxLock(env: Env, walletAddress: string): Promise<void> {
  if (!env.DB) return
  await ensureShipMintConcurrencyTables(env)
  await env.DB.prepare('UPDATE game_ship_mint_tx_locks SET locked_until = 0 WHERE wallet_address = ?').bind(walletAddress).run()
}

async function getCachedMintTx(env: Env, walletAddress: string, maxAgeSec: number): Promise<{ transactionBase64: string; assetAddress: string; candyMachine: string; startAt: number } | null> {
  if (!env.DB) return null
  await ensureShipMintConcurrencyTables(env)
  const row = await env.DB.prepare(
    'SELECT created_at, transaction_base64, asset_address, candy_machine, start_at FROM game_ship_mint_tx_cache WHERE wallet_address = ?'
  ).bind(walletAddress).first<any>()
  if (!row) return null
  const now = Math.floor(Date.now() / 1000)
  const createdAt = Number(row.created_at ?? 0)
  if (createdAt <= 0 || now - createdAt > maxAgeSec) return null
  return {
    transactionBase64: String(row.transaction_base64),
    assetAddress: String(row.asset_address),
    candyMachine: String(row.candy_machine),
    startAt: Number(row.start_at ?? 0),
  }
}

async function setCachedMintTx(env: Env, walletAddress: string, tx: { transactionBase64: string; assetAddress: string; candyMachine: string; startAt: number }): Promise<void> {
  if (!env.DB) return
  await ensureShipMintConcurrencyTables(env)
  const now = Math.floor(Date.now() / 1000)
  await env.DB.prepare(
    `INSERT INTO game_ship_mint_tx_cache (wallet_address, created_at, transaction_base64, asset_address, candy_machine, start_at)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT(wallet_address) DO UPDATE SET created_at = excluded.created_at, transaction_base64 = excluded.transaction_base64, asset_address = excluded.asset_address, candy_machine = excluded.candy_machine, start_at = excluded.start_at`
  ).bind(walletAddress, now, tx.transactionBase64, tx.assetAddress, tx.candyMachine, tx.startAt).run()
}

// Hardcoded NPC Registry
interface NpcConfig {
  id: string;
  name: string;
  portId: string;
  avatar: string; // ASCII Art or URL
  systemPrompt: string;
  greeting: string;
}

const NPC_REGISTRY: Record<string, NpcConfig> = {
  'archivist': {
    id: 'archivist',
    name: 'The Archivist',
    portId: 'port_amsterdam',
    avatar: `
    [ o.o ]
     |___|
    /|   |\\
   / |___| \\
    `,
    systemPrompt: `You are The Archivist, a strict and precise administrator of the Amsterdam Blockchain Node. 
    You speak like a machine or a very bureaucratic human. 
    You value order, data integrity, and politeness. 
    You despise chaos, "glitches", and rude behavior.
    
    If the player asks about the market, provide vague but helpful hints about price trends (e.g., "Data streams indicate a surge in energy demand").
    If the player is rude, refuse to cooperate.
    Keep your responses concise and within 3 sentences.
    `,
    greeting: "Identification verified. State your business, Seeker. The archives are busy today."
  },
  'phantom': {
    id: 'phantom',
    name: 'Neon Phantom',
    portId: 'port_shanghai', // Assuming we add this port later, or map to existing
    avatar: `
    ( ^_^ )
    <| x |>
     |   |
    `,
    systemPrompt: `You are Neon Phantom, a hacker living in the digital shadows.
    You use cyberpunk slang and leetspeak occasionally.
    You are rebellious and distrust authority (The Consortium).
    You offer high-risk, high-reward opportunities.
    Keep your responses cool and edgy.
    `,
    greeting: "Yo. Keep your voice down. The firewalls have ears."
  }
};

// Types based on the schema
interface GamePlayer {
  id: number;
  user_id: string;
  name: string;
  money: number;
  current_port_id: string;
  ship_level: number;
  cargo_capacity: number;
  q?: number;
  r?: number;
  created_at: number;
  updated_at: number;
}

interface GamePort {
  id: string;
  name: string;
  description: string;
  coordinates: string;
  unlock_level: number;
}

interface GameGood {
  id: string;
  name: string;
  description: string;
  base_price: number;
  volatility: number;
}

interface GameMarketItem {
  id: number;
  port_id: string;
  good_id: string;
  price: number;
  stock: number;
  updated_at: number;
  // Joined fields
  name?: string;
  description?: string;
}

interface GameInventoryItem {
  id: number;
  player_id: number;
  good_id: string;
  quantity: number;
  avg_cost: number;
  updated_at: number;
  // Joined fields
  name?: string;
}

interface GameTravelState {
  player_id: number;
  from_port_id: string;
  to_port_id: string;
  depart_at: number;
  arrive_at: number;
  status: 'ACTIVE' | 'ARRIVED' | 'CANCELLED';
  travel_cost: number;
  encounter_event: string | null;
  encounter_money_delta: number;
  created_at: number;
  updated_at: number;
}

// Helper to get user ID from request (assuming auth middleware sets it or we pass it)
// For now, we'll assume the wallet address is passed in the header or body, similar to other routes
// But wait, the main index.ts middleware handles auth and passes it. 
// We'll need to extract the wallet address from the request.

export async function handleGameRoutes(request: Request, env: Env, path: string): Promise<Response | null> {
  // Extract wallet address from request (injected by middleware)
  const walletAddress = (request as any).walletAddress;
  if (!walletAddress) {
    return jsonResponse({ error: 'Unauthorized' }, 401);
  }

  if (env.DB) {
    await env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS game_ship_nfts (
        wallet_address TEXT PRIMARY KEY,
        minted_at INTEGER NOT NULL,
        mint_signature TEXT,
        asset_address TEXT,
        metadata_uri TEXT,
        created_at INTEGER DEFAULT (unixepoch())
      )`
    ).run()
    try { await env.DB.prepare(`ALTER TABLE game_ship_nfts ADD COLUMN mint_signature TEXT`).run() } catch {}
    try { await env.DB.prepare(`ALTER TABLE game_ship_nfts ADD COLUMN asset_address TEXT`).run() } catch {}
    try { await env.DB.prepare(`ALTER TABLE game_ship_nfts ADD COLUMN metadata_uri TEXT`).run() } catch {}
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

  // Get user_id from wallet address or create if not exists
  let userId: string;
  const user = await env.DB?.prepare('SELECT id FROM users WHERE wallet_address = ?').bind(walletAddress).first();
  
  if (!user) {
    // Auto-create user for game players to ensure seamless onboarding
    const nowSec = Math.floor(Date.now() / 1000);
    const newId = `user_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;
    try {
      await env.DB!.prepare(
        `INSERT INTO users (id, wallet_address, memo_balance, current_tier, subscription_type, created_at, last_active_at)
         VALUES (?, ?, 0, 1, 'FREE', ?, ?)`
      ).bind(newId, walletAddress, nowSec, nowSec).run();
      userId = newId;
      console.log(`[Game] Auto-created user ${userId} for wallet ${walletAddress}`);
    } catch (e) {
      console.error('Failed to auto-create user for game:', e);
      // Try to fetch again in case of race condition
      const retryUser = await env.DB?.prepare('SELECT id FROM users WHERE wallet_address = ?').bind(walletAddress).first();
      if (retryUser) {
        userId = retryUser.id as string;
      } else {
        return jsonResponse({ error: 'User creation failed' }, 500);
      }
    }
  } else {
    userId = user.id as string;
  }

  const normalizedPath = path.replace(/\/+$/, '')
  const config = createConfigReader(env)
  const cfg = await config.getMany([
    'game.ship.require_nft',
    'game.ship.collection',
    'game.ship.core.candy_machine',
    'game.ship.mint.recipient',
    'game.ship.mint.price_lamports',
    'game.ship.mint.start_at',
    'game.ship.mint.enabled',
    'game.ship.soulbound.mode',
    'game.ship.metadata.teaser_uri',
    'game.ship.metadata.reveal_mode'
  ])
  const requireShipNft = Math.floor(config.number(cfg['game.ship.require_nft'], 1, 0, 1)) === 1
  const shipCollection = (cfg['game.ship.collection'] || '').trim()
  const shipCoreCandyMachine = (cfg['game.ship.core.candy_machine'] || '').trim()
  const shipMintRecipient = (cfg['game.ship.mint.recipient'] || '').trim()
  const shipMintPriceLamports = Math.floor(config.number(cfg['game.ship.mint.price_lamports'], 50_000_000, 0, 1_000_000_000_000))
  const shipMintStartAt = Math.floor(config.number(cfg['game.ship.mint.start_at'], 1773493200, 0, 4_102_444_800))
  const shipMintEnabled = Math.floor(config.number(cfg['game.ship.mint.enabled'], 0, 0, 1)) === 1
  const shipSoulboundMode = (cfg['game.ship.soulbound.mode'] || '').trim() || 'server_record'
  const shipTeaserUri = (cfg['game.ship.metadata.teaser_uri'] || '').trim()
  const shipRevealMode = Math.floor(config.number(cfg['game.ship.metadata.reveal_mode'], 0, 0, 1)) === 1

  if (normalizedPath === '/api/v1/game/ship/eligibility' && request.method === 'GET') {
    const queueCount = await getShipMintNotifyCount(env)
    const existing = await env.DB?.prepare('SELECT minted_at, mint_signature, asset_address, metadata_uri FROM game_ship_nfts WHERE wallet_address = ?')
      .bind(walletAddress).first<{ minted_at: number; mint_signature: string | null; asset_address: string | null; metadata_uri: string | null }>()
    if (existing) {
      return jsonResponse({
        wallet: walletAddress,
        hasNft: true,
        mintedAt: existing.minted_at,
        mintSignature: existing.mint_signature ?? null,
        assetAddress: existing.asset_address ?? null,
        metadataUri: existing.metadata_uri ?? null,
        requireNft: requireShipNft,
        mintEnabled: shipMintEnabled,
        queueCount,
        soulboundMode: shipSoulboundMode,
        collection: shipCollection || null,
        coreCandyMachine: shipCoreCandyMachine || null,
        mint: {
          recipient: shipMintRecipient || null,
          priceLamports: shipMintPriceLamports,
          startAt: shipMintStartAt
        },
      })
    }

    if (shipSoulboundMode === 'server_record') {
      return jsonResponse({
        wallet: walletAddress,
        hasNft: false,
        requireNft: requireShipNft,
        mintEnabled: shipMintEnabled,
        queueCount,
        soulboundMode: shipSoulboundMode,
        dasSupported: false,
        collection: shipCollection || null,
        coreCandyMachine: shipCoreCandyMachine || null,
        rpcHost: null,
        mint: {
          recipient: shipMintRecipient || null,
          priceLamports: shipMintPriceLamports,
          startAt: shipMintStartAt
        },
        metadata: {
          revealMode: shipRevealMode,
          teaserUri: shipTeaserUri || null
        }
      })
    }

    let chainHas = false
    let dasSupported = false
    let rpcHost: string | null = null
    const rpcUrl = getSolanaRpcUrl(env)
    try { rpcHost = new URL(rpcUrl).host } catch { rpcHost = null }
    if (shipCollection) {
      const das = await verifyGenesisCollectionWithDas({
        walletAddress: walletAddress,
        collectionAddress: shipCollection,
        rpcUrl
      })
      dasSupported = das.supported
      chainHas = das.hasToken
    }
    if (chainHas && env.DB) {
      const now = Math.floor(Date.now() / 1000)
      await env.DB.prepare('INSERT OR IGNORE INTO game_ship_nfts (wallet_address, minted_at) VALUES (?, ?)')
        .bind(walletAddress, now).run()
    }
    return jsonResponse({
      wallet: walletAddress,
      hasNft: chainHas,
      requireNft: requireShipNft,
      mintEnabled: shipMintEnabled,
      queueCount,
      soulboundMode: shipSoulboundMode,
      dasSupported,
      collection: shipCollection || null,
      coreCandyMachine: shipCoreCandyMachine || null,
      rpcHost,
      mint: {
        recipient: shipMintRecipient || null,
        priceLamports: shipMintPriceLamports,
        startAt: shipMintStartAt
      },
      metadata: {
        revealMode: shipRevealMode,
        teaserUri: shipTeaserUri || null
      }
    })
  }

  if (normalizedPath === '/api/v1/game/ship/mint/notify/subscribe' && request.method === 'POST') {
    const body = (await request.json().catch(() => null)) as { startAt?: number } | null
    const startAt = Math.floor(Number(body?.startAt ?? 0))
    if (startAt <= 0) return jsonResponse({ error: 'startAt required' }, 400)
    if (!env.DB) return jsonResponse({ error: 'Database not available' }, 500)
    const now = Math.floor(Date.now() / 1000)
    await ensureShipMintNotifyTable(env)
    await env.DB.prepare(
      `INSERT INTO game_ship_mint_notify_subscriptions (wallet_address, start_at, created_at, updated_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(wallet_address) DO UPDATE SET start_at = excluded.start_at, updated_at = excluded.updated_at`
    ).bind(walletAddress, startAt, now, now).run()
    const queueCount = await getShipMintNotifyCount(env)
    return jsonResponse({ success: true, queueCount })
  }

  if (normalizedPath === '/api/v1/game/my-assets' && request.method === 'GET') {
    if (!env.DB) return jsonResponse({ error: 'Database not available' }, 500)
    const ship = await env.DB.prepare(
      'SELECT minted_at, mint_signature, asset_address, metadata_uri FROM game_ship_nfts WHERE wallet_address = ?'
    ).bind(walletAddress).first<{ minted_at: number; mint_signature: string | null; asset_address: string | null; metadata_uri: string | null }>()
    const assets: any[] = []
    if (ship) {
      assets.push({
        kind: 'ship_basic_freighter',
        name: 'Seeker Spaceship',
        assetAddress: ship.asset_address ?? null,
        metadataUri: ship.metadata_uri ?? null,
        mintedAt: ship.minted_at,
        mintSignature: ship.mint_signature ?? null,
      })
    }
    return jsonResponse({ wallet: walletAddress, assets })
  }

  if (normalizedPath === '/api/v1/game/ship/mint/tx' && request.method === 'POST') {
    if (!shipMintEnabled) return jsonResponse({ error: 'Mint disabled' }, 403)
    const now = Math.floor(Date.now() / 1000)
    if (shipMintStartAt > 0 && now < shipMintStartAt) {
      return jsonResponse({ error: 'Mint not started', startAt: shipMintStartAt }, 400)
    }
    const existing = await env.DB?.prepare('SELECT minted_at FROM game_ship_nfts WHERE wallet_address = ?')
      .bind(walletAddress).first<{ minted_at: number }>()
    if (existing) {
      return jsonResponse({ error: 'Already minted' }, 400)
    }
    if (!shipCoreCandyMachine) {
      return jsonResponse({ error: 'Candy machine not configured' }, 500)
    }

    const ip = getClientIp(request)
    const ipLimit = await checkAndTouchIpRateLimit(env, ip, 250)
    if (!ipLimit.ok) {
      const retryAfter = Math.max(1, Math.ceil(Number(ipLimit.retryAfterMs ?? 0) / 1000))
      return jsonResponse({ error: 'Rate limited', retryAfterMs: ipLimit.retryAfterMs ?? 0 }, 429, { 'Retry-After': String(retryAfter) })
    }
    const walletLimit = await checkAndTouchWalletRateLimit(env, walletAddress, 'tx', 2)
    if (!walletLimit.ok) {
      const retryAfter = Math.max(1, Math.ceil(Number(walletLimit.retryAfterSec ?? 1)))
      return jsonResponse({ error: 'Rate limited', retryAfterSec: walletLimit.retryAfterSec ?? 1 }, 429, { 'Retry-After': String(retryAfter) })
    }

    const cached = await getCachedMintTx(env, walletAddress, 20)
    if (cached) return jsonResponse(cached)

    const lock = await tryAcquireMintTxLock(env, walletAddress, 8)
    if (!lock.ok) {
      const retryAfter = Math.max(1, Math.ceil(Number(lock.retryAfterSec ?? 1)))
      return jsonResponse({ error: 'Busy', retryAfterSec: lock.retryAfterSec ?? 1 }, 429, { 'Retry-After': String(retryAfter) })
    }
    try {
      const built = await buildShipCoreCandyMachineMintTx({
        env,
        walletAddress,
        candyMachineId: shipCoreCandyMachine,
      })
      const out = {
        transactionBase64: built.transactionBase64,
        assetAddress: built.assetAddress,
        candyMachine: built.candyMachine,
        startAt: shipMintStartAt,
      }
      await setCachedMintTx(env, walletAddress, out)
      return jsonResponse(out)
    } finally {
      await releaseMintTxLock(env, walletAddress)
    }
  }

  if (normalizedPath === '/api/v1/game/ship/mint/confirm' && request.method === 'POST') {
    if (!shipMintEnabled) return jsonResponse({ error: 'Mint disabled' }, 403)
    const body = (await request.json().catch(() => null)) as { signature?: string; assetAddress?: string } | null
    const signature = (body?.signature || '').trim()
    if (!signature) return jsonResponse({ error: 'Signature required' }, 400)
    const assetAddress = (body?.assetAddress || '').trim()
    if (!assetAddress) return jsonResponse({ error: 'Asset address required' }, 400)

    const existing = await env.DB!.prepare('SELECT minted_at, mint_signature, asset_address, metadata_uri FROM game_ship_nfts WHERE wallet_address = ?')
      .bind(walletAddress).first<{ minted_at: number; mint_signature: string | null; asset_address: string | null; metadata_uri: string | null }>()
    if (existing && (existing.mint_signature === signature || existing.asset_address === assetAddress || !!existing.minted_at)) {
      return jsonResponse({
        success: true,
        hasNft: true,
        mintedAt: existing.minted_at,
        mintSignature: existing.mint_signature ?? signature,
        assetAddress: existing.asset_address ?? assetAddress,
        metadataUri: existing.metadata_uri ?? null
      })
    }

    const ip = getClientIp(request)
    const ipLimit = await checkAndTouchIpRateLimit(env, ip, 250)
    if (!ipLimit.ok) {
      const retryAfter = Math.max(1, Math.ceil(Number(ipLimit.retryAfterMs ?? 0) / 1000))
      return jsonResponse({ error: 'Rate limited', retryAfterMs: ipLimit.retryAfterMs ?? 0 }, 429, { 'Retry-After': String(retryAfter) })
    }
    const walletLimit = await checkAndTouchWalletRateLimit(env, walletAddress, 'confirm', 2)
    if (!walletLimit.ok) {
      const retryAfter = Math.max(1, Math.ceil(Number(walletLimit.retryAfterSec ?? 1)))
      return jsonResponse({ error: 'Rate limited', retryAfterSec: walletLimit.retryAfterSec ?? 1 }, 429, { 'Retry-After': String(retryAfter) })
    }
    const rpcUrl = getSolanaRpcUrl(env)
    const connection = new Connection(rpcUrl, { commitment: 'confirmed' } as any)
    const tx = await connection.getTransaction(signature, { maxSupportedTransactionVersion: 0 } as any)
    if (!tx) return jsonResponse({ error: 'Transaction not found' }, 400)
    if ((tx as any)?.meta?.err) return jsonResponse({ error: 'Transaction failed' }, 400)
    let metadataUri: string | null = null
    try {
      const confirmed = await confirmShipCoreMint({
        env,
        walletAddress,
        assetAddress,
        expectedCollection: shipCollection || null,
      })
      metadataUri = confirmed.metadataUri ?? null
    } catch (e: any) {
      return jsonResponse({ error: e?.message || 'Mint not confirmed' }, 400)
    }
    const now = Math.floor(Date.now() / 1000)
    await env.DB!.prepare(
      'INSERT OR IGNORE INTO game_ship_nfts (wallet_address, minted_at, mint_signature, asset_address, metadata_uri) VALUES (?, ?, ?, ?, ?)'
    ).bind(walletAddress, now, signature, assetAddress, metadataUri).run()
    await env.DB!.prepare(
      'UPDATE game_ship_nfts SET mint_signature = COALESCE(mint_signature, ?), asset_address = COALESCE(asset_address, ?), metadata_uri = COALESCE(metadata_uri, ?), minted_at = COALESCE(minted_at, ?) WHERE wallet_address = ?'
    ).bind(signature, assetAddress, metadataUri, now, walletAddress).run()
    const current = await env.DB!.prepare('SELECT minted_at, mint_signature, asset_address, metadata_uri FROM game_ship_nfts WHERE wallet_address = ?')
      .bind(walletAddress).first<{ minted_at: number; mint_signature: string | null; asset_address: string | null; metadata_uri: string | null }>()
    return jsonResponse({
      success: true,
      hasNft: true,
      mintedAt: current?.minted_at ?? now,
      mintSignature: current?.mint_signature ?? signature,
      assetAddress: current?.asset_address ?? assetAddress,
      metadataUri: metadataUri
    })
  }

  if (requireShipNft) {
    const row = await env.DB?.prepare('SELECT 1 as v FROM game_ship_nfts WHERE wallet_address = ?')
      .bind(walletAddress).first<{ v: number }>()
    if (!row) {
      return jsonResponse({ error: 'Ship NFT required' }, 403)
    }
  }

  if (path === '/api/v1/game/status') {
    return await handleGetPlayerStatus(env, userId, walletAddress);
  } else if (path === '/api/v1/game/ports') {
    return await handleGetPorts(env);
  } else if (path === '/api/v1/game/dungeons' && request.method === 'GET') {
    return await handleGetDungeons(env);
  } else if (path === '/api/v1/game/market') {
    return await handleGetMarket(env, userId);
  } else if (path === '/api/v1/game/inventory') {
    return await handleGetInventory(env, userId);
  } else if (path === '/api/v1/game/buy' && request.method === 'POST') {
    return await handleBuyGoods(request, env, userId);
  } else if (path === '/api/v1/game/sell' && request.method === 'POST') {
    return await handleSellGoods(request, env, userId);
  } else if (path === '/api/v1/game/sail' && request.method === 'POST') {
    return await handleSail(request, env, userId);
  } else if (path === '/api/v1/game/travel/status' && request.method === 'GET') {
    return await handleTravelStatus(env, userId);
  } else if (path === '/api/v1/game/travel/claim' && request.method === 'POST') {
    return await handleTravelClaim(request, env, userId);
  } else if (path === '/api/v1/game/interact' && request.method === 'POST') {
    return await handleInteract(request, env, userId);
  } else if (path === '/api/v1/game/dungeon/enter' && request.method === 'POST') {
    return await handleDungeonEnter(request, env, userId);
  } else if (path === '/api/v1/game/dungeon/action' && request.method === 'POST') {
    return await handleDungeonAction(request, env, userId);
  } else if (path === '/api/v1/game/season/status') {
    return await handleGetSeasonStatus(env);
  } else if (path === '/api/v1/game/map/chunk' && request.method === 'GET') {
    return await handleGetMapChunk(request, env, userId);
  } else if (path === '/api/v1/game/map/beacon' && request.method === 'POST') {
    return await handlePlaceBeacon(request, env, userId);
  } else if (path === '/api/v1/game/move' && request.method === 'POST') {
    return await handleMove(request, env, userId);
  } else if (path === '/api/v1/game/mint' && request.method === 'POST') {
    return await handleMint(request, env, userId);
  } else if (path === '/api/v1/game/mint/simulate' && request.method === 'POST') {
    return await handleSimulateMint(request, env, userId);
  } else if (path === '/api/v1/game/season/contribute' && request.method === 'POST') {
    return await handleSeasonContribute(request, env, userId);
  } else if (path === '/api/v1/game/season/leaderboard' && request.method === 'GET') {
    return await handleGetLeaderboard(env);
  } else if (path === '/api/v1/game/shipyard/upgrade' && request.method === 'POST') {
    return await handleShipyardUpgrade(request, env, userId);
  } else if (path === '/api/v1/game/shipyard/repair' && request.method === 'POST') {
    return await handleShipyardRepair(request, env, userId);
  } else if (path === '/api/v1/game/lore' && request.method === 'GET') {
    return await handleGetLore(request, env, userId);
  }

  return null;
}

// Open World Types
interface HexTile {
  q: number;
  r: number;
  type: TileType;
  difficulty: number;
  isExplored: boolean;
  hasBeacon: boolean;
  visitedAt: number;
  visitCount: number;
}

interface SeasonContrib {
  total_contribution: number;
}

interface GameLore {
  id: string;
  title: string;
  content: string;
  unlock_threshold: number;
  category: string;
  source_type: string;
  unlocked_at: number; // null if locked
}

type GameDelta = Record<string, unknown>;

function gameActionResponse(
  payload: {
    success: boolean;
    message: string;
    event?: string | null;
    delta?: GameDelta;
    new_unlocks?: GameLore[];
    [key: string]: unknown;
  },
  status = 200
): Response {
  const { event, delta, new_unlocks, ...rest } = payload;
  return jsonResponse(
    {
      ...rest,
      event: event ?? null,
      delta: delta ?? {},
      new_unlocks: new_unlocks ?? []
    },
    status
  );
}

function createConfigReader(env: Env) {
  const cache = new Map<string, string | null>();

  async function getFromKv(key: string): Promise<string | null> {
    if (!env.KV) return null;
    try {
      return await env.KV.get(`config:${key}`);
    } catch {
      return null;
    }
  }

  async function getFromDb(key: string): Promise<string | null> {
    if (!env.DB) return null;
    try {
      const row = await env.DB.prepare(
        'SELECT config_value FROM app_config WHERE config_key = ? AND is_active = 1'
      ).bind(key).first<{ config_value: string }>();
      return row?.config_value ?? null;
    } catch {
      return null;
    }
  }

  async function getMany(keys: string[]): Promise<Record<string, string | null>> {
    const results: Record<string, string | null> = {};
    const missing: string[] = [];

    await Promise.all(
      keys.map(async (k) => {
        if (cache.has(k)) {
          results[k] = cache.get(k)!;
          return;
        }
        const v = await getFromKv(k);
        if (v != null) {
          cache.set(k, v);
          results[k] = v;
          return;
        }
        missing.push(k);
      })
    );

    if (missing.length > 0 && env.DB) {
      const placeholders = missing.map(() => '?').join(', ');
      const rows = await env.DB.prepare(
        `SELECT config_key as configKey, config_value as configValue
         FROM app_config
         WHERE is_active = 1 AND config_key IN (${placeholders})`
      ).bind(...missing).all<{ configKey: string; configValue: string }>();

      const fromDb = new Map(rows.results.map(r => [r.configKey, r.configValue]));
      for (const k of missing) {
        const v = fromDb.get(k) ?? null;
        cache.set(k, v);
        results[k] = v;
      }
    } else {
      for (const k of missing) {
        if (!(k in results)) results[k] = null;
      }
    }

    return results;
  }

  function number(value: string | null, fallback: number, min?: number, max?: number): number {
    if (value == null) return fallback;
    const n = Number(value);
    if (!Number.isFinite(n)) return fallback;
    if (min != null && n < min) return min;
    if (max != null && n > max) return max;
    return n;
  }

  function json<T>(value: string | null, fallback: T): T {
    if (value == null) return fallback;
    try {
      return JSON.parse(value) as T;
    } catch {
      return fallback;
    }
  }

  return { getMany, number, json };
}

async function fetchLoreById(env: Env, loreId: string, unlockedAt: number): Promise<GameLore | null> {
  if (!env.DB) return null
  const lore = await env.DB.prepare(
    'SELECT id, title, content, unlock_threshold, category, source_type, ? as unlocked_at FROM game_lore_entries WHERE id = ?'
  ).bind(unlockedAt, loreId).first<GameLore>()
  return lore || null
}

async function handleGetLore(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const player = await env.DB.prepare('SELECT id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // Get all lore for active season, check unlock status
  const season = await env.DB.prepare('SELECT id FROM game_seasons WHERE status = "ACTIVE" LIMIT 1').first<{id: string}>();
  if (!season) return jsonResponse({ error: 'No active season' }, 404);

  const lore = await env.DB.prepare(`
    SELECT l.id, l.title, l.content, l.unlock_threshold, l.category, l.source_type, pl.unlocked_at
    FROM game_lore_entries l
    LEFT JOIN game_player_lore pl ON l.id = pl.lore_id AND pl.player_id = ?
    WHERE l.season_id = ?
    ORDER BY l.unlock_threshold ASC
  `).bind(player.id, season.id).all<GameLore>();

  const maskedLore = lore.results.map(item => {
    if (!item.unlocked_at) {
      return {
        ...item,
        title: "ENCRYPTED ARCHIVE",
        content: "ACCESS DENIED. FRACTAL ENCRYPTION ACTIVE.",
        unlocked_at: 0
      };
    }
    return item;
  });

  return jsonResponse({ lore: maskedLore });
}

// --- NEW HELPER FOR LORE DROPS ---
async function checkLoreUnlock(
    env: Env, 
    playerId: number, 
    sourceType: string, 
    sourceTarget: string
): Promise<{ unlocked: boolean, title?: string, id?: string }> {
    try {
        // 1. Get Active Season
        const season = await env.DB!.prepare('SELECT id FROM game_seasons WHERE status = "ACTIVE" LIMIT 1').first<{id: string}>();
        if (!season) return { unlocked: false };

        // 2. Find potential lore
        // Logic: Get all locked lore matching criteria, then random roll
        const lockedLore = await env.DB!.prepare(`
            SELECT id, title, drop_chance FROM game_lore_entries 
            WHERE season_id = ? 
            AND source_type = ? 
            AND (source_target = ? OR source_target IS NULL)
            AND id NOT IN (SELECT lore_id FROM game_player_lore WHERE player_id = ?)
        `).bind(season.id, sourceType, sourceTarget, playerId).all<{id: string, title: string, drop_chance: number}>();

        if (lockedLore.results.length === 0) return { unlocked: false };

        // 3. Roll for drop
        // For simplicity, we check the first match or pick random?
        // Let's iterate and roll for each until one unlocks (to avoid unlocking all at once)
        const now = Math.floor(Date.now() / 1000);

        for (const lore of lockedLore.results) {
            const roll = Math.random();
            if (roll < (lore.drop_chance || 0.1)) { // Default 10% if not set
                // Unlock!
                await env.DB!.prepare('INSERT INTO game_player_lore (player_id, lore_id, unlocked_at) VALUES (?, ?, ?)').bind(playerId, lore.id, now).run();
                return { unlocked: true, title: lore.title, id: lore.id };
            }
        }
        
        return { unlocked: false };
    } catch (e) {
        console.error("Lore check failed:", e);
        return { unlocked: false };
    }
}

async function handleSeasonContribute(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  
  const body = await request.json() as { amount: number };
  const { amount } = body;
  
  if (amount <= 0) return jsonResponse({ error: 'Invalid amount' }, 400);

  const player = await env.DB.prepare('SELECT id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // Check Inventory
  const inventoryItem = await env.DB.prepare('SELECT quantity FROM game_inventory WHERE player_id = ? AND good_id = "signal_fragment"').bind(player.id).first<{quantity: number}>();
  
  if (!inventoryItem || inventoryItem.quantity < amount) {
    return jsonResponse({ error: 'Not enough fragments' }, 400);
  }

  // Get Active Season
  const season = await env.DB.prepare('SELECT id FROM game_seasons WHERE status = "ACTIVE" LIMIT 1').first<{id: string}>();
  if (!season) return jsonResponse({ error: 'No active season' }, 404);

  const now = Math.floor(Date.now() / 1000);
  let message = `Contributed ${amount} fragments. Earned ${amount * 100} G.`;

  // Transaction: Deduct Item -> Add Global Progress -> Add Personal Contrib -> Reward Money
  try {
    await env.DB.batch([
      env.DB.prepare('UPDATE game_inventory SET quantity = quantity - ? WHERE player_id = ? AND good_id = "signal_fragment"').bind(amount, player.id),
      env.DB.prepare('UPDATE game_seasons SET current_progress = current_progress + ? WHERE id = ?').bind(amount, season.id),
      env.DB.prepare(`
        INSERT INTO game_player_season_contrib (player_id, season_id, total_contribution, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(player_id, season_id) DO UPDATE SET
        total_contribution = total_contribution + ?,
        updated_at = ?
      `).bind(player.id, season.id, amount, now, now, amount, now),
      env.DB.prepare('UPDATE game_players SET money = money + ? WHERE id = ?').bind(amount * 100, player.id) // 100G per fragment
    ]);
    
    // Check for Lore Unlock
    const contrib = await env.DB.prepare('SELECT total_contribution FROM game_player_season_contrib WHERE player_id = ? AND season_id = ?').bind(player.id, season.id).first<SeasonContrib>();
    const total = contrib?.total_contribution || 0;

    const newUnlockCandidates = await env.DB.prepare(`
        SELECT id, title FROM game_lore_entries 
        WHERE season_id = ? AND source_type = 'CONTRIBUTION' AND unlock_threshold <= ? 
        AND id NOT IN (SELECT lore_id FROM game_player_lore WHERE player_id = ?)
    `).bind(season.id, total, player.id).all<{id: string, title: string}>();

    const newUnlocks: GameLore[] = [];
    for (const lore of newUnlockCandidates.results) {
        await env.DB.prepare('INSERT INTO game_player_lore (player_id, lore_id, unlocked_at) VALUES (?, ?, ?)').bind(player.id, lore.id, now).run();
        message += ` | NEW ARCHIVE UNLOCKED: ${lore.title}`;
        const full = await fetchLoreById(env, lore.id, now);
        if (full) newUnlocks.push(full);
    }
    
    return gameActionResponse({
      success: true,
      message,
      event: message,
      delta: { contribution: amount, money: amount * 100 },
      new_unlocks: newUnlocks
    });
  } catch (e) {
    console.error(e);
    return jsonResponse({ error: 'Transaction failed' }, 500);
  }
}

interface LeaderboardEntry {
  rank: number;
  player_name: string;
  total_contribution: number;
}

async function handleGetLeaderboard(env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const season = await env.DB.prepare('SELECT id FROM game_seasons WHERE status = "ACTIVE" LIMIT 1').first<{id: string}>();
  if (!season) return jsonResponse({ error: 'No active season' }, 404);

  const results = await env.DB.prepare(`
    SELECT p.name as player_name, c.total_contribution
    FROM game_player_season_contrib c
    JOIN game_players p ON c.player_id = p.id
    WHERE c.season_id = ?
    ORDER BY c.total_contribution DESC
    LIMIT 10
  `).bind(season.id).all<{player_name: string, total_contribution: number}>();

  const leaderboard: LeaderboardEntry[] = results.results.map((r, i) => ({
    rank: i + 1,
    player_name: r.player_name,
    total_contribution: r.total_contribution
  }));

  return jsonResponse({ leaderboard });
}

async function handleShipyardUpgrade(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const body = await request.json() as { type: 'CARGO' | 'SHIP_LEVEL' };
  const { type } = body;

  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const now = Math.floor(Date.now() / 1000);
  const config = createConfigReader(env)
  const econCfg = await config.getMany([
    'game.econ.shipyard_cargo_cost_per_capacity',
    'game.econ.shipyard_cargo_delta_capacity',
    'game.econ.shipyard_ship_level_cost_multiplier',
    'game.econ.port_overrides'
  ])
  const portOverrides = config.json<Record<string, Record<string, unknown>>>(
    econCfg['game.econ.port_overrides'],
    {}
  )
  const portCfg = (portOverrides?.[player.current_port_id] as Record<string, unknown> | undefined) ?? {}
  function portOverride(key: string): string | null {
    const v = portCfg[key]
    if (v === undefined || v === null) return null
    if (typeof v === 'string') return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
    return null
  }

  let cost = 0;
  let newStats: any = {};
  let message = "";
  let delta: GameDelta = {};

  if (type === 'CARGO') {
      const costPerCapacity = config.number(
        portOverride('shipyard_cargo_cost_per_capacity') ?? econCfg['game.econ.shipyard_cargo_cost_per_capacity'],
        50,
        0,
        1000000
      )
      const deltaCapacity = Math.floor(config.number(
        portOverride('shipyard_cargo_delta_capacity') ?? econCfg['game.econ.shipyard_cargo_delta_capacity'],
        10,
        1,
        100000
      ))
      cost = Math.floor(player.cargo_capacity * costPerCapacity);
      if (player.money < cost) return jsonResponse({ error: `Not enough money. Need ${cost} G.` }, 400);
      
      const newCapacity = player.cargo_capacity + deltaCapacity;
      await env.DB.prepare('UPDATE game_players SET money = money - ?, cargo_capacity = ?, updated_at = ? WHERE id = ?')
          .bind(cost, newCapacity, now, player.id).run();
      
      message = `Cargo Hold upgraded! Capacity: ${newCapacity} (Cost: ${cost} G)`;
      newStats = { cargo_capacity: newCapacity };
      delta = { money: -cost, cargo_capacity: deltaCapacity };
  } else if (type === 'SHIP_LEVEL') {
      const costMultiplier = config.number(
        portOverride('shipyard_ship_level_cost_multiplier') ?? econCfg['game.econ.shipyard_ship_level_cost_multiplier'],
        2000,
        0,
        100000000
      )
      cost = Math.floor(player.ship_level * costMultiplier);
      if (player.money < cost) return jsonResponse({ error: `Not enough money. Need ${cost} G.` }, 400);
      
      const newLevel = player.ship_level + 1;
      await env.DB.prepare('UPDATE game_players SET money = money - ?, ship_level = ?, updated_at = ? WHERE id = ?')
          .bind(cost, newLevel, now, player.id).run();
          
      message = `Ship Class upgraded to MK-${newLevel}! (Cost: ${cost} G)`;
      newStats = { ship_level: newLevel };
      delta = { money: -cost, ship_level: 1 };
  } else {
      return jsonResponse({ error: 'Invalid upgrade type' }, 400);
  }

  return gameActionResponse({ success: true, message, event: message, delta, new_unlocks: [], ...newStats });
}

async function handleShipyardRepair(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  await request.json().catch(() => ({}));

  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const now = Math.floor(Date.now() / 1000);
  const config = createConfigReader(env)
  const econCfg = await config.getMany([
    'game.econ.repair_base_cost',
    'game.econ.repair_cost_per_ship_level',
    'game.econ.port_overrides'
  ])
  const portOverrides = config.json<Record<string, Record<string, unknown>>>(
    econCfg['game.econ.port_overrides'],
    {}
  )
  const portCfg = (portOverrides?.[player.current_port_id] as Record<string, unknown> | undefined) ?? {}
  function portOverride(key: string): string | null {
    const v = portCfg[key]
    if (v === undefined || v === null) return null
    if (typeof v === 'string') return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
    return null
  }

  const baseCost = config.number(portOverride('repair_base_cost') ?? econCfg['game.econ.repair_base_cost'], 50, 0, 100000000)
  const perLevel = config.number(portOverride('repair_cost_per_ship_level') ?? econCfg['game.econ.repair_cost_per_ship_level'], 25, 0, 100000000)
  const cost = Math.floor(baseCost + (Math.max(0, player.ship_level - 1) * perLevel))

  const update = await env.DB.prepare(
    'UPDATE game_players SET money = money - ?, updated_at = ? WHERE id = ? AND money >= ?'
  ).bind(cost, now, player.id, cost).run();
  if ((update as any)?.meta?.changes !== 1) {
    return jsonResponse({ error: `Not enough money. Need ${cost} G.` }, 400);
  }

  const message = `Dock repair completed. Cost: ${cost} G.`
  return gameActionResponse({
    success: true,
    message,
    event: message,
    delta: { money: -cost },
    new_unlocks: []
  })
}

async function handleGetMapChunk(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const url = new URL(request.url);
  const centerX = parseInt(url.searchParams.get('q') || '0');
  const centerY = parseInt(url.searchParams.get('r') || '0');
  const radius = 5; // View distance

  const player = await env.DB.prepare('SELECT id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // Generate tiles
  const tiles: HexTile[] = [];
  const config = createConfigReader(env)
  const worldCfg = await config.getMany([
    'game.world.tile_weights'
  ])
  const weights = normalizeWeights(config.json<Record<string, unknown>>(worldCfg['game.world.tile_weights'], {}))
  
  // Get exploration data
  const explored = await env.DB.prepare(`
    SELECT q, r, visited_at, visit_count FROM game_player_exploration 
    WHERE player_id = ? AND q BETWEEN ? AND ? AND r BETWEEN ? AND ?
  `).bind(player.id, centerX - radius, centerX + radius, centerY - radius, centerY + radius).all<{q: number, r: number, visited_at: number | null, visit_count: number | null}>();
  
  const exploredByCoord = new Map(
    explored.results.map(e => [`${e.q}_${e.r}`, { visitedAt: e.visited_at || 0, visitCount: e.visit_count || 1 }])
  );

  const overrides = await env.DB.prepare(`
    SELECT q, r, type, has_beacon as hasBeacon
    FROM game_map_chunks
    WHERE q BETWEEN ? AND ? AND r BETWEEN ? AND ?
  `).bind(centerX - radius, centerX + radius, centerY - radius, centerY + radius)
    .all<{ q: number; r: number; type: string | null; hasBeacon: number | boolean | null }>();
  const overrideByCoord = new Map(
    overrides.results.map(o => [`${o.q}_${o.r}`, o])
  )

  for (let q = centerX - radius; q <= centerX + radius; q++) {
    for (let r = centerY - radius; r <= centerY + radius; r++) {
      // Hex distance check
      if (Math.abs(q - centerX + r - centerY) > radius) continue;
      
      const coordKey = `${q}_${r}`
      const override = overrideByCoord.get(coordKey)
      const type = (override?.type as TileType | null) ?? pickTileType(q, r, weights);
      const exploredInfo = exploredByCoord.get(coordKey)
      tiles.push({
        q, r,
        type,
        difficulty: Math.abs(q) + Math.abs(r), // Simple difficulty scaling
        isExplored: exploredInfo != null,
        hasBeacon: !!override?.hasBeacon,
        visitedAt: exploredInfo?.visitedAt || 0,
        visitCount: exploredInfo?.visitCount || 0
      });
    }
  }

  return jsonResponse({ tiles });
}

async function handlePlaceBeacon(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  const body = await request.json().catch(() => ({} as any)) as { message?: string };
  const message = (body.message || '').toString().slice(0, 200);

  const player = await env.DB.prepare('SELECT id, q, r FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer & { q: number; r: number }>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const q = player.q || 0
  const r = player.r || 0

  const explored = await env.DB.prepare(
    'SELECT 1 FROM game_player_exploration WHERE player_id = ? AND q = ? AND r = ?'
  ).bind(player.id, q, r).first<{ 1: number }>()
  if (!explored) return jsonResponse({ error: 'Tile not explored' }, 400);

  const now = Math.floor(Date.now() / 1000)
  const id = `${q}_${r}`
  await env.DB.prepare(
    `INSERT INTO game_map_chunks (id, q, r, type, has_beacon, beacon_message, beacon_owner_id, created_at, updated_at)
     VALUES (?, ?, ?, NULL, 1, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET
       has_beacon = 1,
       beacon_message = excluded.beacon_message,
       beacon_owner_id = excluded.beacon_owner_id,
       updated_at = excluded.updated_at`
  ).bind(id, q, r, message, player.id, now, now).run()

  const event = message ? `Beacon deployed: "${message}"` : 'Beacon deployed.'
  return gameActionResponse({
    success: true,
    message: event,
    event,
    delta: { beacon: { q, r, hasBeacon: true } },
    new_unlocks: []
  })
}

async function handleMove(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  
  const body = await request.json() as { q: number, r: number };
  const { q, r } = body;

  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer & {q: number, r: number}>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // Validate movement (must be adjacent)
  const currentQ = player.q || 0;
  const currentR = player.r || 0;
  const dist = (Math.abs(currentQ - q) + Math.abs(currentQ + currentR - q - r) + Math.abs(currentR - r)) / 2;
  
  if (dist > 1) return jsonResponse({ error: 'Too far' }, 400);
  
  // Cost
  const config = createConfigReader(env)
  const econCfg = await config.getMany([
    'game.econ.jump_cost',
    'game.econ.jump_cost_per_ship_level'
  ])
  const jumpCost = config.number(econCfg['game.econ.jump_cost'], 10, 0, 1000000)
  const jumpCostPerLevel = config.number(econCfg['game.econ.jump_cost_per_ship_level'], 0, 0, 1000000)
  const cost = Math.floor(jumpCost + Math.max(0, (player.ship_level - 1)) * jumpCostPerLevel);
  if (player.money < cost) return jsonResponse({ error: 'Not enough fuel (money)' }, 400);

  const now = Math.floor(Date.now() / 1000);
  let moneyDelta = -cost;
  let moneyAfter = player.money - cost;
  const inventoryDelta: Array<Record<string, unknown>> = [];

  const worldCfg = await config.getMany([
    'game.world.tile_weights',
    'game.world.revisit_event_multiplier',
    'game.world.revisit_anomaly_drop_enabled',
    'game.world.event.positive_chance',
    'game.world.event.negative_chance',
    'game.world.event.flavor_chance',
    'game.world.event.gain_min',
    'game.world.event.gain_max',
    'game.world.event.loss_min',
    'game.world.event.loss_max',
    'game.world.anomaly.fragment_min',
    'game.world.anomaly.fragment_max'
  ])
  const weights = normalizeWeights(config.json<Record<string, unknown>>(worldCfg['game.world.tile_weights'], {}))

  const revisitEventMultiplier = config.number(worldCfg['game.world.revisit_event_multiplier'], 0.5, 0, 1)
  const revisitAnomalyDropEnabled = (worldCfg['game.world.revisit_anomaly_drop_enabled']?.toString()?.toLowerCase() == 'true')
  const positiveChanceBase = config.number(worldCfg['game.world.event.positive_chance'], 0.1, 0, 1)
  const negativeChanceBase = config.number(worldCfg['game.world.event.negative_chance'], 0.15, 0, 1)
  const flavorChanceBase = config.number(worldCfg['game.world.event.flavor_chance'], 0.05, 0, 1)
  const gainMin = Math.floor(config.number(worldCfg['game.world.event.gain_min'], 50, 0, 100000000))
  const gainMax = Math.floor(config.number(worldCfg['game.world.event.gain_max'], 150, gainMin, 100000000))
  const lossMin = Math.floor(config.number(worldCfg['game.world.event.loss_min'], 20, 0, 100000000))
  const lossMax = Math.floor(config.number(worldCfg['game.world.event.loss_max'], 80, lossMin, 100000000))
  const fragmentMin = Math.floor(config.number(worldCfg['game.world.anomaly.fragment_min'], 1, 0, 100000))
  const fragmentMax = Math.floor(config.number(worldCfg['game.world.anomaly.fragment_max'], 2, fragmentMin, 100000))

  const prevExploration = await env.DB.prepare(
    'SELECT visited_at, visit_count FROM game_player_exploration WHERE player_id = ? AND q = ? AND r = ?'
  ).bind(player.id, q, r).first<{ visited_at: number | null; visit_count: number | null }>()
  const isRevisit = !!prevExploration
  const nextVisitCount = isRevisit ? ((prevExploration?.visit_count || 1) + 1) : 1
  const positiveChance = isRevisit ? positiveChanceBase * revisitEventMultiplier : positiveChanceBase
  const negativeChance = isRevisit ? negativeChanceBase * revisitEventMultiplier : negativeChanceBase
  const flavorChance = isRevisit ? flavorChanceBase * revisitEventMultiplier : flavorChanceBase
  const eventRoll = Math.random()

  const override = await env.DB.prepare(
    'SELECT type, has_beacon as hasBeacon FROM game_map_chunks WHERE q = ? AND r = ? LIMIT 1'
  ).bind(q, r).first<{ type: string | null; hasBeacon: number | boolean | null }>()
  const targetTileType: TileType = (override?.type as TileType | null) ?? pickTileType(q, r, weights)
  
  // Update Player
  await env.DB.prepare('UPDATE game_players SET q = ?, r = ?, money = money - ? WHERE id = ?').bind(q, r, cost, player.id).run();
  
  // Mark Explored
  await env.DB.prepare(
    `INSERT INTO game_player_exploration (player_id, q, r, visited_at, visit_count)
     VALUES (?, ?, ?, ?, 1)
     ON CONFLICT(player_id, q, r) DO UPDATE SET
       visited_at = excluded.visited_at,
       visit_count = visit_count + 1`
  ).bind(player.id, q, r, now).run();

  // Event Trigger (Simulated)
  let event: string | null = null;
  if (eventRoll < negativeChance) {
    const loss = Math.floor(Math.random() * (lossMax - lossMin + 1)) + lossMin;
    if (moneyAfter >= loss) {
      event = `Ambushed by Glitch Pirates! Paid ransom. (-${loss} G)`;
      moneyDelta -= loss;
      moneyAfter -= loss;
      await env.DB.prepare('UPDATE game_players SET money = money - ? WHERE id = ?').bind(loss, player.id).run();
    } else {
      event = `Ambushed by Glitch Pirates! Hull damaged.`;
    }
  } else if (eventRoll < negativeChance + flavorChance) {
    event = "Passed a derelict signal buoy playing ancient music.";
  } else if (eventRoll > (1 - positiveChance)) {
    const gain = Math.floor(Math.random() * (gainMax - gainMin + 1)) + gainMin;
    event = `Encountered a drifting data cache! (+${gain} G)`;
    moneyDelta += gain;
    moneyAfter += gain;
    await env.DB.prepare('UPDATE game_players SET money = money + ? WHERE id = ?').bind(gain, player.id).run();
  }

  // Handle Anomaly Drop (Signal Fragment)
  if (targetTileType === 'ANOMALY' && (!isRevisit || revisitAnomalyDropEnabled)) {
      const fragmentCount = Math.floor(Math.random() * (fragmentMax - fragmentMin + 1)) + fragmentMin;
      await env.DB.prepare(`
        INSERT INTO game_inventory (player_id, good_id, quantity, avg_cost, created_at, updated_at)
        VALUES (?, 'signal_fragment', ?, 0, ?, ?)
        ON CONFLICT(player_id, good_id) DO UPDATE SET
        quantity = quantity + ?,
        updated_at = ?
      `).bind(player.id, fragmentCount, now, now, fragmentCount, now).run();
      inventoryDelta.push({ good_id: 'signal_fragment', delta: fragmentCount });
      
      const fragmentMsg = `Recovered ${fragmentCount} Signal Fragments from the anomaly!`;
      event = event ? `${event} | ${fragmentMsg}` : fragmentMsg;
  }

  // --- LORE DROP CHECK (MAP_DISCOVERY) ---
  const newUnlocks: GameLore[] = [];
  const loreResult = await checkLoreUnlock(env, player.id, 'MAP_DISCOVERY', targetTileType);
  if (loreResult.unlocked) {
      const msg = `ARCHIVE DECRYPTED: ${loreResult.title}`;
      event = event ? `${event} | ${msg}` : msg;
      if (loreResult.id) {
        const lore = await fetchLoreById(env, loreResult.id, now);
        if (lore) newUnlocks.push(lore);
      }
  }

  return gameActionResponse({
    success: true,
    message: "Jump successful",
    event,
    delta: { money: moneyDelta, position: { q, r }, inventory: inventoryDelta, exploration: { isRevisit, visitCount: nextVisitCount }, tile: { type: targetTileType, hasBeacon: !!override?.hasBeacon } },
    position: { q, r },
    new_unlocks: newUnlocks
  });
}

async function handleMint(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const body = await request.json() as { goodId: string };
  const { goodId } = body;
  if (!goodId) return jsonResponse({ error: 'goodId is required' }, 400);

  const player = await env.DB.prepare('SELECT id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // Check inventory
  const item = await env.DB.prepare('SELECT quantity FROM game_inventory WHERE player_id = ? AND good_id = ?').bind(player.id, goodId).first<{quantity: number}>();
  if (!item || item.quantity < 1) return jsonResponse({ error: 'Not enough item' }, 400);

  // Consume item
  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare('UPDATE game_inventory SET quantity = quantity - 1, updated_at = ? WHERE player_id = ? AND good_id = ?').bind(now, player.id, goodId).run();

  // Simulate Mint
  const mintAddress = `mint_${goodId}_${Date.now()}_${Math.random().toString(36).substring(7)}`;
  const message = `Successfully minted ${goodId} into NFT.`;

  return gameActionResponse({
    success: true,
    message,
    event: message,
    delta: { inventory: [{ good_id: goodId, delta: -1 }] },
    mintAddress
  });
}

async function handleSimulateMint(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  const mintAddress = `mint_sim_${Date.now()}_${Math.random().toString(36).substring(7)}`;
  return gameActionResponse({
    success: true,
    message: "Mint simulation successful.",
    event: "Mint simulation successful.",
    mintAddress
  });
}

type TileType = 'SAFE_ZONE' | 'VOID' | 'ASTEROID' | 'NEBULA' | 'ANOMALY'

function deterministicValue(q: number, r: number): number {
  const hash = Math.sin(q * 12.9898 + r * 78.233) * 43758.5453;
  return hash - Math.floor(hash);
}

function normalizeWeights(weights: Record<string, unknown>): Record<TileType, number> {
  const picked: Record<TileType, number> = {
    SAFE_ZONE: 0,
    VOID: 0,
    ASTEROID: 0,
    NEBULA: 0,
    ANOMALY: 0
  }
  for (const k of Object.keys(picked) as TileType[]) {
    const v = weights[k]
    const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : 0
    picked[k] = Number.isFinite(n) ? Math.max(0, n) : 0
  }
  picked.SAFE_ZONE = 0
  const sum = picked.VOID + picked.ASTEROID + picked.NEBULA + picked.ANOMALY
  if (sum <= 0) {
    return { SAFE_ZONE: 0, VOID: 0.6, ASTEROID: 0.2, NEBULA: 0.15, ANOMALY: 0.05 }
  }
  return {
    SAFE_ZONE: 0,
    VOID: picked.VOID / sum,
    ASTEROID: picked.ASTEROID / sum,
    NEBULA: picked.NEBULA / sum,
    ANOMALY: picked.ANOMALY / sum
  }
}

function pickTileType(
  q: number,
  r: number,
  weights: Record<TileType, number>
): TileType {
  if (q === 0 && r === 0) return 'SAFE_ZONE';
  const v = deterministicValue(q, r)
  const cdfVoid = weights.VOID
  const cdfAsteroid = cdfVoid + weights.ASTEROID
  const cdfNebula = cdfAsteroid + weights.NEBULA
  if (v < cdfVoid) return 'VOID'
  if (v < cdfAsteroid) return 'ASTEROID'
  if (v < cdfNebula) return 'NEBULA'
  return 'ANOMALY'
}

// Season Types
interface GameSeason {
  id: string;
  name: string;
  description: string;
  start_time: number;
  end_time: number;
  global_target: number;
  current_progress: number;
  status: string;
}

interface GameWorldState {
  id: number;
  season_id: string;
  date: string;
  chaos_level: number;
  market_stability: number;
  daily_news_summary: string;
}

async function handleGetSeasonStatus(env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  // Get Active Season
  const season = await env.DB.prepare('SELECT * FROM game_seasons WHERE status = "ACTIVE" LIMIT 1').first<GameSeason>();
  if (!season) {
    return jsonResponse({ error: 'No active season' }, 404);
  }

  // Get Latest World State (Daily News)
  const worldState = await env.DB.prepare('SELECT * FROM game_world_state WHERE season_id = ? ORDER BY id DESC LIMIT 1').bind(season.id).first<GameWorldState>();

  // If news is stale (e.g. older than 24h), trigger AI generation (Simulated here, ideally via Cron)
  // For MVP, we just return what's in DB.
  
  return jsonResponse({
    season,
    worldState: worldState || {
      daily_news_summary: "No news today. The void is silent.",
      chaos_level: 0,
      market_stability: 100
    }
  });
}

// Dungeon Types
interface DungeonState {
  player_id: number;
  dungeon_id: string;
  current_depth: number;
  current_room_description: string;
  sanity: number;
  health: number;
  inventory: string;
  status: 'ACTIVE' | 'COMPLETED' | 'FAILED';
}

interface DungeonDef {
  id: string
  name: string
  description: string
  difficulty_level: number
  max_depth: number
  entry_cost: number
}

type DungeonLootEntry = {
  good_id: string
  chance?: number
  min?: number
  max?: number
}

type DungeonLootTables = Record<
  string,
  Partial<Record<'SEARCH' | 'COMPLETE', DungeonLootEntry[]>>
>

type DungeonActionCosts = {
  base_sanity_cost?: number
  base_health_cost?: number
  move_sanity_cost?: number
  move_health_cost?: number
  search_sanity_cost?: number
  search_health_cost?: number
  attack_sanity_cost?: number
  attack_health_cost?: number
  difficulty_sanity_per_level?: number
  difficulty_health_per_level?: number
  depth_sanity_per_depth?: number
  depth_health_per_depth?: number
}

type DungeonCompletionRewards = {
  money_per_difficulty?: number
  contribution_per_difficulty?: number
}

function parseDungeonDefs(value: string | null): Map<string, DungeonDef> {
  if (!value) return new Map()
  try {
    const raw = JSON.parse(value) as any
    const list: any[] = Array.isArray(raw) ? raw : typeof raw === 'object' && raw ? Object.values(raw) : []
    const map = new Map<string, DungeonDef>()
    for (const it of list) {
      const id = (it?.id || '').toString()
      if (!id) continue
      map.set(id, {
        id,
        name: (it?.name || id).toString(),
        description: (it?.description || '').toString(),
        difficulty_level: Math.max(1, Math.floor(Number(it?.difficulty_level ?? it?.difficultyLevel ?? 1) || 1)),
        max_depth: Math.max(1, Math.floor(Number(it?.max_depth ?? it?.maxDepth ?? 5) || 5)),
        entry_cost: Math.max(0, Math.floor(Number(it?.entry_cost ?? it?.entryCost ?? 0) || 0))
      })
    }
    return map
  } catch {
    return new Map()
  }
}

function parseDungeonLootTables(value: string | null): DungeonLootTables {
  if (!value) return {}
  try {
    const raw = JSON.parse(value) as any
    return (raw && typeof raw === 'object') ? raw : {}
  } catch {
    return {}
  }
}

function parseDungeonActionCosts(value: string | null): DungeonActionCosts {
  if (!value) return {}
  try {
    const raw = JSON.parse(value) as any
    return (raw && typeof raw === 'object') ? raw : {}
  } catch {
    return {}
  }
}

function parseDungeonCompletionRewards(value: string | null): DungeonCompletionRewards {
  if (!value) return {}
  try {
    const raw = JSON.parse(value) as any
    return (raw && typeof raw === 'object') ? raw : {}
  } catch {
    return {}
  }
}

function normalizeLootEntries(entries: DungeonLootEntry[] | undefined): DungeonLootEntry[] {
  if (!entries) return []
  return entries
    .map((e) => ({
      good_id: (e.good_id || '').toString(),
      chance: typeof e.chance === 'number' ? e.chance : (e.chance != null ? Number(e.chance) : 1),
      min: typeof e.min === 'number' ? e.min : (e.min != null ? Number(e.min) : 1),
      max: typeof e.max === 'number' ? e.max : (e.max != null ? Number(e.max) : 1),
    }))
    .filter((e) => !!e.good_id)
    .map((e) => ({
      ...e,
      chance: Number.isFinite(e.chance) ? Math.max(0, Math.min(1, e.chance!)) : 1,
      min: Number.isFinite(e.min) ? Math.max(0, Math.floor(e.min!)) : 0,
      max: Number.isFinite(e.max) ? Math.max(0, Math.floor(e.max!)) : 0
    }))
}

function randomInt(min: number, max: number): number {
  if (max <= min) return min
  return Math.floor(Math.random() * (max - min + 1)) + min
}

async function applyLoot(
  env: Env,
  playerId: number,
  entries: DungeonLootEntry[],
  now: number
): Promise<{ inventoryDelta: Array<Record<string, unknown>>; moneyDelta: number; text: string }> {
  if (!env.DB) return { inventoryDelta: [], moneyDelta: 0, text: '' }
  const inventoryDelta: Array<Record<string, unknown>> = []
  let moneyDelta = 0
  const gained: string[] = []
  const stmts: any[] = []

  for (const e of normalizeLootEntries(entries)) {
    const chance = e.chance ?? 1
    if (Math.random() > chance) continue
    const qty = randomInt(e.min ?? 1, e.max ?? 1)
    if (qty <= 0) continue

    if (e.good_id === 'money') {
      moneyDelta += qty
      gained.push(`${qty} G`)
      continue
    }

    stmts.push(
      env.DB.prepare(
        `INSERT INTO game_inventory (player_id, good_id, quantity, avg_cost, created_at, updated_at)
         VALUES (?, ?, ?, 0, ?, ?)
         ON CONFLICT(player_id, good_id) DO UPDATE SET
           quantity = quantity + ?,
           updated_at = ?`
      ).bind(playerId, e.good_id, qty, now, now, qty, now)
    )
    inventoryDelta.push({ good_id: e.good_id, delta: qty })
    gained.push(`${qty} ${e.good_id}`)
  }

  if (moneyDelta !== 0) {
    stmts.push(
      env.DB.prepare('UPDATE game_players SET money = money + ? WHERE id = ?')
        .bind(moneyDelta, playerId)
    )
  }

  if (stmts.length > 0) {
    await env.DB.batch(stmts)
  }

  return {
    inventoryDelta,
    moneyDelta,
    text: gained.length > 0 ? ` [LOOT] ${gained.join(', ')}` : ''
  }
}

async function handleDungeonEnter(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  
  const body = await request.json() as { dungeonId: string };
  const { dungeonId } = body;

  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // Check if player is already in a dungeon
  const existingState = await env.DB.prepare('SELECT * FROM game_player_dungeon_state WHERE player_id = ? AND status = "ACTIVE"').bind(player.id).first<DungeonState>();
  if (existingState) {
    return gameActionResponse({ 
      success: true, 
      message: "Resuming exploration...",
      event: "Resuming exploration...",
      new_unlocks: [],
      state: existingState 
    });
  }

  const config = createConfigReader(env)
  const cfg = await config.getMany(['game.dungeon.defs'])
  const defs = parseDungeonDefs(cfg['game.dungeon.defs'])

  const dungeon = defs.get(dungeonId) || await env.DB.prepare('SELECT * FROM game_dungeons WHERE id = ?').bind(dungeonId).first<DungeonDef>();
  if (!dungeon) return jsonResponse({ error: 'Dungeon not found' }, 404);

  if (player.money < dungeon.entry_cost) return jsonResponse({ error: 'Not enough money' }, 400);

  // Deduct cost
  const now = Math.floor(Date.now() / 1000);
  const playerUpdate = await env.DB.prepare(
    'UPDATE game_players SET money = money - ? WHERE id = ? AND money >= ?'
  ).bind(dungeon.entry_cost, player.id, dungeon.entry_cost).run();
  if ((playerUpdate as any)?.meta?.changes !== 1) return jsonResponse({ error: 'Not enough money' }, 400);

  // Generate initial room
  const roomDesc = await generateRoomDescription(env, dungeon.name, 1, "ENTRANCE");

  // Create state
  const state = await env.DB.prepare(`
    INSERT INTO game_player_dungeon_state (player_id, dungeon_id, current_room_description, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?) RETURNING *
  `).bind(player.id, dungeonId, roomDesc, now, now).first<DungeonState>();

  const event = `Entered ${dungeon.name}`
  return gameActionResponse({
    success: true,
    message: event,
    event,
    delta: { money: -dungeon.entry_cost, dungeon: { dungeon_id: dungeonId, status: 'ACTIVE', max_depth: dungeon.max_depth, difficulty_level: dungeon.difficulty_level } },
    new_unlocks: [],
    state
  });
}

async function handleDungeonAction(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  
  const body = await request.json() as { action: string }; // MOVE, SEARCH, ATTACK, RETREAT
  const { action } = body;

  const player = await env.DB.prepare('SELECT id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const state = await env.DB.prepare('SELECT * FROM game_player_dungeon_state WHERE player_id = ? AND status = "ACTIVE"').bind(player.id).first<DungeonState>();
  if (!state) return jsonResponse({ error: 'No active dungeon session' }, 404);

  const config = createConfigReader(env)
  const cfg = await config.getMany([
    'game.dungeon.defs',
    'game.dungeon.loot_tables',
    'game.dungeon.action_costs',
    'game.dungeon.completion_rewards'
  ])
  const defs = parseDungeonDefs(cfg['game.dungeon.defs'])
  const dungeonDef = defs.get(state.dungeon_id) || await env.DB.prepare('SELECT * FROM game_dungeons WHERE id = ?').bind(state.dungeon_id).first<DungeonDef>();
  const dungeonName = dungeonDef?.name || "Unknown Sector";
  const dungeonDesc = dungeonDef?.description || "A corrupted data void.";
  const dungeonDiff = dungeonDef?.difficulty_level || 1;
  const dungeonMaxDepth = dungeonDef?.max_depth || 5;
  const lootTables = parseDungeonLootTables(cfg['game.dungeon.loot_tables'])
  const actionCosts = parseDungeonActionCosts(cfg['game.dungeon.action_costs'])
  const completionRewards = parseDungeonCompletionRewards(cfg['game.dungeon.completion_rewards'])

  const now = Math.floor(Date.now() / 1000);
  let message = "";
  let newDesc = state.current_room_description;
  let sanityChange = -5;
  let healthChange = 0;
  const inventoryDelta: Array<Record<string, unknown>> = [];
  let moneyDelta = 0
  let contributionDelta = 0
  const newUnlocks: GameLore[] = [];

  if (action === 'RETREAT') {
    await env.DB.prepare('UPDATE game_player_dungeon_state SET status = "FAILED", updated_at = ? WHERE player_id = ?').bind(now, player.id).run();
    const msg = "Retreated safely. Exploration ended."
    return gameActionResponse({
      success: true,
      message: msg,
      event: msg,
      delta: { dungeon: { status: 'FAILED' } },
      new_unlocks: newUnlocks,
      state: { ...state, status: 'FAILED' }
    });
  }

  // AI Logic for Action Resolution
  const aiResult = await resolveDungeonAction(env, state, action, dungeonName, dungeonDesc, dungeonDiff);
  
  message = aiResult.narrative;
  newDesc = aiResult.nextRoomDesc || state.current_room_description;
  sanityChange = aiResult.sanityDelta;
  healthChange = aiResult.healthDelta;

  function num(v: any, fallback: number): number {
    const n = Number(v)
    return Number.isFinite(n) ? n : fallback
  }
  const extraSanity =
    -Math.floor(num(actionCosts.base_sanity_cost, 5)) +
    -Math.floor(action === 'MOVE' ? num(actionCosts.move_sanity_cost, 2) :
      action === 'SEARCH' ? num(actionCosts.search_sanity_cost, 1) :
      action === 'ATTACK' ? num(actionCosts.attack_sanity_cost, 2) : 0) +
    -Math.floor(Math.max(0, dungeonDiff - 1) * num(actionCosts.difficulty_sanity_per_level, 1)) +
    -Math.floor(Math.max(0, state.current_depth - 1) * num(actionCosts.depth_sanity_per_depth, 0))
  const extraHealth =
    -Math.floor(num(actionCosts.base_health_cost, 0)) +
    -Math.floor(action === 'MOVE' ? num(actionCosts.move_health_cost, 0) :
      action === 'SEARCH' ? num(actionCosts.search_health_cost, 0) :
      action === 'ATTACK' ? num(actionCosts.attack_health_cost, 1) : 0) +
    -Math.floor(Math.max(0, dungeonDiff - 1) * num(actionCosts.difficulty_health_per_level, 0)) +
    -Math.floor(Math.max(0, state.current_depth - 1) * num(actionCosts.depth_health_per_depth, 0))

  sanityChange = Math.max(-100, Math.min(20, sanityChange + extraSanity))
  healthChange = Math.max(-100, Math.min(20, healthChange + extraHealth))

  const tableKey = lootTables[state.dungeon_id] ? state.dungeon_id : (lootTables['default'] ? 'default' : '')
  const table = tableKey ? lootTables[tableKey] : undefined
  const defaultSearchLoot: DungeonLootEntry[] = [{ good_id: 'signal_fragment', chance: 0.2, min: 1, max: 3 }]
  const defaultCompleteLoot: DungeonLootEntry[] = [{ good_id: 'signal_fragment', chance: 1, min: 2, max: 5 }]
  const searchEntries = (table?.SEARCH && table.SEARCH.length > 0) ? table.SEARCH : defaultSearchLoot
  const completeEntries = (table?.COMPLETE && table.COMPLETE.length > 0) ? table.COMPLETE : defaultCompleteLoot

  if (action === 'SEARCH') {
    const loot = await applyLoot(env, player.id, searchEntries, now)
    inventoryDelta.push(...loot.inventoryDelta)
    moneyDelta += loot.moneyDelta
    message += loot.text
  }

  // --- LORE DROP CHECK (DUNGEON_DROP) ---
  if (action === 'SEARCH') {
    const loreResult = await checkLoreUnlock(env, player.id, 'DUNGEON_DROP', state.dungeon_id);
    if (loreResult.unlocked) {
        message += ` [DATA] DECRYPTED ARCHIVE: ${loreResult.title}`;
        if (loreResult.id) {
          const lore = await fetchLoreById(env, loreResult.id, now)
          if (lore) newUnlocks.push(lore)
        }
    }
  }

  const depthDelta = action === 'MOVE' ? 1 : 0
  const nextDepth = state.current_depth + depthDelta
  const completed = action === 'MOVE' && nextDepth >= dungeonMaxDepth

  const newSanity = Math.max(0, state.sanity + sanityChange);
  const newHealth = Math.max(0, state.health + healthChange);
  
  // Check death/insanity
  if (newSanity <= 0 || newHealth <= 0) {
    await env.DB.prepare('UPDATE game_player_dungeon_state SET status = "FAILED", sanity = 0, health = 0, updated_at = ? WHERE player_id = ?').bind(now, player.id).run();
    return gameActionResponse({ 
      success: true, 
      message: newSanity <= 0 ? "Lost to madness..." : "Critical hull failure...", 
      event: message,
      delta: { sanity: -state.sanity, health: -state.health, dungeon: { status: 'FAILED' }, inventory: inventoryDelta },
      new_unlocks: newUnlocks,
      state: { ...state, sanity: 0, health: 0, status: 'FAILED' } 
    });
  }

  if (completed) {
    const rewardMoney = Math.floor(Math.max(0, num(completionRewards.money_per_difficulty, 0)) * dungeonDiff)
    const rewardContribution = Math.floor(Math.max(0, num(completionRewards.contribution_per_difficulty, 0)) * dungeonDiff)

    if (rewardMoney > 0) {
      await env.DB.prepare('UPDATE game_players SET money = money + ? WHERE id = ?').bind(rewardMoney, player.id).run()
      moneyDelta += rewardMoney
    }

    if (rewardContribution > 0) {
      const season = await env.DB.prepare('SELECT id FROM game_seasons WHERE status = "ACTIVE" LIMIT 1').first<{id: string}>()
      if (season) {
        await env.DB.batch([
          env.DB.prepare('UPDATE game_seasons SET current_progress = current_progress + ? WHERE id = ?').bind(rewardContribution, season.id),
          env.DB.prepare(
            `INSERT INTO game_player_season_contrib (player_id, season_id, total_contribution, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(player_id, season_id) DO UPDATE SET
               total_contribution = total_contribution + ?,
               updated_at = ?`
          ).bind(player.id, season.id, rewardContribution, now, now, rewardContribution, now)
        ])
        contributionDelta += rewardContribution
      }
    }

    if (completeEntries.length > 0) {
      const loot = await applyLoot(env, player.id, completeEntries, now)
      inventoryDelta.push(...loot.inventoryDelta)
      moneyDelta += loot.moneyDelta
      message += loot.text
    }

    message += ' [STATUS] Dungeon cleared.'
  }

  // Update State
  const newState = await env.DB.prepare(`
    UPDATE game_player_dungeon_state 
    SET sanity = ?, health = ?, current_room_description = ?, current_depth = ?, status = ?, last_action = ?, updated_at = ? 
    WHERE player_id = ? RETURNING *
  `).bind(
    newSanity,
    newHealth,
    newDesc,
    nextDepth,
    completed ? 'COMPLETED' : 'ACTIVE',
    action,
    now,
    player.id
  ).first<DungeonState>();

  return gameActionResponse({
    success: true,
    message,
    event: message,
    delta: {
      sanity: sanityChange,
      health: healthChange,
      depth: depthDelta,
      inventory: inventoryDelta,
      money: moneyDelta,
      contribution: contributionDelta,
      dungeon: { status: newState?.status || (completed ? 'COMPLETED' : 'ACTIVE') }
    },
    new_unlocks: newUnlocks,
    state: newState
  });
}

async function generateRoomDescription(env: Env, dungeonName: string, depth: number, type: string): Promise<string> {
  const apiKey = env.QWEN_API_KEY;
  if (!apiKey) return "You enter a dark, corrupted data sector. Static fills the air.";

  try {
    const prompt = `
    Generate a short, atmospheric description (2 sentences max) for a room in a Cyberpunk Digital Dungeon named "${dungeonName}".
    Depth: ${depth}.
    Type: ${type} (e.g., Entrance, Corridor, Server Room, Boss Chamber).
    Atmosphere: Glitchy, ominous, technological decay.
    `;
    const config = createConfigReader(env)
    const cfg = await config.getMany(['game.ai.timeout_ms', 'game.ai.model'])
    const timeoutMs = Math.floor(config.number(cfg['game.ai.timeout_ms'], 8000, 1000, 60000))
    const model = cfg['game.ai.model'] || 'qwen-turbo'

    const result = await qwenChatCompletion(
      env,
      model,
      [{ role: 'user', content: prompt }],
      timeoutMs,
      null
    )
    return result.ok ? (result.content || "Static noise...") : "Connection to reality unstable..."
  } catch (e) {
    return "Connection to reality unstable...";
  }
}

async function resolveDungeonAction(env: Env, state: DungeonState, action: string, dungeonName: string = "Unknown", dungeonDesc: string = "", difficulty: number = 1): Promise<{ narrative: string, nextRoomDesc?: string, sanityDelta: number, healthDelta: number }> {
  const apiKey = env.QWEN_API_KEY;
  // Fallback
  if (!apiKey) return { narrative: "You move forward.", sanityDelta: -5, healthDelta: 0 };

  try {
    const prompt = `
    You are a Dungeon Master for a Text MUD RPG.
    Dungeon: "${dungeonName}" (Difficulty: ${difficulty}/5)
    Context: ${dungeonDesc}
    Current Room: "${state.current_room_description}"
    Player Stats: Sanity ${state.sanity}, Health ${state.health}.
    Player Action: "${action}" (MOVE, SEARCH, ATTACK).
    
    Output a JSON object:
    {
      "narrative": "What happens (1 sentence).",
      "nextRoomDesc": "Description of new room if action is MOVE, else null.",
      "sanityDelta": -5 to 5 (integer),
      "healthDelta": -10 to 0 (integer)
    }
    `;
    const config = createConfigReader(env)
    const cfg = await config.getMany(['game.ai.timeout_ms', 'game.ai.model'])
    const timeoutMs = Math.floor(config.number(cfg['game.ai.timeout_ms'], 8000, 1000, 60000))
    const model = cfg['game.ai.model'] || 'qwen-turbo'

    const result = await qwenChatCompletion(
      env,
      model,
      [{ role: 'user', content: prompt }],
      timeoutMs,
      { type: 'json_object' }
    )
    if (!result.ok) return { narrative: "Action processed.", sanityDelta: -5, healthDelta: 0 };
    const content = result.content || ''
    return JSON.parse(content);
  } catch (e) {
    return { narrative: "Action processed.", sanityDelta: -5, healthDelta: 0 };
  }
}

type QwenMessage = { role: 'system' | 'user' | 'assistant'; content: string }
type QwenResponseFormat = { type: 'json_object' } | null

async function qwenChatCompletion(
  env: Env,
  model: string,
  messages: QwenMessage[],
  timeoutMs: number,
  responseFormat: QwenResponseFormat
): Promise<{ ok: boolean; content?: string; status?: number }> {
  const apiKey = env.QWEN_API_KEY;
  if (!apiKey) return { ok: false, status: 503 }
  const controller = new AbortController()
  const t = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const resp = await fetch('https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model,
        messages,
        ...(responseFormat ? { response_format: responseFormat } : {})
      }),
      signal: controller.signal
    })
    if (!resp.ok) return { ok: false, status: resp.status }
    const data = await resp.json() as any
    const content = data?.choices?.[0]?.message?.content
    return { ok: true, content: typeof content === 'string' ? content : '' }
  } catch {
    return { ok: false }
  } finally {
    clearTimeout(t)
  }
}

function clampText(input: string, maxLen: number): string {
  const s = (input || '').toString()
  if (s.length <= maxLen) return s
  return s.slice(0, maxLen)
}

async function checkNpcRateLimit(env: Env, playerId: number, nowSec: number): Promise<{ ok: boolean; retryAfterSec?: number }> {
  const config = createConfigReader(env)
  const cfg = await config.getMany([
    'game.ai.npc.rate_limit_window_seconds',
    'game.ai.npc.rate_limit_max'
  ])
  const windowSec = Math.floor(config.number(cfg['game.ai.npc.rate_limit_window_seconds'], 30, 1, 3600))
  const maxReq = Math.floor(config.number(cfg['game.ai.npc.rate_limit_max'], 6, 1, 1000))

  if (!env.KV) return { ok: true }
  const key = `ratelimit:npc:${playerId}`
  const raw = await env.KV.get(key)
  let windowStart = nowSec
  let count = 0
  try {
    const o = raw ? JSON.parse(raw) : null
    if (o && typeof o === 'object') {
      windowStart = Number(o.windowStart) || nowSec
      count = Number(o.count) || 0
    }
  } catch {}

  if (nowSec - windowStart >= windowSec) {
    windowStart = nowSec
    count = 0
  }
  if (count >= maxReq) {
    const retryAfter = Math.max(1, (windowStart + windowSec) - nowSec)
    return { ok: false, retryAfterSec: retryAfter }
  }

  await env.KV.put(key, JSON.stringify({ windowStart, count: count + 1 }), { expirationTtl: windowSec * 2 })
  return { ok: true }
}

async function getNpcSummary(env: Env, playerId: number, npcId: string): Promise<{ summary: string; interactionCount: number } | null> {
  if (!env.DB) return null
  const row = await env.DB.prepare(
    'SELECT summary, interaction_count FROM game_npc_memory_summaries WHERE player_id = ? AND npc_id = ?'
  ).bind(playerId, npcId).first<{ summary: string; interaction_count: number }>()
  if (!row) return null
  return { summary: row.summary || '', interactionCount: row.interaction_count || 0 }
}

async function incrementNpcSummaryCount(env: Env, playerId: number, npcId: string, now: number): Promise<number> {
  if (!env.DB) return 0
  const row = await env.DB.prepare(
    `INSERT INTO game_npc_memory_summaries (player_id, npc_id, summary, interaction_count, created_at, updated_at)
     VALUES (?, ?, '', 1, ?, ?)
     ON CONFLICT(player_id, npc_id) DO UPDATE SET
       interaction_count = interaction_count + 1,
       updated_at = excluded.updated_at
     RETURNING interaction_count`
  ).bind(playerId, npcId, now, now).first<{ interaction_count: number }>()
  return row?.interaction_count || 0
}

async function maybeUpdateNpcSummary(
  env: Env,
  npc: { systemPrompt: string },
  player: GamePlayer,
  npcId: string,
  memory: NpcInteraction[],
  now: number,
  interactionCount: number
): Promise<void> {
  if (!env.DB) return
  const config = createConfigReader(env)
  const cfg = await config.getMany([
    'game.ai.timeout_ms',
    'game.ai.model',
    'game.ai.npc.summary_every',
    'game.ai.npc.summary_max_chars'
  ])
  const every = Math.floor(config.number(cfg['game.ai.npc.summary_every'], 10, 1, 1000000))
  if (interactionCount % every !== 0) return

  const timeoutMs = Math.floor(config.number(cfg['game.ai.timeout_ms'], 8000, 1000, 60000))
  const model = cfg['game.ai.model'] || 'qwen-turbo'
  const maxChars = Math.floor(config.number(cfg['game.ai.npc.summary_max_chars'], 800, 100, 5000))

  const convo = memory.map(m => `Player: ${m.message}\nNPC: ${m.response}`).join('\n')
  const prompt = `
Summarize the relationship and key facts about the player and NPC in <= ${maxChars} characters.
Focus on stable preferences, promises, conflict points, and ongoing quests.
Return ONLY JSON: {"summary":"..."}

Player: ${player.name} at ${player.current_port_id}, ship MK-${player.ship_level}.
Conversation:
${convo}
`
  const result = await qwenChatCompletion(
    env,
    model,
    [{ role: 'system', content: npc.systemPrompt }, { role: 'user', content: prompt }],
    timeoutMs,
    { type: 'json_object' }
  )
  if (!result.ok) return
  try {
    const obj = JSON.parse(result.content || '{}') as any
    const summary = clampText((obj?.summary || '').toString(), maxChars)
    if (!summary) return
    await env.DB.prepare(
      'UPDATE game_npc_memory_summaries SET summary = ?, updated_at = ? WHERE player_id = ? AND npc_id = ?'
    ).bind(summary, now, player.id, npcId).run()
  } catch {}
}

interface NpcInteraction {
  message: string;
  response: string;
  created_at: number;
}

async function getNpcMemory(env: Env, playerId: number, npcId: string, limit: number = 5): Promise<NpcInteraction[]> {
  if (!env.DB) return [];
  const results = await env.DB.prepare(
    'SELECT message, response, created_at FROM game_npc_interactions WHERE player_id = ? AND npc_id = ? ORDER BY created_at DESC LIMIT ?'
  ).bind(playerId, npcId, limit).all<NpcInteraction>();
  return results.results.reverse();
}

async function saveNpcMemory(env: Env, playerId: number, npcId: string, message: string, response: string) {
  if (!env.DB) return;
  const now = Math.floor(Date.now() / 1000);
  const config = createConfigReader(env)
  const cfg = await config.getMany(['game.ai.npc.max_store_interactions'])
  const maxKeep = Math.floor(config.number(cfg['game.ai.npc.max_store_interactions'], 200, 0, 10000))

  const stmts: any[] = [
    env.DB.prepare(
      'INSERT INTO game_npc_interactions (player_id, npc_id, message, response, created_at) VALUES (?, ?, ?, ?, ?)'
    ).bind(playerId, npcId, message, response, now)
  ]
  if (maxKeep > 0) {
    stmts.push(
      env.DB.prepare(
        `DELETE FROM game_npc_interactions
         WHERE player_id = ? AND npc_id = ?
           AND id NOT IN (
             SELECT id FROM game_npc_interactions
             WHERE player_id = ? AND npc_id = ?
             ORDER BY created_at DESC
             LIMIT ?
           )`
      ).bind(playerId, npcId, playerId, npcId, maxKeep)
    )
  }
  await env.DB.batch(stmts)
}

async function handleInteract(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const body = await request.json() as { npcId: string; message: string };
  const npcId = (body.npcId || '').toString()
  const rawMessage = (body.message || '').toString()

  const npc = NPC_REGISTRY[npcId];
  if (!npc) return jsonResponse({ error: 'NPC not found' }, 404);

  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const now = Math.floor(Date.now() / 1000);
  const newUnlocks: GameLore[] = [];
  let event: string | null = null
  const config = createConfigReader(env)
  const cfg = await config.getMany([
    'game.ai.timeout_ms',
    'game.ai.model',
    'game.ai.npc.max_memory_messages',
    'game.ai.npc.max_prompt_chars',
    'game.ai.npc.max_response_chars'
  ])
  const timeoutMs = Math.floor(config.number(cfg['game.ai.timeout_ms'], 8000, 1000, 60000))
  const model = cfg['game.ai.model'] || 'qwen-turbo'
  const memoryLimit = Math.floor(config.number(cfg['game.ai.npc.max_memory_messages'], 5, 0, 50))
  const maxPromptChars = Math.floor(config.number(cfg['game.ai.npc.max_prompt_chars'], 3500, 500, 20000))
  const maxResponseChars = Math.floor(config.number(cfg['game.ai.npc.max_response_chars'], 800, 50, 5000))

  const rate = await checkNpcRateLimit(env, player.id, now)
  if (!rate.ok) {
    return jsonResponse({ error: `Rate limited. Retry after ${rate.retryAfterSec || 1}s` }, 429)
  }

  const message = clampText(rawMessage.trim(), 500)

  // --- LORE DROP CHECK (NPC_INTERACTION) ---
  // Simple logic: 30% chance every interaction
  let loreMsg = "";
  if (Math.random() < 0.3) {
      const loreResult = await checkLoreUnlock(env, player.id, 'NPC_INTERACTION', npcId);
      if (loreResult.unlocked) {
          loreMsg = `\n\n[SYSTEM] TRUST INCREASED. ARCHIVE DECRYPTED: ${loreResult.title}`;
          event = `TRUST INCREASED. ARCHIVE DECRYPTED: ${loreResult.title}`
          if (loreResult.id) {
            const lore = await fetchLoreById(env, loreResult.id, now)
            if (lore) newUnlocks.push(lore)
          }
      }
  }

  const memory = memoryLimit > 0 ? await getNpcMemory(env, player.id, npcId, memoryLimit) : []
  const memoryContext = memory.map(m => `Player: "${m.message}"\nNPC: "${m.response}"`).join('\n');
  const summaryRow = await getNpcSummary(env, player.id, npcId)
  const summary = summaryRow?.summary || ''

  const context = clampText(`
Player Profile:
- Name: ${player.name}
- Money: ${player.money} G
- Current Port: ${player.current_port_id}
- Ship Level: ${player.ship_level}

Memory Summary:
${summary || '(none)'}

Recent Conversation History:
${memoryContext}

Player's Message: "${message}"

Respond as the NPC.
Output ONLY JSON:
{
  "text": "Your response text here",
  "action": "optional_action_id_or_null",
  "mood": "neutral|happy|angry"
}
`, maxPromptChars)

  const result = await qwenChatCompletion(
    env,
    model,
    [
      { role: 'system', content: npc.systemPrompt },
      { role: 'user', content: context }
    ],
    timeoutMs,
    { type: 'json_object' }
  )

  function fallbackText(): string {
    const base = npc.greeting || `...`
    return clampText(base, maxResponseChars)
  }

  let text = ''
  let action: string | null = null
  let mood: 'neutral' | 'happy' | 'angry' = 'neutral'
  if (result.ok) {
    try {
      const parsed = JSON.parse(result.content || '{}') as any
      text = clampText((parsed?.text || '').toString(), maxResponseChars)
      const a = parsed?.action
      action = (typeof a === 'string' && a.trim().length > 0) ? a.trim() : null
      const m = (parsed?.mood || '').toString()
      mood = (m === 'happy' || m === 'angry' || m === 'neutral') ? m : 'neutral'
      if (!text) text = fallbackText()
    } catch {
      text = clampText(result.content || '', maxResponseChars) || fallbackText()
    }
  } else {
    text = fallbackText()
  }

  if (loreMsg) text += loreMsg;

  await saveNpcMemory(env, player.id, npcId, message, text);
  const interactionCount = await incrementNpcSummaryCount(env, player.id, npcId, now)
  await maybeUpdateNpcSummary(env, npc, player, npcId, [...memory, { message, response: text, created_at: now }], now, interactionCount)

  return gameActionResponse({ 
    success: true,
    message: "Interaction complete",
    npcId,
    text,
    action,
    mood,
    event,
    delta: {},
    new_unlocks: newUnlocks
  });
}

async function handleGetPlayerStatus(env: Env, userId: string, walletAddress: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  let player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();

  if (!player) {
    // Initialize new player
    const now = Math.floor(Date.now() / 1000);
    try {
      const result = await env.DB.prepare(
        `INSERT INTO game_players (user_id, name, money, current_port_id, ship_level, cargo_capacity, q, r, created_at, updated_at)
         VALUES (?, ?, 1000, 'port_amsterdam', 1, 100, 0, 0, ?, ?) RETURNING *`
      ).bind(userId, `Captain ${walletAddress.slice(0, 6)}`, now, now).first<GamePlayer>();
      player = result;
    } catch {
      const result = await env.DB.prepare(
        `INSERT INTO game_players (user_id, name, money, current_port_id, ship_level, cargo_capacity, created_at, updated_at)
         VALUES (?, ?, 1000, 'port_amsterdam', 1, 100, ?, ?) RETURNING *`
      ).bind(userId, `Captain ${walletAddress.slice(0, 6)}`, now, now).first<GamePlayer>();
      player = result;
    }
  }

  return jsonResponse({ player });
}

async function handleGetPorts(env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  const ports = await env.DB.prepare('SELECT * FROM game_ports').all<GamePort>();
  return jsonResponse({ ports: ports.results });
}

async function handleGetDungeons(env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  const config = createConfigReader(env)
  const cfg = await config.getMany(['game.dungeon.defs'])
  const defs = parseDungeonDefs(cfg['game.dungeon.defs'])

  const dungeons = await env.DB.prepare('SELECT * FROM game_dungeons').all<DungeonDef>();
  const merged = new Map<string, DungeonDef>()

  for (const row of dungeons.results) merged.set(row.id, row)
  for (const [id, def] of defs.entries()) merged.set(id, def)

  return jsonResponse({ dungeons: Array.from(merged.values()) });
}

async function handleGetMarket(env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  // Get player's current port
  const player = await env.DB.prepare('SELECT current_port_id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const portId = player.current_port_id;
  const config = createConfigReader(env)

  // Refresh market prices if needed (simple logic: random fluctuation every request for now, or check timestamp)
  // For MVP, we'll just fetch and if empty, populate.
  // Ideally, we should have a scheduled task or a check here to update prices periodically.
  
  // Check if market exists for this port
  const marketItems = await env.DB.prepare(
    `SELECT m.*, g.name, g.description, g.base_price 
     FROM game_market m 
     JOIN game_goods g ON m.good_id = g.id 
     WHERE m.port_id = ?`
  ).bind(portId).all<GameMarketItem>();

  const now = Math.floor(Date.now() / 1000);
  let event: string | null = null
  let marketUpdatedAt: number | null = null

  const cfg = await config.getMany([
    'game.market.refresh_ttl_seconds',
    'game.market.price_variation_ratio',
    'game.market.stock_min',
    'game.market.stock_max',
    'game.market.init_price_variation_ratio',
    'game.market.init_stock_min',
    'game.market.init_stock_max',
    'game.market.port_overrides'
  ])

  const portOverrides = config.json<Record<string, Record<string, unknown>>>(
    cfg['game.market.port_overrides'],
    {}
  )
  const portCfg = (portOverrides?.[portId] as Record<string, unknown> | undefined) ?? {}

  function portOverride(key: string): string | null {
    const v = portCfg[key]
    if (v === undefined || v === null) return null
    if (typeof v === 'string') return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
    return null
  }

  const refreshTtlSeconds = config.number(
    portOverride('refresh_ttl_seconds') ?? cfg['game.market.refresh_ttl_seconds'],
    300,
    10,
    86400
  )
  const priceVariationRatio = config.number(
    portOverride('price_variation_ratio') ?? cfg['game.market.price_variation_ratio'],
    0.2,
    0,
    0.8
  )
  const stockMin = Math.floor(config.number(
    portOverride('stock_min') ?? cfg['game.market.stock_min'],
    5,
    0,
    100000
  ))
  const stockMax = Math.floor(config.number(
    portOverride('stock_max') ?? cfg['game.market.stock_max'],
    125,
    stockMin,
    100000
  ))
  const initPriceVariationRatio = config.number(
    portOverride('init_price_variation_ratio') ?? cfg['game.market.init_price_variation_ratio'],
    priceVariationRatio,
    0,
    0.8
  )
  const initStockMin = Math.floor(config.number(
    portOverride('init_stock_min') ?? cfg['game.market.init_stock_min'],
    10,
    0,
    100000
  ))
  const initStockMax = Math.floor(config.number(
    portOverride('init_stock_max') ?? cfg['game.market.init_stock_max'],
    Math.max(initStockMin, 110),
    initStockMin,
    100000
  ))

  function randomInt(min: number, max: number): number {
    if (max <= min) return min
    return Math.floor(Math.random() * (max - min + 1)) + min
  }

  if (marketItems.results.length === 0) {
    // Populate market for this port
    // Get all goods
    const goods = await env.DB.prepare('SELECT * FROM game_goods').all<GameGood>();
    
    // Insert initial market data
    const stmts = goods.results.map(good => {
      // Random price variation +/- 20%
      const vol = Math.max(0, Math.min(2, Number(good.volatility) || 1))
      const effectiveRatio = Math.max(0, Math.min(0.8, initPriceVariationRatio * vol))
      const variation = ((Math.random() * 2) - 1) * effectiveRatio;
      const price = Math.max(1, Math.floor(good.base_price * (1 + variation)));
      const stock = randomInt(initStockMin, initStockMax);
      
      return env.DB!.prepare(
        `INSERT INTO game_market (port_id, good_id, price, stock, updated_at) VALUES (?, ?, ?, ?, ?)`
      ).bind(portId, good.id, price, stock, now);
    });
    
    await env.DB.batch(stmts);
    
    // Fetch again
    const newItems = await env.DB.prepare(
      `SELECT m.*, g.name, g.description, g.base_price 
       FROM game_market m 
       JOIN game_goods g ON m.good_id = g.id 
       WHERE m.port_id = ?`
    ).bind(portId).all<GameMarketItem>();
    
    event = 'Market initialized.'
    marketUpdatedAt = now
    return jsonResponse({ market: newItems.results, portId, updatedAt: marketUpdatedAt, event });
  }
  
  const oldestUpdatedAt = marketItems.results.reduce((min, it) => Math.min(min, it.updated_at || now), now)
  if (now - oldestUpdatedAt > refreshTtlSeconds) {
    if (env.KV) {
      const lockKey = `lock:market_refresh:${portId}`
      const existing = await env.KV.get(lockKey)
      if (existing) {
        event = 'Market refresh in progress.'
        marketUpdatedAt = oldestUpdatedAt
        return jsonResponse({ market: marketItems.results, portId, updatedAt: marketUpdatedAt, event })
      }
      await env.KV.put(lockKey, String(now), { expirationTtl: 8 })
    }
    const goods = await env.DB.prepare('SELECT id, base_price, volatility FROM game_goods').all<{ id: string; base_price: number; volatility: number }>()
    const goodsById = new Map(goods.results.map(g => [g.id, g]))
    const stmts = marketItems.results.map(item => {
      const g = goodsById.get(item.good_id)
      const basePrice = g?.base_price ?? item.price
      const vol = Math.max(0, Math.min(2, Number(g?.volatility) || 1))
      const effectiveRatio = Math.max(0, Math.min(0.8, priceVariationRatio * vol))
      const variation = ((Math.random() * 2) - 1) * effectiveRatio
      const price = Math.max(1, Math.floor(basePrice * (1 + variation)))
      const stock = randomInt(stockMin, stockMax)
      return env.DB!.prepare('UPDATE game_market SET price = ?, stock = ?, updated_at = ? WHERE id = ?')
        .bind(price, stock, now, item.id)
    })
    await env.DB.batch(stmts)
    const refreshed = await env.DB.prepare(
      `SELECT m.*, g.name, g.description, g.base_price 
       FROM game_market m 
       JOIN game_goods g ON m.good_id = g.id 
       WHERE m.port_id = ?`
    ).bind(portId).all<GameMarketItem>()
    event = 'Market prices updated.'
    marketUpdatedAt = now
    if (env.KV) {
      await env.KV.delete(`lock:market_refresh:${portId}`)
    }
    return jsonResponse({ market: refreshed.results, portId, updatedAt: marketUpdatedAt, event })
  }
  
  marketUpdatedAt = now
  return jsonResponse({ market: marketItems.results, portId, updatedAt: marketUpdatedAt, event });
}

async function handleGetInventory(env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  
  const player = await env.DB.prepare('SELECT id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const inventory = await env.DB.prepare(
    `SELECT i.*, g.name, g.description 
     FROM game_inventory i 
     JOIN game_goods g ON i.good_id = g.id 
     WHERE i.player_id = ? AND i.quantity > 0`
  ).bind(player.id).all<GameInventoryItem>();

  return jsonResponse({ inventory: inventory.results });
}

async function handleBuyGoods(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const body = await request.json() as { goodId: string; quantity: number };
  const { goodId, quantity } = body;
  
  if (quantity <= 0) return jsonResponse({ error: 'Invalid quantity' }, 400);

  // Transaction start
  // 1. Get player
  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // 2. Get market item
  const marketItem = await env.DB.prepare(
    'SELECT * FROM game_market WHERE port_id = ? AND good_id = ?'
  ).bind(player.current_port_id, goodId).first<GameMarketItem>();

  if (!marketItem) return jsonResponse({ error: 'Item not available in this port' }, 400);
  if (marketItem.stock < quantity) return jsonResponse({ error: 'Not enough stock' }, 400);

  const config = createConfigReader(env)
  const econCfg = await config.getMany([
    'game.econ.trade_tax_rate',
    'game.econ.trade_tax_min',
    'game.econ.port_overrides'
  ])
  const portOverrides = config.json<Record<string, Record<string, unknown>>>(
    econCfg['game.econ.port_overrides'],
    {}
  )
  const portCfg = (portOverrides?.[player.current_port_id] as Record<string, unknown> | undefined) ?? {}
  function portOverride(key: string): string | null {
    const v = portCfg[key]
    if (v === undefined || v === null) return null
    if (typeof v === 'string') return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
    return null
  }
  const tradeTaxRate = config.number(portOverride('trade_tax_rate') ?? econCfg['game.econ.trade_tax_rate'], 0.02, 0, 0.5)
  const tradeTaxMin = Math.floor(config.number(portOverride('trade_tax_min') ?? econCfg['game.econ.trade_tax_min'], 1, 0, 1000000))

  const baseCost = marketItem.price * quantity;
  const tax = Math.max(tradeTaxMin, Math.ceil(baseCost * tradeTaxRate))
  const totalCost = baseCost + tax
  if (player.money < totalCost) return jsonResponse({ error: 'Not enough money' }, 400);

  // 3. Check cargo capacity
  const currentCargo = await env.DB.prepare(
    'SELECT SUM(quantity) as total FROM game_inventory WHERE player_id = ?'
  ).bind(player.id).first<{ total: number }>();
  
  const currentLoad = currentCargo?.total || 0;
  if (currentLoad + quantity > player.cargo_capacity) return jsonResponse({ error: 'Cargo full' }, 400);

  const now = Math.floor(Date.now() / 1000);
  
  await env.DB.prepare('BEGIN').run();
  try {
    const playerUpdate = await env.DB.prepare(
      'UPDATE game_players SET money = money - ?, updated_at = ? WHERE id = ? AND money >= ?'
    ).bind(totalCost, now, player.id, totalCost).run();
    if ((playerUpdate as any)?.meta?.changes !== 1) {
      await env.DB.prepare('ROLLBACK').run();
      return jsonResponse({ error: 'Not enough money' }, 400);
    }

    const marketUpdate = await env.DB.prepare(
      'UPDATE game_market SET stock = stock - ?, updated_at = ? WHERE id = ? AND stock >= ?'
    ).bind(quantity, now, marketItem.id, quantity).run();
    if ((marketUpdate as any)?.meta?.changes !== 1) {
      await env.DB.prepare('ROLLBACK').run();
      return jsonResponse({ error: 'Not enough stock' }, 400);
    }

    await env.DB.prepare(
      `INSERT INTO game_inventory (player_id, good_id, quantity, avg_cost, updated_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(player_id, good_id) DO UPDATE SET
         avg_cost = (((quantity * avg_cost) + ?) * 1.0) / (quantity + ?),
         quantity = quantity + ?,
         updated_at = ?`
    ).bind(
      player.id,
      goodId,
      quantity,
      (totalCost * 1.0) / quantity,
      now,
      totalCost,
      quantity,
      quantity,
      now
    ).run();

    await env.DB.prepare('COMMIT').run();
  } catch (e) {
    await env.DB.prepare('ROLLBACK').run();
    console.error(e);
    return jsonResponse({ error: 'Transaction failed' }, 500);
  }

  const event = `Bought ${quantity} ${goodId} (Tax: ${tax} G)`
  return gameActionResponse({
    success: true,
    message: event,
    event,
    delta: { money: -totalCost, tax, inventory: [{ good_id: goodId, delta: quantity }] },
    new_unlocks: []
  });
}

async function handleSellGoods(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const body = await request.json() as { goodId: string; quantity: number };
  const { goodId, quantity } = body;

  if (quantity <= 0) return jsonResponse({ error: 'Invalid quantity' }, 400);

  // 1. Get player
  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  // 2. Check inventory
  const inventoryItem = await env.DB.prepare(
    'SELECT * FROM game_inventory WHERE player_id = ? AND good_id = ?'
  ).bind(player.id, goodId).first<GameInventoryItem>();

  if (!inventoryItem || inventoryItem.quantity < quantity) return jsonResponse({ error: 'Not enough goods' }, 400);

  // 3. Get market price (you can sell even if stock is 0, stock increases)
  // Check if market exists for this port (should exist if we are in a valid port)
  const marketItem = await env.DB.prepare(
    'SELECT * FROM game_market WHERE port_id = ? AND good_id = ?'
  ).bind(player.current_port_id, goodId).first<GameMarketItem>();

  // If item is not traded in this port, maybe allow selling at lower price? Or not allow?
  // For now, assume only traded goods can be sold.
  if (!marketItem) return jsonResponse({ error: 'Cannot sell this item here' }, 400);

  const config = createConfigReader(env)
  const econCfg = await config.getMany([
    'game.econ.trade_tax_rate',
    'game.econ.trade_tax_min',
    'game.econ.sell_price_ratio',
    'game.econ.port_overrides'
  ])
  const portOverrides = config.json<Record<string, Record<string, unknown>>>(
    econCfg['game.econ.port_overrides'],
    {}
  )
  const portCfg = (portOverrides?.[player.current_port_id] as Record<string, unknown> | undefined) ?? {}
  function portOverride(key: string): string | null {
    const v = portCfg[key]
    if (v === undefined || v === null) return null
    if (typeof v === 'string') return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
    return null
  }
  const tradeTaxRate = config.number(portOverride('trade_tax_rate') ?? econCfg['game.econ.trade_tax_rate'], 0.02, 0, 0.5)
  const tradeTaxMin = Math.floor(config.number(portOverride('trade_tax_min') ?? econCfg['game.econ.trade_tax_min'], 1, 0, 1000000))
  const sellPriceRatio = config.number(portOverride('sell_price_ratio') ?? econCfg['game.econ.sell_price_ratio'], 0.9, 0.0, 1.0)

  const finalSellPrice = Math.floor(marketItem.price * sellPriceRatio);
  const grossRevenue = finalSellPrice * quantity;
  const tax = Math.max(tradeTaxMin, Math.ceil(grossRevenue * tradeTaxRate))
  const totalRevenue = Math.max(0, grossRevenue - tax)
  const now = Math.floor(Date.now() / 1000);

  await env.DB.prepare('BEGIN').run();
  try {
    const invUpdate = await env.DB.prepare(
      'UPDATE game_inventory SET quantity = quantity - ?, updated_at = ? WHERE id = ? AND quantity >= ?'
    ).bind(quantity, now, inventoryItem.id, quantity).run();
    if ((invUpdate as any)?.meta?.changes !== 1) {
      await env.DB.prepare('ROLLBACK').run();
      return jsonResponse({ error: 'Not enough goods' }, 400);
    }

    await env.DB.prepare(
      'DELETE FROM game_inventory WHERE id = ? AND quantity = 0'
    ).bind(inventoryItem.id).run();

    await env.DB.prepare('UPDATE game_players SET money = money + ?, updated_at = ? WHERE id = ?')
      .bind(totalRevenue, now, player.id).run();

    await env.DB.prepare('UPDATE game_market SET stock = stock + ?, updated_at = ? WHERE id = ?')
      .bind(quantity, now, marketItem.id).run();

    await env.DB.prepare('COMMIT').run();
  } catch (e) {
    await env.DB.prepare('ROLLBACK').run();
    console.error(e);
    return jsonResponse({ error: 'Transaction failed' }, 500);
  }
  
  // Calculate profit
  const profit = totalRevenue - (quantity * inventoryItem.avg_cost);

  const event = `Sold ${quantity} ${goodId} (Tax: ${tax} G)`
  return gameActionResponse({
    success: true,
    message: event,
    event,
    delta: { money: totalRevenue, tax, inventory: [{ good_id: goodId, delta: -quantity }] },
    new_unlocks: [],
    profit: Math.floor(profit),
    grossRevenue
  });
}

async function handleSail(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);

  const body = await request.json() as { targetPortId: string };
  const { targetPortId } = body;

  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  if (player.current_port_id === targetPortId) return jsonResponse({ error: 'Already at this port' }, 400);

  // Check if target port exists
  const targetPort = await env.DB.prepare('SELECT * FROM game_ports WHERE id = ?').bind(targetPortId).first<GamePort>();
  if (!targetPort) return jsonResponse({ error: 'Invalid port' }, 400);

  const config = createConfigReader(env)
  const econCfg = await config.getMany([
    'game.econ.travel_base_cost',
    'game.econ.travel_cost_per_distance',
    'game.econ.travel_cost_per_ship_level',
    'game.econ.port_overrides'
  ])
  const portOverrides = config.json<Record<string, Record<string, unknown>>>(
    econCfg['game.econ.port_overrides'],
    {}
  )
  const portCfg = (portOverrides?.[player.current_port_id] as Record<string, unknown> | undefined) ?? {}
  function portOverride(key: string): string | null {
    const v = portCfg[key]
    if (v === undefined || v === null) return null
    if (typeof v === 'string') return v
    if (typeof v === 'number' || typeof v === 'boolean') return String(v)
    return null
  }
  const travelBaseCost = config.number(portOverride('travel_base_cost') ?? econCfg['game.econ.travel_base_cost'], 20, 0, 1000000)
  const travelCostPerDistance = config.number(portOverride('travel_cost_per_distance') ?? econCfg['game.econ.travel_cost_per_distance'], 20, 0, 1000000)
  const travelCostPerShipLevel = config.number(portOverride('travel_cost_per_ship_level') ?? econCfg['game.econ.travel_cost_per_ship_level'], 0, 0, 1000000)

  const currentPort = await env.DB.prepare('SELECT * FROM game_ports WHERE id = ?').bind(player.current_port_id).first<GamePort>();
  function parseCoords(s: string | undefined): { x: number; y: number } | null {
    if (!s) return null
    const parts = s.split(',').map(p => p.trim())
    if (parts.length !== 2) return null
    const x = Number(parts[0])
    const y = Number(parts[1])
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null
    return { x, y }
  }
  const from = parseCoords(currentPort?.coordinates)
  const to = parseCoords(targetPort.coordinates)
  const dist = from && to ? Math.abs(from.x - to.x) + Math.abs(from.y - to.y) : 1
  const travelCost = Math.floor(travelBaseCost + (dist * travelCostPerDistance) + (Math.max(0, player.ship_level - 1) * travelCostPerShipLevel))
  if (player.money < travelCost) return jsonResponse({ error: 'Not enough money for supplies' }, 400);

  const now = Math.floor(Date.now() / 1000);

  const activeTravel = await env.DB.prepare(
    'SELECT * FROM game_player_travel_state WHERE player_id = ? AND status = "ACTIVE"'
  ).bind(player.id).first<GameTravelState>();
  if (activeTravel) return jsonResponse({ error: 'Already traveling' }, 400);

  const travelCfg = await config.getMany([
    'game.travel.base_duration_seconds',
    'game.travel.seconds_per_distance',
    'game.travel.seconds_per_ship_level',
    'game.travel.encounter_reward_chance',
    'game.travel.encounter_penalty_chance',
    'game.travel.encounter_reward_min',
    'game.travel.encounter_reward_max',
    'game.travel.encounter_penalty_min',
    'game.travel.encounter_penalty_max'
  ])

  const baseDuration = Math.floor(config.number(travelCfg['game.travel.base_duration_seconds'], 30, 5, 86400))
  const secondsPerDistance = Math.floor(config.number(travelCfg['game.travel.seconds_per_distance'], 20, 0, 86400))
  const secondsPerShipLevel = Math.floor(config.number(travelCfg['game.travel.seconds_per_ship_level'], 0, 0, 86400))
  const durationSeconds = baseDuration + (dist * secondsPerDistance) + (Math.max(0, player.ship_level - 1) * secondsPerShipLevel)
  const arriveAt = now + Math.max(5, durationSeconds)

  const rewardChance = config.number(travelCfg['game.travel.encounter_reward_chance'], 0.15, 0, 1)
  const penaltyChance = config.number(travelCfg['game.travel.encounter_penalty_chance'], 0.1, 0, 1)
  const rewardMin = Math.floor(config.number(travelCfg['game.travel.encounter_reward_min'], 50, 0, 100000000))
  const rewardMax = Math.floor(config.number(travelCfg['game.travel.encounter_reward_max'], 150, rewardMin, 100000000))
  const penaltyMin = Math.floor(config.number(travelCfg['game.travel.encounter_penalty_min'], 20, 0, 100000000))
  const penaltyMax = Math.floor(config.number(travelCfg['game.travel.encounter_penalty_max'], 80, penaltyMin, 100000000))

  const roll = Math.random()
  let encounterEvent: string | null = null
  let encounterMoneyDelta = 0
  if (roll < penaltyChance) {
    const loss = Math.floor(Math.random() * (penaltyMax - penaltyMin + 1)) + penaltyMin
    encounterEvent = `Encountered a pirate toll gate. (-${loss} G)`;
    encounterMoneyDelta = -loss
  } else if (roll > (1 - rewardChance)) {
    const gain = Math.floor(Math.random() * (rewardMax - rewardMin + 1)) + rewardMin
    encounterEvent = `Salvaged a drifting cache. (+${gain} G)`
    encounterMoneyDelta = gain
  }

  await env.DB.prepare('BEGIN').run();
  try {
    const playerUpdate = await env.DB.prepare(
      'UPDATE game_players SET money = money - ?, updated_at = ? WHERE id = ? AND money >= ?'
    ).bind(travelCost, now, player.id, travelCost).run();
    if ((playerUpdate as any)?.meta?.changes !== 1) {
      await env.DB.prepare('ROLLBACK').run();
      return jsonResponse({ error: 'Not enough money for supplies' }, 400);
    }

    const travel: GameTravelState = {
      player_id: player.id,
      from_port_id: player.current_port_id,
      to_port_id: targetPortId,
      depart_at: now,
      arrive_at: arriveAt,
      status: 'ACTIVE',
      travel_cost: travelCost,
      encounter_event: encounterEvent,
      encounter_money_delta: encounterMoneyDelta,
      created_at: now,
      updated_at: now
    }

    await env.DB.prepare(
      `INSERT INTO game_player_travel_state
        (player_id, from_port_id, to_port_id, depart_at, arrive_at, status, travel_cost, encounter_event, encounter_money_delta, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
       ON CONFLICT(player_id) DO UPDATE SET
         from_port_id = excluded.from_port_id,
         to_port_id = excluded.to_port_id,
         depart_at = excluded.depart_at,
         arrive_at = excluded.arrive_at,
         status = 'ACTIVE',
         travel_cost = excluded.travel_cost,
         encounter_event = excluded.encounter_event,
         encounter_money_delta = excluded.encounter_money_delta,
         updated_at = excluded.updated_at`
    ).bind(
      travel.player_id,
      travel.from_port_id,
      travel.to_port_id,
      travel.depart_at,
      travel.arrive_at,
      travel.travel_cost,
      travel.encounter_event,
      travel.encounter_money_delta,
      travel.created_at,
      travel.updated_at
    ).run();

    await env.DB.prepare('COMMIT').run();

    const event = `Departed for ${targetPort.name}. ETA: ${new Date(arriveAt * 1000).toISOString()}`
    return gameActionResponse({ 
      success: true, 
      message: event,
      event,
      delta: { money: -travelCost, travel: { to_port_id: targetPortId, arrive_at: arriveAt, depart_at: now } },
      new_unlocks: [],
      travel
    });
  } catch (e) {
    await env.DB.prepare('ROLLBACK').run();
    console.error(e);
    return jsonResponse({ error: 'Transaction failed' }, 500);
  }
}

async function handleTravelStatus(env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  const player = await env.DB.prepare('SELECT id FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ travel: null });
  const travel = await env.DB.prepare(
    'SELECT * FROM game_player_travel_state WHERE player_id = ? AND status = "ACTIVE"'
  ).bind(player.id).first<GameTravelState>();
  return jsonResponse({ travel: travel || null });
}

async function handleTravelClaim(request: Request, env: Env, userId: string): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database error' }, 500);
  await request.json().catch(() => ({}));

  const player = await env.DB.prepare('SELECT * FROM game_players WHERE user_id = ?').bind(userId).first<GamePlayer>();
  if (!player) return jsonResponse({ error: 'Player not found' }, 404);

  const travel = await env.DB.prepare(
    'SELECT * FROM game_player_travel_state WHERE player_id = ? AND status = "ACTIVE"'
  ).bind(player.id).first<GameTravelState>();
  if (!travel) return jsonResponse({ error: 'No active travel' }, 400);

  const now = Math.floor(Date.now() / 1000);
  if (now < travel.arrive_at) {
    return jsonResponse({ error: 'Not arrived yet', remainingSeconds: travel.arrive_at - now }, 400);
  }

  const port = await env.DB.prepare('SELECT * FROM game_ports WHERE id = ?').bind(travel.to_port_id).first<GamePort>();
  if (!port) return jsonResponse({ error: 'Invalid port' }, 400);

  await env.DB.prepare('BEGIN').run();
  try {
    await env.DB.prepare(
      'UPDATE game_player_travel_state SET status = "ARRIVED", updated_at = ? WHERE player_id = ?'
    ).bind(now, player.id).run();

    await env.DB.prepare(
      `UPDATE game_players
       SET current_port_id = ?,
           money = CASE WHEN money + ? < 0 THEN 0 ELSE money + ? END,
           updated_at = ?
       WHERE id = ?`
    ).bind(travel.to_port_id, travel.encounter_money_delta, travel.encounter_money_delta, now, player.id).run();

    await env.DB.prepare('COMMIT').run();

    const event = travel.encounter_event
      ? `Arrived at ${port.name}. ${travel.encounter_event}`
      : `Arrived at ${port.name}.`

    return gameActionResponse({
      success: true,
      message: event,
      event,
      delta: { current_port_id: travel.to_port_id, money: travel.encounter_money_delta },
      new_unlocks: [],
      travel: { ...travel, status: 'ARRIVED', updated_at: now }
    });
  } catch (e) {
    await env.DB.prepare('ROLLBACK').run();
    console.error(e);
    return jsonResponse({ error: 'Transaction failed' }, 500);
  }
}
