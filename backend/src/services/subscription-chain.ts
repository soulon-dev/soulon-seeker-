import * as bs58 from 'bs58'
import {
  Connection,
  Keypair,
  PublicKey,
  Transaction,
  TransactionInstruction,
  sendAndConfirmRawTransaction,
  SystemProgram,
} from '@solana/web3.js'

export type SubscriptionConfigField = 'admin' | 'executor' | 'recipient'

function toInstructionData(bytes: Uint8Array): any {
  const anyGlobal = globalThis as any
  if (anyGlobal.Buffer && typeof anyGlobal.Buffer.from === 'function') {
    return anyGlobal.Buffer.from(bytes)
  }
  return bytes
}

export type UpdateConfigResult = {
  signature: string
  confirmedAdmin?: string
  confirmedExecutor?: string
  confirmedRecipient?: string
}

export type SubscriptionProgramStatus = {
  rpcUrl: string
  programId: string
  configPda: string
  programDeployed: boolean
  programExecutable: boolean
  configInitialized: boolean
  config?: { admin: string; executor: string; recipient: string }
}

export function assertSolanaPubkeyBase58(value: string, label: string): PublicKey {
  try {
    const pk = new PublicKey(value)
    const decoded = bs58.decode(pk.toBase58())
    if (decoded.length !== 32) throw new Error('invalid length')
    return pk
  } catch {
    throw new Error(`Invalid Solana address for ${label}`)
  }
}

export function parseProgramConfigAccount(data: Uint8Array): { admin: PublicKey; executor: PublicKey; recipient: PublicKey } {
  if (data.length < 8 + 32 + 32 + 32 + 1) {
    throw new Error('Invalid ProgramConfig account length')
  }
  const view = data.subarray(8)
  const admin = new PublicKey(view.subarray(0, 32))
  const executor = new PublicKey(view.subarray(32, 64))
  const recipient = new PublicKey(view.subarray(64, 96))
  return { admin, executor, recipient }
}

async function anchorDiscriminator(name: string): Promise<Uint8Array> {
  const preimage = new TextEncoder().encode(`global:${name}`)
  const digest = await crypto.subtle.digest('SHA-256', preimage)
  return new Uint8Array(digest).slice(0, 8)
}

function configPdaFor(programId: PublicKey): PublicKey {
  const [configPda] = PublicKey.findProgramAddressSync([new TextEncoder().encode('config')], programId)
  return configPda
}

function decodeKeypairBase58(secretBase58: string): Keypair {
  const bytes = bs58.decode(secretBase58)
  return Keypair.fromSecretKey(bytes)
}

export async function getSubscriptionProgramStatus(params: {
  rpcUrl: string
  programId: string
}): Promise<SubscriptionProgramStatus> {
  const programId = assertSolanaPubkeyBase58(params.programId, 'programId')
  const connection = new Connection(params.rpcUrl, 'confirmed')
  const programAccount = await connection.getAccountInfo(programId, 'confirmed')
  const deployed = !!programAccount
  const executable = !!programAccount?.executable

  const configPda = configPdaFor(programId)
  const configAccount = await connection.getAccountInfo(configPda, 'confirmed')
  if (!configAccount?.data) {
    return {
      rpcUrl: params.rpcUrl,
      programId: programId.toBase58(),
      configPda: configPda.toBase58(),
      programDeployed: deployed,
      programExecutable: executable,
      configInitialized: false,
    }
  }

  const decoded = parseProgramConfigAccount(configAccount.data)
  return {
    rpcUrl: params.rpcUrl,
    programId: programId.toBase58(),
    configPda: configPda.toBase58(),
    programDeployed: deployed,
    programExecutable: executable,
    configInitialized: true,
    config: {
      admin: decoded.admin.toBase58(),
      executor: decoded.executor.toBase58(),
      recipient: decoded.recipient.toBase58(),
    },
  }
}

export async function initializeSubscriptionProgramConfigOnChain(params: {
  rpcUrl: string
  programId: string
  adminSecretKeyBase58: string
  executor: string
  recipient: string
}): Promise<{ signature: string; configPda: string; confirmed: { admin: string; executor: string; recipient: string } }> {
  const programId = assertSolanaPubkeyBase58(params.programId, 'programId')
  const executor = assertSolanaPubkeyBase58(params.executor, 'executor')
  const recipient = assertSolanaPubkeyBase58(params.recipient, 'recipient')
  const adminKeypair = decodeKeypairBase58(params.adminSecretKeyBase58)

  const connection = new Connection(params.rpcUrl, 'confirmed')
  const programAccount = await connection.getAccountInfo(programId, 'confirmed')
  if (!programAccount || !programAccount.executable) {
    throw new Error(`Program not deployed or not executable: ${programId.toBase58()}`)
  }

  const configPda = configPdaFor(programId)
  const existing = await connection.getAccountInfo(configPda, 'confirmed')
  if (existing) {
    const decoded = parseProgramConfigAccount(existing.data)
    return {
      signature: '',
      configPda: configPda.toBase58(),
      confirmed: {
        admin: decoded.admin.toBase58(),
        executor: decoded.executor.toBase58(),
        recipient: decoded.recipient.toBase58(),
      },
    }
  }

  const discriminator = await anchorDiscriminator('initialize_config')
  const data = new Uint8Array(8 + 32 + 32)
  data.set(discriminator, 0)
  data.set(executor.toBytes(), 8)
  data.set(recipient.toBytes(), 8 + 32)

  const ix = new TransactionInstruction({
    programId,
    keys: [
      { pubkey: adminKeypair.publicKey, isSigner: true, isWritable: true },
      { pubkey: configPda, isSigner: false, isWritable: true },
      { pubkey: SystemProgram.programId, isSigner: false, isWritable: false },
    ],
    data: toInstructionData(data),
  })

  const blockhash = await connection.getLatestBlockhash('confirmed')
  const tx = new Transaction({
    feePayer: adminKeypair.publicKey,
    blockhash: blockhash.blockhash,
    lastValidBlockHeight: blockhash.lastValidBlockHeight,
  }).add(ix)

  tx.sign(adminKeypair)
  const raw = tx.serialize()
  const signature = await sendAndConfirmRawTransaction(connection, raw, { commitment: 'confirmed' })

  const account = await connection.getAccountInfo(configPda, 'confirmed')
  if (!account?.data) throw new Error('ProgramConfig not found after initialize')
  const decoded = parseProgramConfigAccount(account.data)

  return {
    signature,
    configPda: configPda.toBase58(),
    confirmed: {
      admin: decoded.admin.toBase58(),
      executor: decoded.executor.toBase58(),
      recipient: decoded.recipient.toBase58(),
    },
  }
}

export async function updateSubscriptionProgramConfigOnChain(params: {
  rpcUrl: string
  programId: string
  adminSecretKeyBase58: string
  field: SubscriptionConfigField
  newValue: string
}): Promise<UpdateConfigResult> {
  const programId = assertSolanaPubkeyBase58(params.programId, 'programId')
  const newValue = assertSolanaPubkeyBase58(params.newValue, `new ${params.field}`)
  const adminKeypair = decodeKeypairBase58(params.adminSecretKeyBase58)

  const connection = new Connection(params.rpcUrl, 'confirmed')
  const configPda = configPdaFor(programId)

  const method = params.field === 'admin'
    ? 'update_admin'
    : params.field === 'executor'
      ? 'update_executor'
      : 'update_recipient'

  const discriminator = await anchorDiscriminator(method)
  const data = new Uint8Array(8 + 32)
  data.set(discriminator, 0)
  data.set(newValue.toBytes(), 8)

  const ix = new TransactionInstruction({
    programId,
    keys: [
      { pubkey: adminKeypair.publicKey, isSigner: true, isWritable: false },
      { pubkey: configPda, isSigner: false, isWritable: true },
    ],
    data: toInstructionData(data),
  })

  const blockhash = await connection.getLatestBlockhash('confirmed')
  const tx = new Transaction({
    feePayer: adminKeypair.publicKey,
    blockhash: blockhash.blockhash,
    lastValidBlockHeight: blockhash.lastValidBlockHeight,
  }).add(ix)

  tx.sign(adminKeypair)
  const raw = tx.serialize()
  const signature = await sendAndConfirmRawTransaction(connection, raw, { commitment: 'confirmed' })

  const account = await connection.getAccountInfo(configPda, 'confirmed')
  if (!account?.data) {
    throw new Error('ProgramConfig not found after update')
  }

  const decoded = parseProgramConfigAccount(account.data)
  return {
    signature,
    confirmedAdmin: decoded.admin.toBase58(),
    confirmedExecutor: decoded.executor.toBase58(),
    confirmedRecipient: decoded.recipient.toBase58(),
  }
}
