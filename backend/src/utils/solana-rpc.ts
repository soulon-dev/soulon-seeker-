export function getSolanaRpcUrl(env: any, network?: string): string {
  const envUrl = (env?.SOLANA_RPC_URL || '').trim()
  if (envUrl) return envUrl
  const n = String(network || '').toLowerCase()
  if (n === 'devnet') return 'https://api.devnet.solana.com'
  return 'https://api.mainnet-beta.solana.com'
}
