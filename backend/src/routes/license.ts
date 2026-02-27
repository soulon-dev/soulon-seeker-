import type { Env } from '../index'

function htmlResponse(html: string, status: number = 200): Response {
  return new Response(html, {
    status,
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'public, max-age=3600',
    },
  })
}

function render(): string {
  const title = 'License & Compliance'
  const appName = 'Soulon'

  const content = `
    <h1>${title}</h1>
    <p>This page is provided for transparency and compliance purposes for <strong>${appName}</strong>. It describes the licensing approach, acceptable use expectations, and compliance commitments in a high-level, human-readable form.</p>

    <h2>1. App License (End Users)</h2>
    <p>The App is licensed to you for personal use. You may not copy, modify, redistribute, reverse engineer, or exploit the App except where such restriction is prohibited by applicable law.</p>

    <h2>2. User Content</h2>
    <p>You are responsible for the content you submit (including memories, messages, and questionnaire responses). You must not submit illegal, infringing, harmful, or abusive content.</p>

    <h2>3. Blockchain & Storage Characteristics</h2>
    <p>The App may interact with decentralized networks (e.g., Solana) and decentralized storage systems (e.g., Arweave via Irys). These systems can have immutable characteristics. When content is encrypted and uploaded, it may be impractical or impossible to delete the on-network record; you should understand these properties before using the App.</p>

    <h2>4. Compliance Commitments</h2>
    <ul>
      <li><strong>No wallet secrets collected</strong>: the App does not request or store your wallet private keys or seed phrases. Wallet signing occurs in your wallet app.</li>
      <li><strong>Security measures</strong>: the App uses encryption to protect user-submitted content, and applies reasonable safeguards to reduce the risk of unauthorized access.</li>
      <li><strong>Policy compliance</strong>: the App is intended to comply with applicable platform policies and relevant laws and regulations in the jurisdictions where it is distributed.</li>
    </ul>

    <h2>5. Third-Party Services & Dependencies</h2>
    <p>The App may use third-party services (e.g., push notification providers) and open-source libraries. Those components may be subject to their own terms and licenses.</p>

    <h2>6. Updates</h2>
    <p>This document may be updated from time to time to reflect changes in functionality or compliance requirements.</p>
  `

  return `<!doctype html>
  <html lang="en">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
      <title>${title} - ${appName}</title>
      <meta name="robots" content="index,follow" />
      <style>
        :root { color-scheme: dark; }
        body { margin: 0; font-family: -apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Helvetica,Arial,sans-serif; background: #0A0A0F; color: rgba(255,255,255,0.92); }
        .wrap { max-width: 920px; margin: 0 auto; padding: 32px 20px 64px; }
        .top { display:flex; align-items:center; justify-content:space-between; gap: 12px; margin-bottom: 24px; }
        .brand { font-weight: 700; letter-spacing: 0.2px; color: #ffffff; }
        h1 { font-size: 34px; margin: 16px 0 8px; }
        h2 { font-size: 20px; margin: 28px 0 10px; }
        p, li { line-height: 1.7; color: rgba(255,255,255,0.78); }
        ul { padding-left: 18px; }
        .card { background: rgba(255,255,255,0.04); border: 1px solid rgba(255,255,255,0.08); border-radius: 16px; padding: 18px 18px; }
      </style>
    </head>
    <body>
      <div class="wrap">
        <div class="top">
          <div class="brand">${appName}</div>
        </div>
        <div class="card">
          ${content}
        </div>
      </div>
    </body>
  </html>`
}

export async function handleLicense(_request: Request, _env: Env): Promise<Response> {
  return htmlResponse(render())
}
