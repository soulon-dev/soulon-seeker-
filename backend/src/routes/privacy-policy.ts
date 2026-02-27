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
  const title = 'Privacy Policy'
  const appName = 'Soulon'

  const content = `
    <h1>Privacy Policy</h1>
    <p>Welcome to <strong>${appName}</strong> (the “App”, “we”, “us”). This Privacy Policy explains how we collect, use, store, share, and protect information, and the choices available to you.</p>

    <h2>1. Information We Collect</h2>
    <h3>1.1 Content you provide</h3>
    <ul>
      <li><strong>Memory/Text content</strong>: memories, questionnaire answers, and chat messages you submit to provide personalized AI features.</li>
      <li><strong>Support</strong>: bug reports, contact details (such as email), and optional device information.</li>
    </ul>
    <h3>1.2 Wallet-related information</h3>
    <ul>
      <li><strong>Wallet address</strong>: used for account identification and on-chain interactions.</li>
      <li><strong>No private keys</strong>: we do not collect or store your wallet private keys or seed phrases. Signing is performed in your wallet app via Mobile Wallet Adapter.</li>
    </ul>
    <h3>1.3 Device & network information (optional)</h3>
    <p>If you choose to include device information in a bug report, we may collect details such as device model, OS version, screen info, memory/storage summary, network type, and app version. This should not include unique device identifiers (e.g., IMEI, MAC address, Android ID).</p>
    <h3>1.4 Push notifications</h3>
    <p>If you enable notifications, we may process a push token (e.g., Firebase Cloud Messaging token) to deliver service notifications (such as adventure invites).</p>

    <h2>2. How We Use Information</h2>
    <ul>
      <li><strong>Core features</strong>: encrypted memory storage & retrieval, personalized chat, questionnaire analysis and persona updates.</li>
      <li><strong>Security</strong>: prevent abuse, troubleshoot incidents, and maintain service reliability.</li>
      <li><strong>Support</strong>: respond to bug reports and inquiries.</li>
    </ul>

    <h2>3. Storage & Security</h2>
    <h3>3.1 End-to-end encryption & on-device keys</h3>
    <p>We use encryption such as AES-GCM-256 to protect memory content. Encryption keys are stored securely on your device (e.g., Android KeyStore).</p>
    <h3>3.2 On-chain/decentralized storage</h3>
    <p>Encrypted data may be uploaded via Irys and stored on networks such as Arweave. Due to the immutable nature of such networks, on-chain data typically cannot be deleted; however, encryption reduces the risk of content exposure.</p>

    <h2>4. Third-Party Services</h2>
    <p>We may rely on third-party services to deliver features:</p>
    <ul>
      <li><strong>Blockchain & wallet</strong>: Solana network and Mobile Wallet Adapter.</li>
      <li><strong>Push</strong>: Firebase Cloud Messaging (if notifications are enabled).</li>
      <li><strong>AI providers</strong>: to generate/analyze text, your inputs and necessary context may be transmitted to third-party AI services for processing. We aim to minimize transmitted data and apply reasonable safeguards.</li>
    </ul>

    <h2>5. Sharing</h2>
    <p>We do not sell your personal information. We share information only when:</p>
    <ul>
      <li>required to provide third-party services described above;</li>
      <li>required by law, regulation, or legal process;</li>
      <li>necessary to protect our rights and user safety (e.g., anti-fraud and abuse prevention).</li>
    </ul>

    <h2>6. Your Choices</h2>
    <ul>
      <li><strong>Access & correction</strong>: you can view and edit certain information in the App.</li>
      <li><strong>Delete local data</strong>: you can delete local data/cache at any time.</li>
      <li><strong>On-chain limitations</strong>: if data is written to Arweave-like immutable storage, it generally cannot be physically deleted; you can delete local keys/local copies to reduce accessibility.</li>
      <li><strong>Disable notifications</strong>: you can revoke notification permission in system settings.</li>
    </ul>

    <h2>7. Children</h2>
    <p>The App is not intended for individuals under 18. If you believe we may have collected information from a minor, please contact us.</p>

    <h2>8. Changes</h2>
    <p>We may update this Privacy Policy from time to time. Material changes will be notified via in-app notice or website announcements.</p>

    <h2>9. Contact</h2>
    <p>If you have questions, please contact us via the support channels available within the App.</p>
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
        body { margin: 0; font-family: -apple-system,BlinkMacSystemFont,Segoe UI,Roboto,Helvetica,Arial,"PingFang SC","Hiragino Sans GB","Noto Sans CJK SC","Microsoft YaHei",sans-serif; background: #0A0A0F; color: rgba(255,255,255,0.92); }
        .wrap { max-width: 920px; margin: 0 auto; padding: 32px 20px 64px; }
        .top { display:flex; align-items:center; justify-content:space-between; gap: 12px; margin-bottom: 24px; }
        .brand { font-weight: 700; letter-spacing: 0.2px; color: #ffffff; }
        h1 { font-size: 34px; margin: 16px 0 8px; }
        h2 { font-size: 20px; margin: 28px 0 10px; }
        h3 { font-size: 16px; margin: 18px 0 8px; color: rgba(255,255,255,0.88); }
        p, li { line-height: 1.7; color: rgba(255,255,255,0.78); }
        ul { padding-left: 18px; }
        a { color: #7c5cff; }
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

export async function handlePrivacyPolicy(request: Request, _env: Env): Promise<Response> {
  void request
  return htmlResponse(render())
}
