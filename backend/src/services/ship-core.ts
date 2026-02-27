import bs58 from 'bs58'
import { createUmi } from '@metaplex-foundation/umi-bundle-defaults'
import {
  createNoopSigner,
  createSignerFromKeypair,
  dateTime,
  generateSigner,
  publicKey,
  signerIdentity,
  signTransaction,
  sol,
  some,
  unwrapOption,
} from '@metaplex-foundation/umi'
import { mplCore, fetchAsset } from '@metaplex-foundation/mpl-core'
import { CheckResult, addCollectionPlugin, createCollection } from '@metaplex-foundation/mpl-core'
import {
  DefaultGuardSetMintArgs,
  addConfigLines,
  createCandyGuard,
  createCandyMachine,
  fetchCandyMachine,
  mintV1,
  mplCandyMachine,
  safeFetchCandyGuard,
  wrap,
} from '@metaplex-foundation/mpl-core-candy-machine'
import { setComputeUnitLimit } from '@metaplex-foundation/mpl-toolbox'
import { Connection, PublicKey } from '@solana/web3.js'
import { getSolanaRpcUrl } from '../utils/solana-rpc'

type ShipMintTx = {
  transactionBase64: string
  assetAddress: string
  candyMachine: string
}

type ShipMintConfirm = {
  metadataUri: string | null
}

function getRpcUrl(env: any): string {
  return getSolanaRpcUrl(env)
}

function base64Encode(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString('base64')
}

function isBase58String(value: string): boolean {
  return /^[1-9A-HJ-NP-Za-km-z]+$/.test(value)
}

function describeNonBase58(value: string): string {
  for (let i = 0; i < value.length; i++) {
    const ch = value[i]
    if (!/^[1-9A-HJ-NP-Za-km-z]$/.test(ch)) {
      const code = value.codePointAt(i) ?? 0
      return `at=${i} char=${JSON.stringify(ch)} code=${code}`
    }
  }
  return 'unknown'
}

function safeWeb3PublicKey(value: string, label: string): PublicKey {
  const v = String(value || '')
  if (!isBase58String(v)) {
    throw new Error(`${label} invalid (base58). ${describeNonBase58(v)}`)
  }
  try {
    return new PublicKey(v)
  } catch (e: any) {
    throw new Error(`${label} invalid (web3). ${String(e?.message || e)}`)
  }
}

const MPL_CORE_CANDY_GUARD_PROGRAM_ID = new PublicKey('CMAGAKJ67e9hRZgfC5SFTbZH8MgEmtqazKXjmkaJjWTJ')

function deriveCandyGuardAddressBase58FromBase(basePkUmi: any): string {
  const baseBase58 = umiKeyToBase58(basePkUmi, 'basePublicKey')
  const basePk = safeWeb3PublicKey(baseBase58, 'basePublicKey')
  const [pda] = PublicKey.findProgramAddressSync([Buffer.from('candy_guard'), basePk.toBytes()], MPL_CORE_CANDY_GUARD_PROGRAM_ID)
  return pda.toBase58()
}

async function waitForSignatureFinal(params: { env: any; signature: string; timeoutMs?: number }): Promise<void> {
  const rpcUrl = getRpcUrl(params.env)
  const connection = new Connection(rpcUrl, { commitment: 'confirmed' } as any)
  const timeoutMs = typeof params.timeoutMs === 'number' ? params.timeoutMs : 60_000
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const res = await connection.getSignatureStatuses([params.signature], { searchTransactionHistory: true } as any)
    const st = res?.value?.[0]
    if (st) {
      if (st.err) throw new Error(`Transaction failed: ${JSON.stringify(st.err)}`)
      if (st.confirmationStatus === 'confirmed' || st.confirmationStatus === 'finalized') return
    }
    await sleep(1500)
  }
  throw new Error('Transaction not confirmed before timeout')
}

function umiKeyToBase58(key: any, label: string): string {
  const extract32 = (value: any): Uint8Array | null => {
    if (!value) return null
    if (value instanceof Uint8Array) return value.length === 32 ? value : null
    if (Array.isArray(value) && value.length === 32 && value.every((n) => Number.isInteger(n) && n >= 0 && n <= 255)) {
      return Uint8Array.from(value)
    }
    if (typeof value === 'object') {
      if (value.publicKey) {
        const fromPk = extract32(value.publicKey)
        if (fromPk) return fromPk
      }
      if (value.bytes) {
        const fromBytes = extract32(value.bytes)
        if (fromBytes) return fromBytes
      }
      if (typeof value.toBytes === 'function') {
        const maybe = value.toBytes()
        const fromMethod = extract32(maybe)
        if (fromMethod) return fromMethod
      }
      if (typeof value.toBuffer === 'function') {
        const maybe = value.toBuffer()
        const fromMethod = extract32(maybe)
        if (fromMethod) return fromMethod
      }
    }
    return null
  }

  const encodeBytes = (bytes: Uint8Array) => {
    if (bytes.length !== 32) throw new Error(`${label} invalid (expected 32 bytes, got ${bytes.length})`)
    return bs58.encode(bytes)
  }

  const asString = (value: any): string | null => {
    if (!value) return null
    if (typeof value === 'string') return value
    if (typeof value?.toBase58 === 'function') return value.toBase58()
    if (typeof value?.toString === 'function') return value.toString()
    return null
  }

  const candidate = (key as any)?.publicKey ?? key

  if (Array.isArray(candidate) && candidate.length >= 1) {
    const first = candidate[0]
    const bytes = extract32(first)
    if (bytes) return encodeBytes(bytes)
    const s = asString(first)
    if (s) {
      const v = s.trim()
      if (!isBase58String(v)) throw new Error(`${label} invalid (base58). ${describeNonBase58(v)}`)
      return v
    }
  }

  const bytes = extract32(candidate)
  if (bytes) return encodeBytes(bytes)

  const s = asString(candidate)
  if (s) {
    const v = s.trim()
    if (!isBase58String(v)) throw new Error(`${label} invalid (base58). ${describeNonBase58(v)}`)
    return v
  }

  const keys = candidate && typeof candidate === 'object' ? Object.keys(candidate).slice(0, 8).join(',') : ''
  const ctor = candidate && typeof candidate === 'object' ? candidate.constructor?.name : typeof candidate
  throw new Error(`${label} invalid (unsupported pubkey shape). ctor=${ctor} keys=${keys}`)
}

async function sleep(ms: number) {
  await new Promise((r) => setTimeout(r, ms))
}

async function waitForAccountInitialized(params: {
  env: any
  address: string
  timeoutMs?: number
}): Promise<boolean> {
  const rpcUrl = getRpcUrl(params.env)
  const connection = new Connection(rpcUrl, { commitment: 'confirmed' } as any)
  const pk = safeWeb3PublicKey(String(params.address || ''), 'pollAccountAddress')
  const timeoutMs = typeof params.timeoutMs === 'number' ? params.timeoutMs : 60_000
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const info = await connection.getAccountInfo(pk, 'confirmed')
    if (info && info.data && info.data.length > 0) return true
    await sleep(1500)
  }
  return false
}

function stripZeroWidth(s: string): string {
  return s.replace(/[\u200B-\u200D\uFEFF]/g, '')
}

function stripOuterQuotes(s: string): string {
  const t = s.trim()
  if (t.length < 2) return t
  const a = t[0]
  const b = t[t.length - 1]
  const pairs: Array<[string, string]> = [
    ['"', '"'],
    ["'", "'"],
    ['`', '`'],
    ['“', '”'],
    ['‘', '’'],
  ]
  for (const [l, r] of pairs) {
    if (a === l && b === r) return t.slice(1, -1).trim()
  }
  return t
}

function parseAdminSecretKey(secretRaw: any): Uint8Array {
  const raw0 = stripZeroWidth(String(secretRaw || '')).trim()
  const raw = stripZeroWidth(stripOuterQuotes(raw0).trim())
  if (!raw) throw new Error('Admin private key not configured')

  const firstBracket = raw.indexOf('[')
  const lastBracket = raw.lastIndexOf(']')
  if (firstBracket >= 0 && lastBracket > firstBracket) {
    const inner = raw.slice(firstBracket, lastBracket + 1)
    try {
      const arr = JSON.parse(inner)
      if (!Array.isArray(arr) || arr.length === 0) throw new Error('Admin private key invalid (json)')
      const bytes = Uint8Array.from(arr.map((v: any) => Number(v)))
      for (const b of bytes) {
        if (!Number.isFinite(b) || b < 0 || b > 255) throw new Error('Admin private key invalid (json)')
      }
      return bytes
    } catch {
      throw new Error('Admin private key invalid (json)')
    }
  }

  if (raw.startsWith('base64:')) {
    const b64 = raw.slice('base64:'.length).trim()
    try {
      return new Uint8Array(Buffer.from(b64, 'base64'))
    } catch {
      throw new Error('Admin private key invalid (base64)')
    }
  }

  if (raw.startsWith('base64url:')) {
    const b64u = raw.slice('base64url:'.length).trim()
    try {
      return new Uint8Array(Buffer.from(b64u, 'base64url'))
    } catch {
      throw new Error('Admin private key invalid (base64url)')
    }
  }

  const compact = stripZeroWidth(raw).replace(/\s+/g, '')
  if (!compact) throw new Error('Admin private key not configured')

  const looksBase64 = /[+/=]/.test(compact) && /^[A-Za-z0-9+/=]+$/.test(compact)
  if (looksBase64) {
    try {
      return new Uint8Array(Buffer.from(compact, 'base64'))
    } catch {}
  }

  const looksBase64Url = /[-_]/.test(compact) && /^[A-Za-z0-9_-]+={0,2}$/.test(compact)
  if (looksBase64Url) {
    try {
      return new Uint8Array(Buffer.from(compact, 'base64url'))
    } catch {}
  }

  try {
    return bs58.decode(compact)
  } catch {
    throw new Error('Admin private key invalid (base58)')
  }
}

export async function buildShipCoreCandyMachineMintTx(params: {
  env: any
  walletAddress: string
  candyMachineId: string
  computeUnits?: number
}): Promise<ShipMintTx> {
  const { env, walletAddress, candyMachineId } = params
  const rpcUrl = getRpcUrl(env)
  const walletPk = publicKey(walletAddress)
  const umi = createUmi(rpcUrl)
    .use(mplCandyMachine())
    .use(mplCore())
    .use(signerIdentity(createNoopSigner(walletPk)))

  const candyMachine = await fetchCandyMachine(umi, publicKey(candyMachineId))
  let candyGuard: any = null
  try {
    candyGuard = await safeFetchCandyGuard(umi, candyMachine.mintAuthority)
  } catch {
    candyGuard = null
  }

  let mintArgs: Partial<DefaultGuardSetMintArgs> = {}
  if (candyGuard) {
    const solPayment = unwrapOption((candyGuard as any).guards.solPayment) as any
    if (solPayment) {
      mintArgs.solPayment = some({ destination: solPayment.destination })
    }
    const mintLimit = unwrapOption((candyGuard as any).guards.mintLimit) as any
    if (mintLimit) {
      mintArgs.mintLimit = some({ id: mintLimit.id })
    }
  }

  const assetSigner = generateSigner(umi)
  const units = typeof params.computeUnits === 'number' ? params.computeUnits : 600_000
  const builder = setComputeUnitLimit(umi, { units }).add(
    mintV1(umi as any, {
      candyMachine: candyMachine.publicKey,
      collection: candyMachine.collectionMint,
      asset: assetSigner,
      candyGuard: candyGuard?.publicKey,
      mintArgs,
    })
  )

  const tx = await builder.buildWithLatestBlockhash(umi)
  const signed = await signTransaction(tx, [assetSigner])
  const bytes = umi.transactions.serialize(signed)

  return {
    transactionBase64: base64Encode(bytes),
    assetAddress: assetSigner.publicKey.toString(),
    candyMachine: candyMachineId,
  }
}

export async function confirmShipCoreMint(params: {
  env: any
  walletAddress: string
  assetAddress: string
  expectedCollection?: string | null
}): Promise<ShipMintConfirm> {
  const { env, walletAddress, assetAddress, expectedCollection } = params
  const rpcUrl = getRpcUrl(env)
  const umi = createUmi(rpcUrl).use(mplCore())
  const asset = await fetchAsset(umi as any, publicKey(assetAddress))
  if (asset.owner.toString() !== walletAddress) {
    throw new Error('Mint not owned by wallet')
  }
  if (expectedCollection) {
    if (asset.updateAuthority.type !== 'Collection' || asset.updateAuthority.address?.toString() !== expectedCollection) {
      throw new Error('Mint not in expected collection')
    }
  }
  return { metadataUri: asset.uri || null }
}

export function getAdminUmi(env: any) {
  const rpcUrl = getRpcUrl(env)
  const umi = createUmi(rpcUrl).use(mplCandyMachine()).use(mplCore())
  const candidates: Array<{ name: string; value: any }> = [
    { name: 'SHIP_ADMIN_PRIVATE_KEY', value: env.SHIP_ADMIN_PRIVATE_KEY },
    { name: 'ADMIN_PRIVATE_KEY', value: env.ADMIN_PRIVATE_KEY },
  ]
  const errors: Array<{ name: string; error: string }> = []
  let secretBytes: Uint8Array | null = null
  for (const c of candidates) {
    const raw = String(c.value || '').trim()
    if (!raw) continue
    try {
      secretBytes = parseAdminSecretKey(c.value)
      break
    } catch (e: any) {
      errors.push({ name: c.name, error: String(e?.message || e) })
    }
  }
  if (!secretBytes) {
    if (errors.length > 0) {
      throw new Error(`Admin private key invalid. ${errors.map((e) => `${e.name}: ${e.error}`).join(' | ')}`)
    }
    throw new Error('Admin private key not configured')
  }
  if (secretBytes.length !== 64) {
    throw new Error(`Admin private key invalid (length=${secretBytes.length}, expected=64)`)
  }
  const keypair = umi.eddsa.createKeypairFromSecretKey(secretBytes)
  const signer = createSignerFromKeypair(umi, keypair)
  return umi.use(signerIdentity(signer))
}

export function diagnoseShipAdminSigner(env: any): { configured: boolean; ok: boolean; publicKey?: string; error?: string } {
  const raw = String(env?.SHIP_ADMIN_PRIVATE_KEY || env?.ADMIN_PRIVATE_KEY || '').trim()
  if (!raw) return { configured: false, ok: false, error: 'Admin private key not configured' }
  try {
    const umi = getAdminUmi(env)
    return { configured: true, ok: true, publicKey: umi.identity.publicKey.toString() }
  } catch (e: any) {
    return { configured: true, ok: false, error: String(e?.message || e) }
  }
}

export async function createShipSoulboundCollection(params: {
  env: any
  name: string
  uri: string
  oracleBaseAddress?: string
}): Promise<{ collectionAddress: string }> {
  const umi = getAdminUmi(params.env)
  const oracle = publicKey(params.oracleBaseAddress || 'GxaWxaQVeaNeFHehFQEDeKR65MnT6Nup81AGwh2EEnuq')
  const collection = generateSigner(umi)
  const builder = createCollection(umi as any, {
    collection,
    updateAuthority: umi.identity.publicKey,
    name: params.name,
    uri: params.uri,
    plugins: [
      {
        type: 'Oracle',
        resultsOffset: { type: 'Anchor' },
        baseAddress: oracle,
        lifecycleChecks: { transfer: [CheckResult.CAN_REJECT] },
        baseAddressConfig: undefined,
      } as any,
    ],
  })
  await builder.sendAndConfirm(umi as any)
  return { collectionAddress: collection.publicKey.toString() }
}

export async function applyShipSoulboundOracle(params: {
  env: any
  collectionAddress: string
  oracleBaseAddress?: string
}): Promise<{ signature: string }> {
  const umi = getAdminUmi(params.env)
  const oracle = publicKey(params.oracleBaseAddress || 'GxaWxaQVeaNeFHehFQEDeKR65MnT6Nup81AGwh2EEnuq')
  const builder = addCollectionPlugin(umi as any, {
    collection: publicKey(params.collectionAddress),
    plugin: {
      type: 'Oracle',
      resultsOffset: { type: 'Anchor' },
      baseAddress: oracle,
      lifecycleChecks: { transfer: [CheckResult.CAN_REJECT] },
      baseAddressConfig: undefined,
    } as any,
  })
  const res = await builder.sendAndConfirm(umi as any)
  return { signature: res.signature.toString() }
}

export async function createShipCandyMachineWithGuards(params: {
  env: any
  collectionAddress: string
  itemsAvailable: number
  configLineSettings?: { prefixName: string; nameLength: number; prefixUri: string; uriLength: number; isSequential: boolean }
  mintPriceLamports: number
  mintRecipient: string
  botTaxLamports: number
  startAt: number
  mintLimit: number
  mintLimitId: number
}): Promise<{ candyMachineAddress: string; candyGuardAddress: string }> {
  const umi = getAdminUmi(params.env)
  const candyMachine = generateSigner(umi)
  const cmBuilder = await createCandyMachine(umi as any, {
    candyMachine,
    collection: publicKey(params.collectionAddress),
    collectionUpdateAuthority: umi.identity,
    itemsAvailable: BigInt(params.itemsAvailable),
    configLineSettings: some({
      prefixName: params.configLineSettings?.prefixName ?? 'Seeker Spaceship #',
      nameLength: params.configLineSettings?.nameLength ?? 6,
      prefixUri: params.configLineSettings?.prefixUri ?? '',
      uriLength: params.configLineSettings?.uriLength ?? 200,
      isSequential: params.configLineSettings?.isSequential ?? false,
    }),
  } as any)
  await cmBuilder.sendAndConfirm(umi as any)

  const base = umi.identity.publicKey
  const candyGuardAddress = deriveCandyGuardAddressBase58FromBase(base)
  const guardBuilder = await createCandyGuard(umi as any, {
    candyGuard: publicKey(candyGuardAddress),
    base,
    authority: umi.identity,
    payer: umi.identity,
    guards: {
      botTax: { lamports: sol(params.botTaxLamports / 1_000_000_000), lastInstruction: false },
      solPayment: { lamports: sol(params.mintPriceLamports / 1_000_000_000), destination: publicKey(stripZeroWidth(String(params.mintRecipient || '')).trim().replace(/\s+/g, '')) },
      startDate: { date: dateTime(new Date(params.startAt * 1000).toISOString()) },
      mintLimit: { id: params.mintLimitId, limit: params.mintLimit },
    },
  } as any)
  await guardBuilder.sendAndConfirm(umi as any)

  const wrapBuilder = wrap(umi as any, { candyMachine: candyMachine.publicKey, candyGuard: publicKey(candyGuardAddress), authority: umi.identity, candyMachineAuthority: umi.identity })
  await wrapBuilder.sendAndConfirm(umi as any)

  return { candyMachineAddress: candyMachine.publicKey.toString(), candyGuardAddress }
}

export async function addShipCandyMachineConfigLines(params: {
  env: any
  candyMachineAddress: string
  index: number
  configLines: { name: string; uri: string }[]
}): Promise<{ signature: string }> {
  const umi = getAdminUmi(params.env)
  const builder = addConfigLines(umi as any, {
    candyMachine: publicKey(params.candyMachineAddress),
    index: params.index,
    configLines: params.configLines,
  } as any)
  const res = await builder.sendAndConfirm(umi as any)
  return { signature: res.signature.toString() }
}

export async function createShipCandyGuardAndWrap(params: {
  env: any
  candyMachineAddress: string
  mintPriceLamports: number
  mintRecipient: string
  botTaxLamports: number
  startAt: number
  mintLimit: number
  mintLimitId: number
  confirm?: boolean
}): Promise<{ candyGuardAddress: string; createGuardSignature: string; wrapSignature: string }> {
  const candyMachineAddress = stripZeroWidth(String(params.candyMachineAddress || '')).trim().replace(/\s+/g, '')
  const mintRecipient = stripZeroWidth(String(params.mintRecipient || '')).trim().replace(/\s+/g, '')
  safeWeb3PublicKey(candyMachineAddress, 'candyMachineAddress')
  safeWeb3PublicKey(mintRecipient, 'mintRecipient')
  let candyMachinePk: any
  try {
    candyMachinePk = publicKey(candyMachineAddress)
  } catch {
    throw new Error('Invalid candyMachineAddress (umi)')
  }
  let mintRecipientPk: any
  try {
    mintRecipientPk = publicKey(mintRecipient)
  } catch {
    throw new Error('Invalid mintRecipient (umi)')
  }
  const umi = getAdminUmi(params.env)
  const candyMachine = await fetchCandyMachine(umi as any, candyMachinePk)
  const base = candyMachine.mintAuthority
  const candyGuardAddress = deriveCandyGuardAddressBase58FromBase(base)
  const candyGuardPk = publicKey(candyGuardAddress)

  const connection = new Connection(getRpcUrl(params.env), { commitment: 'confirmed' } as any)
  const guardWeb3Pk = safeWeb3PublicKey(candyGuardAddress, 'candyGuardAddress')
  const existingGuard = await connection.getAccountInfo(guardWeb3Pk, 'confirmed')

  const confirm = params.confirm !== false
  let createIncluded = false
  let builder: any
  if (existingGuard) {
    builder = wrap(umi as any, { candyMachine: candyMachinePk, candyGuard: candyGuardPk, authority: umi.identity, candyMachineAuthority: umi.identity })
  } else {
    createIncluded = true
    const guardBuilder = await createCandyGuard(umi as any, {
      candyGuard: candyGuardPk,
      base,
      authority: umi.identity,
      payer: umi.identity,
      guards: {
        botTax: { lamports: sol(params.botTaxLamports / 1_000_000_000), lastInstruction: false },
        solPayment: { lamports: sol(params.mintPriceLamports / 1_000_000_000), destination: mintRecipientPk },
        startDate: { date: dateTime(new Date(params.startAt * 1000).toISOString()) },
        mintLimit: { id: params.mintLimitId, limit: params.mintLimit },
      },
    } as any)
    const wrapBuilder = wrap(umi as any, { candyMachine: candyMachinePk, candyGuard: candyGuardPk, authority: umi.identity, candyMachineAuthority: umi.identity })
    builder = guardBuilder.add(wrapBuilder)
  }

  if (confirm) {
    const res = await builder.send(umi as any, { skipPreflight: true, maxRetries: 3 } as any)
    const sig = bs58.encode(Uint8Array.from(res as any))
    await waitForSignatureFinal({ env: params.env, signature: sig, timeoutMs: 90_000 })
    return { candyGuardAddress, createGuardSignature: createIncluded ? sig : '', wrapSignature: sig }
  }

  const sigBytes = await builder.send(umi as any, { skipPreflight: true, maxRetries: 3 } as any)
  const sig = bs58.encode(Uint8Array.from(sigBytes as any))
  return { candyGuardAddress, createGuardSignature: createIncluded ? sig : '', wrapSignature: sig }
}
