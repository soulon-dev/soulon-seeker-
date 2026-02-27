export async function signConfigValue(env: any, key: string, value: string): Promise<string | null> {
  const privateKeyPem = env.CONFIG_PRIVATE_KEY
  if (!privateKeyPem) {
    return `mock_sig_${key}_${Date.now()}`
  }

  try {
    const pemHeader = '-----BEGIN PRIVATE KEY-----'
    const pemFooter = '-----END PRIVATE KEY-----'
    const pemContents = privateKeyPem
      .substring(privateKeyPem.indexOf(pemHeader) + pemHeader.length, privateKeyPem.indexOf(pemFooter))
      .replace(/\s/g, '')

    const binaryDerString = atob(pemContents)
    const binaryDer = new Uint8Array(binaryDerString.length)
    for (let i = 0; i < binaryDerString.length; i++) {
      binaryDer[i] = binaryDerString.charCodeAt(i)
    }

    const cryptoKey = await crypto.subtle.importKey(
      'pkcs8',
      binaryDer.buffer,
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['sign']
    )

    const encoder = new TextEncoder()
    const data = encoder.encode(`${key}:${value}`)
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', cryptoKey, data)

    return Array.from(new Uint8Array(signature))
      .map(b => b.toString(16).padStart(2, '0'))
      .join('')
  } catch {
    return null
  }
}
