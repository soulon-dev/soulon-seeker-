/**
 * Solana 工具函数
 */

import { Connection, PublicKey } from '@solana/web3.js';
import { TOKEN_2022_PROGRAM_ID } from '@solana/spl-token';
import bs58 from 'bs58';

/**
 * 验证 Genesis Token 归属权
 */
export async function verifyGenesisToken(
  walletAddress: string,
  tokenMint: string,
  collectionMint: string,
  rpcUrl: string
): Promise<boolean> {
  try {
    const tokenPrograms = [
      'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA',
      'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb',
    ];

    for (const programId of tokenPrograms) {
      const response = await fetch(rpcUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          id: 1,
          method: 'getTokenAccountsByOwner',
          params: [
            walletAddress,
            { programId },
            { encoding: 'jsonParsed' },
          ],
        }),
      });

      const data = (await response.json()) as any;
      if (data.error) {
        console.error('RPC error:', data.error);
        continue;
      }

      const accounts = data.result?.value || [];
      for (const account of accounts) {
        const info = account.account?.data?.parsed?.info;
        if (info?.mint === tokenMint && parseInt(info.tokenAmount?.amount || '0') > 0) {
          // TODO: 验证 Token 是否属于 Genesis Collection
          // 需要查询 Token 的元数据（collectionMint）
          return true;
        }
      }
    }

    return false;
    
  } catch (error) {
    console.error('Verify genesis token error:', error);
    return false;
  }
}

/**
 * 获取 Sovereign Score
 */
export async function getSovereignScore(
  walletAddress: string,
  rpcUrl: string
): Promise<number> {
  try {
    // Sovereign Program PDA
    // 这里需要替换为实际的 Sovereign Program 地址
    const sovereignProgram = 'SovrnXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX';
    
    // 派生 PDA
    // const [pda] = PublicKey.findProgramAddressSync(
    //   [Buffer.from('sovereign'), new PublicKey(walletAddress).toBuffer()],
    //   new PublicKey(sovereignProgram)
    // );
    
    // 简化：返回模拟分数
    // 生产环境应该查询链上数据
    
    // 基于钱包地址生成伪随机分数（用于演示）
    const hash = await hashString(walletAddress);
    const score = hash % 1000; // 0-999 之间的分数
    
    return score;
    
  } catch (error) {
    console.error('Get sovereign score error:', error);
    return 0;
  }
}

/**
 * 获取质押状态
 */
export async function getStakingStatus(
  walletAddress: string,
  rpcUrl: string
): Promise<{
  amount: number;
  validatorAddress: string | null;
  activationEpoch: number | null;
}> {
  try {
    // 查询 Stake 账户
    const response = await fetch(rpcUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'getProgramAccounts',
        params: [
          'Stake11111111111111111111111111111111111111',
          {
            encoding: 'jsonParsed',
            filters: [
              {
                memcmp: {
                  offset: 12, // staker authority offset
                  bytes: walletAddress,
                },
              },
            ],
          },
        ],
      }),
    });
    
    const data = await response.json() as any;
    
    if (data.error) {
      console.error('RPC error:', data.error);
      return { amount: 0, validatorAddress: null, activationEpoch: null };
    }
    
    const accounts = data.result || [];
    
    if (accounts.length === 0) {
      return { amount: 0, validatorAddress: null, activationEpoch: null };
    }
    
    // 找到最大的质押账户
    let maxStake = { amount: 0, validatorAddress: null as string | null, activationEpoch: null as number | null };
    
    for (const account of accounts) {
      const stake = account.account?.data?.parsed?.info?.stake;
      if (stake) {
        const amount = parseInt(stake.delegation?.stake || '0');
        if (amount > maxStake.amount) {
          maxStake = {
            amount,
            validatorAddress: stake.delegation?.voter || null,
            activationEpoch: stake.delegation?.activationEpoch || null,
          };
        }
      }
    }
    
    return maxStake;
    
  } catch (error) {
    console.error('Get staking status error:', error);
    return { amount: 0, validatorAddress: null, activationEpoch: null };
  }
}

/**
 * 获取账户余额
 */
export async function getBalance(
  walletAddress: string,
  rpcUrl: string
): Promise<number> {
  try {
    const response = await fetch(rpcUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'getBalance',
        params: [walletAddress],
      }),
    });
    
    const data = await response.json() as any;
    
    if (data.error) {
      console.error('RPC error:', data.error);
      return 0;
    }
    
    return data.result?.value || 0;
    
  } catch (error) {
    console.error('Get balance error:', error);
    return 0;
  }
}

export async function verifyGenesisCollectionWithDas(params: {
  walletAddress: string;
  collectionAddress: string;
  rpcUrl: string;
}): Promise<{ supported: boolean; hasToken: boolean; total?: number }> {
  try {
    const response = await fetch(params.rpcUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 'genesis-collection-check',
        method: 'searchAssets',
        params: {
          ownerAddress: params.walletAddress,
          grouping: ['collection', params.collectionAddress],
          page: 1,
          limit: 1,
        },
      }),
    });

    const data = (await response.json()) as any;

    if (data?.error) {
      const msg = String(data.error?.message || '');
      const unsupported =
        msg.toLowerCase().includes('method not found') ||
        msg.toLowerCase().includes('does not support') ||
        msg.toLowerCase().includes('unsupported');

      if (unsupported) {
        return { supported: false, hasToken: false, total: 0 };
      }
      return { supported: true, hasToken: false, total: 0 };
    }

    const total = Number(data?.result?.total ?? 0);
    if (!Number.isFinite(total)) return { supported: true, hasToken: false, total: 0 };
    return { supported: true, hasToken: total > 0, total };
  } catch (error) {
    console.error('Verify genesis collection (DAS) error:', error);
    return { supported: false, hasToken: false, total: 0 };
  }
}

export async function verifySeekerGenesisToken(params: {
  walletAddress: string;
  rpcUrl: string;
}): Promise<{ supported: boolean; hasToken: boolean }> {
  const res = await verifySeekerGenesisTokenWithDebug(params);
  return { supported: res.supported, hasToken: res.hasToken };
}

export async function verifySeekerGenesisTokenWithDebug(params: {
  walletAddress: string;
  rpcUrl: string;
}): Promise<{ supported: boolean; hasToken: boolean; debug: any }> {
  try {
    const SGT_MINT_AUTHORITY = 'GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4';
    const SGT_METADATA_ADDRESS = 'GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te';
    const SGT_GROUP_MINT_ADDRESS = 'GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te';

    const debug: any = {
      rpcHost: (() => {
        try {
          return new URL(params.rpcUrl).host;
        } catch (_) {
          return null;
        }
      })(),
      v2: { attempted: false, supported: false, pages: 0, accounts: 0 },
      v1: { attempted: false, accounts: 0, parsed: 0 },
      tokenkeg: { attempted: false, accounts: 0, parsed: 0, jsonParsed: false },
      mints: { unique: 0, checked: 0, authorityMatch: 0 },
      found: false,
    };

    const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

    const postRpc = async (payload: any, attempts: number = 3): Promise<{ ok: boolean; status: number; data: any | null }> => {
      let lastStatus = 0;
      for (let i = 0; i < attempts; i++) {
        const resp = await fetch(params.rpcUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        lastStatus = resp.status;
        if (resp.ok) {
          try {
            const data = (await resp.json()) as any;
            return { ok: true, status: resp.status, data };
          } catch (_) {
            return { ok: false, status: resp.status, data: null };
          }
        }
        if ([429, 500, 502, 503, 504].includes(resp.status) && i < attempts - 1) {
          await sleep(200 * (i + 1));
          continue;
        }
        break;
      }
      return { ok: false, status: lastStatus, data: null };
    };

    const fetchOwnerMints = async (programId: string, tag: 'v1' | 'tokenkeg'): Promise<{ supported: boolean; mints: string[] }> => {
      if (tag === 'tokenkeg') debug.tokenkeg.attempted = true
      let jsonSupported = false

      try {
        const jsonRes = await postRpc({
          jsonrpc: '2.0',
          id: `sgt-${tag}-json`,
          method: 'getTokenAccountsByOwner',
          params: [
            params.walletAddress,
            { programId },
            { encoding: 'jsonParsed' },
          ],
        });
        if (jsonRes.ok && jsonRes.data && !jsonRes.data?.error) {
          const accounts = jsonRes.data?.result?.value || []
            const mints: string[] = []
            for (const account of accounts) {
              const info = account?.account?.data?.parsed?.info
              const amount = Number(info?.tokenAmount?.amount ?? 0)
              const mint = info?.mint ? String(info.mint) : null
              if (!mint) continue
              if (!Number.isFinite(amount) || amount <= 0) continue
              mints.push(mint)
            }
            if (tag === 'tokenkeg') {
              debug.tokenkeg.jsonParsed = true
              debug.tokenkeg.accounts = Array.isArray(accounts) ? accounts.length : 0
            }
            return { supported: true, mints: Array.from(new Set(mints)) }
        }
        jsonSupported = jsonRes.ok
      } catch {
        // fallthrough to base64
      }

      // Fallback to base64 parsing (works for both token programs)
      try {
        const b64Res = await postRpc({
          jsonrpc: '2.0',
          id: `sgt-${tag}-b64`,
          method: 'getTokenAccountsByOwner',
          params: [
            params.walletAddress,
            { programId },
            { encoding: 'base64' },
          ],
        });
        if (!b64Res.ok || !b64Res.data || b64Res.data?.error) return { supported: jsonSupported, mints: [] }
        const accounts = b64Res.data?.result?.value || []
        const mints: string[] = []
        if (tag === 'tokenkeg') {
          debug.tokenkeg.accounts = Array.isArray(accounts) ? accounts.length : 0
        }
        for (const account of accounts) {
          try {
            const dataField = account?.account?.data
            const b64 = Array.isArray(dataField) ? String(dataField[0] || '') : String(dataField || '')
            if (!b64) continue
            const bytes = Uint8Array.from(Buffer.from(b64, 'base64'))
            if (bytes.length < 72) continue

            const mint = bs58.encode(Uint8Array.from(bytes.slice(0, 32)))
            let amount = 0n
            for (let i = 0; i < 8; i++) {
              amount |= BigInt(bytes[64 + i] ?? 0) << (8n * BigInt(i))
            }
            if (amount <= 0n) continue
            mints.push(mint)
            if (tag === 'tokenkeg') debug.tokenkeg.parsed++
          } catch (_) {
            continue
          }
        }
        return { supported: true, mints: Array.from(new Set(mints)) }
      } catch {
        return { supported: jsonSupported, mints: [] }
      }
    }

    const fetchToken2022Mints = async (): Promise<{ supported: boolean; mints: string[] }> => {
      let mints: string[] = []
      let paginationKey: string | null = null
      let usedV2 = false
      let usedV1 = false

      const isUnsupported = (msg: string): boolean => {
        const m = msg.toLowerCase();
        return m.includes('method not found') || m.includes('does not support') || m.includes('unsupported');
      };

      let guard = 0;
      do {
        guard++;
        if (guard > 50) break;

        debug.v2.attempted = true;
        const payload: any = {
          jsonrpc: '2.0',
          id: `sgt-v2-${guard}`,
          method: 'getTokenAccountsByOwnerV2',
          params: [
            params.walletAddress,
            { programId: TOKEN_2022_PROGRAM_ID.toBase58() },
            {
              encoding: 'jsonParsed',
              limit: 1000,
              ...(paginationKey ? { paginationKey } : {}),
            },
          ],
        };

        const res = await postRpc(payload);
        if (!res.ok) break;
        const data = res.data;
        if (data?.error) {
          const msg = String(data?.error?.message || '');
          if (isUnsupported(msg)) break;
          break;
        }

        usedV2 = true;
        debug.v2.supported = true;
        debug.v2.pages = guard;
        const accounts = data?.result?.value?.accounts || [];
        debug.v2.accounts += Array.isArray(accounts) ? accounts.length : 0;
        for (const account of accounts) {
          const info = account?.account?.data?.parsed?.info;
          const amount = Number(info?.tokenAmount?.amount ?? 0);
          const mint = info?.mint ? String(info.mint) : null;
          if (!mint) continue;
          if (!Number.isFinite(amount) || amount <= 0) continue;
          mints.push(mint);
        }

        paginationKey = data?.result?.paginationKey ?? null;
      } while (paginationKey);

      if (!usedV2) {
        debug.v1.attempted = true
        const v1 = await fetchOwnerMints(TOKEN_2022_PROGRAM_ID.toBase58(), 'v1')
        if (!v1.supported) return { supported: false, mints: [] }
        usedV1 = true
        mints = mints.concat(v1.mints)
      }

      return { supported: usedV2 || usedV1, mints: Array.from(new Set(mints)) }
    };

    const token2022 = await fetchToken2022Mints();
    if (!token2022.supported && !debug.v2.supported) {
      debug.v1.attempted = true
    }
    const tokenkeg = await fetchOwnerMints('TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA', 'tokenkeg')

    const mintStrings = Array.from(new Set([...(token2022.mints || []), ...(tokenkeg.mints || [])]))
    debug.mints.unique = mintStrings.length
    if (mintStrings.some((m) => m === SGT_METADATA_ADDRESS)) {
      debug.found = true;
      return { supported: token2022.supported || tokenkeg.supported, hasToken: true, debug };
    }

    if (mintStrings.length === 0) return { supported: token2022.supported || tokenkeg.supported, hasToken: false, debug };

    const readU32LE = (data: Uint8Array, offset: number): number => {
      return (
        (data[offset] ?? 0) |
        ((data[offset + 1] ?? 0) << 8) |
        ((data[offset + 2] ?? 0) << 16) |
        ((data[offset + 3] ?? 0) << 24)
      ) >>> 0;
    };

    const readU64LE = (data: Uint8Array, offset: number): bigint => {
      let v = 0n;
      for (let i = 0; i < 8; i++) {
        v |= BigInt(data[offset + i] ?? 0) << (8n * BigInt(i));
      }
      return v;
    };

    const parseTokenMintHeader = (data: Uint8Array): { mintAuthority: string | null; supply: bigint; decimals: number } | null => {
      if (data.length < 82) return null;
      const mintAuthorityOption = readU32LE(data, 0);
      const mintAuthority =
        mintAuthorityOption === 1 ? bs58.encode(Uint8Array.from(data.slice(4, 36))) : null;
      const supply = readU64LE(data, 36);
      const decimals = data[44] ?? 0;
      return { mintAuthority, supply, decimals };
    };

    const connection = new Connection(params.rpcUrl, { commitment: 'confirmed' } as any);
    const mintPubkeys = mintStrings.map((m) => new PublicKey(m));

    const BATCH = 100;
    for (let i = 0; i < mintPubkeys.length; i += BATCH) {
      const batch = mintPubkeys.slice(i, i + BATCH);
      const infos = await connection.getMultipleAccountsInfo(batch);
      debug.mints.checked += infos.length;

      for (let j = 0; j < infos.length; j++) {
        const mintInfo = infos[j];
        if (!mintInfo) continue;
        const mintPubkey = batch[j];

        try {
          const header = parseTokenMintHeader(mintInfo.data as any);
          if (!header) continue;
          if (header.mintAuthority !== SGT_MINT_AUTHORITY) continue;
          debug.mints.authorityMatch++;
          if (header.decimals !== 0) continue;
          if (header.supply !== 1n) continue;
          if (mintPubkey.toBase58() === SGT_GROUP_MINT_ADDRESS) continue;
          debug.found = true;
          return { supported: token2022.supported || tokenkeg.supported, hasToken: true, debug };
        } catch (_) {
          continue;
        }
      }
    }

    return { supported: token2022.supported || tokenkeg.supported, hasToken: false, debug };
  } catch (error) {
    console.error('Verify Seeker Genesis Token error:', error);
    return { supported: false, hasToken: false, debug: { error: String((error as any)?.message || error) } };
  }
}

export async function verifySolTransfer(params: {
  signature: string;
  from: string;
  to: string;
  minLamports: number;
  rpcUrl: string;
}): Promise<boolean> {
  try {
    const response = await fetch(params.rpcUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 1,
        method: 'getTransaction',
        params: [
          params.signature,
          {
            encoding: 'jsonParsed',
            commitment: 'confirmed',
            maxSupportedTransactionVersion: 0,
          },
        ],
      }),
    });

    const data = (await response.json()) as any;
    if (data.error) return false;

    const result = data.result;
    if (!result) return false;
    if (result.meta?.err) return false;

    const instructions: any[] = result.transaction?.message?.instructions || [];
    for (const ix of instructions) {
      if (ix.program !== 'system') continue;
      if (ix.parsed?.type !== 'transfer') continue;
      const info = ix.parsed?.info;
      if (!info) continue;
      if (info.source !== params.from) continue;
      if (info.destination !== params.to) continue;
      const lamports = Number(info.lamports || 0);
      if (!Number.isFinite(lamports)) continue;
      if (lamports >= params.minLamports) return true;
    }

    return false;
  } catch (error) {
    console.error('Verify SOL transfer error:', error);
    return false;
  }
}

/**
 * 简单字符串哈希（用于演示）
 */
async function hashString(str: string): Promise<number> {
  const encoder = new TextEncoder();
  const data = encoder.encode(str);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  const hashArray = new Uint8Array(hashBuffer);
  
  let hash = 0;
  for (let i = 0; i < 4; i++) {
    hash = (hash << 8) | hashArray[i];
  }
  
  return Math.abs(hash);
}
