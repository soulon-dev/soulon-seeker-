import { Env, jsonResponse } from '../index';
import { verifyGenesisCollectionWithDas, verifySeekerGenesisToken, verifySeekerGenesisTokenWithDebug, verifySolTransfer } from '../utils/solana';
import { getSolanaRpcUrl } from '../utils/solana-rpc';

export async function handleGenesisRoutes(request: Request, env: Env, path: string): Promise<Response | null> {
  const normalizedPath = path.replace(/\/+$/, '');

  // Check redemption status
  if (normalizedPath === '/api/v1/subscription/genesis/status' && request.method === 'GET') {
    return checkGenesisStatus(request, env);
  }

  if (normalizedPath === '/api/v1/subscription/genesis/eligibility' && request.method === 'GET') {
    return checkGenesisEligibility(request, env);
  }

  // Redeem Genesis Token trial
  if (normalizedPath === '/api/v1/subscription/genesis/redeem' && request.method === 'POST') {
    return redeemGenesisTrial(request, env);
  }

  return null;
}

async function ensureGenesisTables(env: Env): Promise<void> {
  if (!env.DB) return;

  await env.DB.prepare(
    `CREATE TABLE IF NOT EXISTS genesis_redemptions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      wallet_address TEXT NOT NULL,
      redeemed_at INTEGER NOT NULL,
      ip_address TEXT,
      device_id TEXT,
      transaction_signature TEXT,
      created_at INTEGER DEFAULT (unixepoch())
    )`
  ).run();

  await env.DB.prepare(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_genesis_redemptions_wallet ON genesis_redemptions(wallet_address)`
  ).run();

  await env.DB.prepare(
    `CREATE UNIQUE INDEX IF NOT EXISTS idx_genesis_redemptions_signature ON genesis_redemptions(transaction_signature)`
  ).run();
}

async function checkGenesisEligibility(request: Request, env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database not available' }, 500);

  const url = new URL(request.url);
  const wallet = url.searchParams.get('wallet');
  const debug = url.searchParams.get('debug') === '1';

  if (!wallet) return jsonResponse({ error: 'Wallet address required' }, 400);

  try {
    await ensureGenesisTables(env);

    const redeemedRow = await env.DB.prepare(
      'SELECT wallet_address FROM genesis_redemptions WHERE wallet_address = ?'
    ).bind(wallet).first();

    const sagaCollection = (env.GENESIS_TOKEN_COLLECTION || '').trim() || '46pcSL5gmjBrPqGKFaLbbCmR6iVuLJbnQy13hAe7s6CC';
    const rpcUrl = getSolanaRpcUrl(env);
    const rpcConfigured = true;
    let rpcHost: string | null = null;
    try {
      rpcHost = new URL(rpcUrl).host;
    } catch (_) {
      rpcHost = null;
    }
    const sagaDas = await verifyGenesisCollectionWithDas({
      walletAddress: wallet,
      collectionAddress: sagaCollection,
      rpcUrl,
    });
    const seeker = debug
      ? await verifySeekerGenesisTokenWithDebug({ walletAddress: wallet, rpcUrl })
      : await verifySeekerGenesisToken({ walletAddress: wallet, rpcUrl });
    if (!seeker.supported && !sagaDas.hasToken) {
      return jsonResponse(
        {
          wallet,
          redeemed: !!redeemedRow,
          error: 'Seeker Genesis check unavailable',
          seekerSupported: false,
          hasSagaGenesisToken: sagaDas.hasToken,
          dasSupported: sagaDas.supported,
          dasTotal: sagaDas.total ?? 0,
          collection: sagaCollection,
          rpcConfigured,
          rpcHost,
          ...(debug ? { debug: (seeker as any).debug || null } : {}),
        },
        503
      );
    }
    const hasGenesisToken = seeker.hasToken || sagaDas.hasToken;
    const dasSupported = sagaDas.supported || seeker.supported;

    return jsonResponse({
      wallet,
      redeemed: !!redeemedRow,
      hasGenesisToken,
      hasSeekerGenesisToken: seeker.hasToken,
      seekerSupported: seeker.supported,
      hasSagaGenesisToken: sagaDas.hasToken,
      dasSupported,
      dasTotal: sagaDas.total ?? 0,
      collection: sagaCollection,
      rpcConfigured,
      rpcHost,
      ...(debug ? { debug: (seeker as any).debug || null } : {}),
    });
  } catch (e) {
    console.error('Genesis eligibility check error:', e);
    return jsonResponse({ error: 'Database error', details: (e as Error).message }, 500);
  }
}

async function checkGenesisStatus(request: Request, env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database not available' }, 500);
  
  const url = new URL(request.url);
  const wallet = url.searchParams.get('wallet');

  if (!wallet) return jsonResponse({ error: 'Wallet address required' }, 400);

  try {
    await ensureGenesisTables(env);

    const result = await env.DB.prepare(
      'SELECT * FROM genesis_redemptions WHERE wallet_address = ?'
    ).bind(wallet).first();

    return jsonResponse({
      wallet,
      redeemed: !!result,
      redeemedAt: result ? result.redeemed_at : null
    });
  } catch (e) {
    console.error('Genesis status check error:', e);
    return jsonResponse({ error: 'Database error', details: (e as Error).message }, 500);
  }
}

async function redeemGenesisTrial(request: Request, env: Env): Promise<Response> {
  if (!env.DB) return jsonResponse({ error: 'Database not available' }, 500);

  try {
    await ensureGenesisTables(env);

    const body = await request.json() as { wallet: string; signature?: string; deviceId?: string };
    if (!body.wallet) return jsonResponse({ error: 'Wallet address required' }, 400);
    if (!body.signature) return jsonResponse({ error: 'Transaction signature required' }, 400);

    // Check if already redeemed
    const existing = await env.DB.prepare(
      'SELECT wallet_address FROM genesis_redemptions WHERE wallet_address = ?'
    ).bind(body.wallet).first();

    if (existing) {
      return jsonResponse({ 
        success: false, 
        error: 'already_redeemed', 
        message: 'This wallet has already redeemed the Genesis Token trial.',
        redeemed: true 
      }, 400);
    }

    // Prevent signature reuse
    const sigExisting = await env.DB.prepare(
      'SELECT wallet_address FROM genesis_redemptions WHERE transaction_signature = ?'
    ).bind(body.signature).first();
    if (sigExisting) {
      return jsonResponse({
        success: false,
        error: 'signature_already_used',
        message: 'This transaction signature has already been used.',
      }, 400);
    }

    const rpcUrl = getSolanaRpcUrl(env);

    const sagaCollection = (env.GENESIS_TOKEN_COLLECTION || '').trim() || '46pcSL5gmjBrPqGKFaLbbCmR6iVuLJbnQy13hAe7s6CC';
    const seeker = await verifySeekerGenesisToken({ walletAddress: body.wallet, rpcUrl });
    const sagaDas = await verifyGenesisCollectionWithDas({
      walletAddress: body.wallet,
      collectionAddress: sagaCollection,
      rpcUrl,
    });

    if (!seeker.hasToken && !sagaDas.hasToken) {
      return jsonResponse({
        success: false,
        error: 'no_genesis_token',
        message: 'Wallet does not hold a supported Genesis Token. If you are a Saga Genesis Token holder, the backend RPC must support DAS (e.g. Helius searchAssets).',
        collection: sagaCollection,
        seekerSupported: seeker.supported,
        dasSupported: sagaDas.supported,
      }, 403);
    }

    // Resolve fee recipient wallet
    let recipient: string | null = null;
    try {
      const walletResult = await env.DB.prepare(
        `SELECT address FROM wallet_addresses 
         WHERE type = 'recipient' AND is_active = 1 
         ORDER BY created_at DESC LIMIT 1`
      ).first();
      if (walletResult && (walletResult as any).address) {
        recipient = (walletResult as any).address as string;
      }
    } catch (e) {
      recipient = null;
    }

    if (!recipient) {
      return jsonResponse({ error: 'Recipient wallet not configured' }, 500);
    }

    // Verify 0.05 SOL fee payment
    const feeLamports = 0.05 * 1_000_000_000;
    const feeOk = await verifySolTransfer({
      signature: body.signature,
      from: body.wallet,
      to: recipient,
      minLamports: feeLamports,
      rpcUrl,
    });
    if (!feeOk) {
      return jsonResponse({
        success: false,
        error: 'invalid_fee_payment',
        message: 'Fee payment not found or insufficient.',
      }, 400);
    }

    // Insert record
    const now = Math.floor(Date.now() / 1000);
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';

    await env.DB.prepare(
      'INSERT INTO genesis_redemptions (wallet_address, redeemed_at, ip_address, device_id, transaction_signature) VALUES (?, ?, ?, ?, ?)'
    ).bind(
      body.wallet, 
      now,
      ip,
      body.deviceId || null, 
      body.signature || null
    ).run();

    return jsonResponse({ 
      success: true, 
      redeemed: true,
      redeemedAt: now,
      trialDays: 7
    });
  } catch (e) {
    console.error('Genesis redemption error:', e);
    return jsonResponse({ error: 'Database error', details: (e as Error).message }, 500);
  }
}
