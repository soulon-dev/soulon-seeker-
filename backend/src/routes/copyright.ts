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
  const title = 'Copyright & Ownership'
  const appName = 'Soulon'

  const content = `
    <h1>${title}</h1>
    <p>This page is provided to confirm ownership and legal compliance for <strong>${appName}</strong>.</p>

    <h2>1. Copyright Notice</h2>
    <p>Unless otherwise indicated, all rights, title, and interest in the App (including its software, user interface, designs, and documentation) are owned by the developer of ${appName}. All rights reserved.</p>

    <h2>2. Trademarks</h2>
    <p>All product names, logos, and brands are property of their respective owners. Any use of third-party marks is for identification purposes only and does not imply endorsement.</p>

    <h2>3. Open Source & Third-Party Components</h2>
    <p>The App may include open-source software and third-party libraries. Such components are licensed under their respective terms. Where required, applicable notices and license texts should accompany distribution.</p>

    <h2>4. User Content</h2>
    <p>You retain rights to content you submit to the App. You represent that you have the right to submit such content and that it does not infringe the rights of others.</p>

    <h2>5. Reporting IP Concerns</h2>
    <p>If you believe your intellectual property rights are being infringed, please report the issue through the in-app support channels so it can be reviewed.</p>
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

export async function handleCopyright(_request: Request, _env: Env): Promise<Response> {
  return htmlResponse(render())
}
