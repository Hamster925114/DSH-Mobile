/**
 * @dsh-mobile/bridge — pairing and health routes for DSH Mobile.
 *
 * Mounted by the DSH desktop profile. It deliberately does only three things:
 *
 * 1. Mint a short-lived, one-time pairing code at startup and write it to
 *    `$DSH_HOME/mobile/pairing.json` (and optionally show it on a loopback-only
 *    page).
 * 2. Exchange that code for the process launch token via
 *    `ctx.connection.authenticatedUrl()`. The phone then trades the token for
 *    the normal 30-day browser cookie.
 * 3. Expose `/mobile/hello` for version/port diagnostics.
 *
 * It does NOT proxy the API, does NOT store device tokens yet, and does NOT
 * implement push. Those are intentionally out of scope for M0.
 *
 * @module @dsh-mobile/bridge
 */

import { randomBytes, timingSafeEqual } from 'node:crypto'
import { mkdir, rename, unlink, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'

/** Stable Cordis plugin name. */
export const name = 'dsh-mobile-bridge'

/** Services required before the pairing routes can be registered. */
export const inject = ['webServer', 'connection']

const VERSION = '0.0.1'
const DEFAULT_TTL_SECONDS = 120
const MIN_TTL_SECONDS = 30
const MAX_BODY_BYTES = 4096
const DEFAULT_MAX_ATTEMPTS_PER_MINUTE = 20

/**
 * Validate and normalize a configured public base URL.
 * @param {string} value
 * @returns {string}
 */
function normalizeBaseUrl(value) {
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`dsh-mobile-bridge: publicUrl is not a valid URL: ${JSON.stringify(value)}`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`dsh-mobile-bridge: publicUrl must be http(s), got ${url.protocol}`)
  }
  return url.href.replace(/\/+$/, '')
}

/**
 * Resolve the base URL the phone should use.
 * @param {object} webServer
 * @param {string | undefined} configured
 * @returns {string}
 */
function resolveBaseUrl(webServer, configured) {
  if (typeof configured === 'string' && configured.trim() !== '') {
    return normalizeBaseUrl(configured.trim())
  }
  const origin = typeof webServer.getOrigin === 'function' ? webServer.getOrigin() : undefined
  if (typeof origin === 'string' && origin !== '') return origin.replace(/\/+$/, '')
  return `${webServer.protocol ?? 'http:'}//127.0.0.1:${String(webServer.port)}`
}

/**
 * Resolve the base URL, waiting briefly for the webserver to finish binding.
 * `getOrigin()` throws before `listen` assigns a port, and the plugin may be
 * inserted before that callback runs.
 * @param {object} webServer
 * @param {string | undefined} configured
 * @returns {Promise<string>}
 */
async function waitForBaseUrl(webServer, configured) {
  if (typeof configured === 'string' && configured.trim() !== '') {
    return normalizeBaseUrl(configured.trim())
  }
  const deadline = Date.now() + 2000
  for (;;) {
    try {
      const origin = typeof webServer.getOrigin === 'function' ? webServer.getOrigin() : undefined
      if (typeof origin === 'string' && origin !== '') return origin.replace(/\/+$/, '')
    } catch {
      // The listener has not bound a port yet; retry below.
    }
    if (Date.now() >= deadline) {
      throw new Error('dsh-mobile-bridge: webserver did not expose an origin within 2s')
    }
    await new Promise((resolve) => setTimeout(resolve, 25))
  }
}

/**
 * Whether the request arrived over loopback (the pairing page is local-only).
 * @param {import('node:http').IncomingMessage} req
 * @returns {boolean}
 */
function isLoopback(req) {
  const address = req.socket?.remoteAddress ?? ''
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
}

/**
 * Write a JSON response.
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {unknown} value
 */
function sendJson(res, status, value) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  })
  res.end(JSON.stringify(value))
}

/**
 * Write an HTML response.
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {string} html
 */
function sendHtml(res, status, html) {
  res.writeHead(status, {
    'content-type': 'text/html; charset=utf-8',
    'cache-control': 'no-store',
  })
  res.end(html)
}

/**
 * Read and parse a bounded JSON request body.
 * @param {import('node:http').IncomingMessage} req
 * @returns {Promise<unknown>}
 */
async function readJson(req) {
  const chunks = []
  let size = 0
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
    size += buffer.length
    if (size > MAX_BODY_BYTES) throw new Error('body-too-large')
    chunks.push(buffer)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

/**
 * Constant-time string comparison.
 * @param {unknown} left
 * @param {unknown} right
 * @returns {boolean}
 */
function safeEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false
  const a = Buffer.from(left, 'utf8')
  const b = Buffer.from(right, 'utf8')
  return a.length === b.length && timingSafeEqual(a, b)
}

/**
 * Escape text for the local pairing page.
 * @param {unknown} value
 * @returns {string}
 */
function escapeHtml(value) {
  const replacements = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  }
  return String(value).replace(/[&<>"']/g, (character) => replacements[character])
}

/**
 * Mount the bridge.
 * @param {object} ctx Cordis context carrying `webServer` and `connection`.
 * @param {{ publicUrl?: string, pairingTtlSeconds?: number, maxAttemptsPerMinute?: number }} [config]
 */
export async function apply(ctx, config = {}) {
  const webServer = ctx.webServer
  const connection = ctx.connection
  if (webServer === undefined || connection === undefined) {
    throw new Error('dsh-mobile-bridge: webServer and connection services are required')
  }

  const ttlSeconds =
    Number.isSafeInteger(config.pairingTtlSeconds) && config.pairingTtlSeconds >= MIN_TTL_SECONDS
      ? config.pairingTtlSeconds
      : DEFAULT_TTL_SECONDS
  const maxAttempts =
    Number.isSafeInteger(config.maxAttemptsPerMinute) && config.maxAttemptsPerMinute > 0
      ? config.maxAttemptsPerMinute
      : DEFAULT_MAX_ATTEMPTS_PER_MINUTE

  let base
  try {
    base = await waitForBaseUrl(webServer, config.publicUrl)
  } catch (error) {
    ctx.logger?.warn?.(`dsh-mobile-bridge: ${error instanceof Error ? error.message : String(error)}`)
    base = resolveBaseUrl(webServer, config.publicUrl)
  }

  /** The current origin, refreshed at request time when the port changed. */
  function currentBase() {
    if (typeof config.publicUrl === 'string' && config.publicUrl.trim() !== '') {
      return normalizeBaseUrl(config.publicUrl.trim())
    }
    try {
      return webServer.getOrigin().replace(/\/+$/, '')
    } catch {
      return base
    }
  }

  const home = process.env.DSH_HOME?.trim() || join(homedir(), '.dsh')
  const directory = join(home, 'mobile')
  const file = join(directory, 'pairing.json')

  let code = ''
  let expiresAt = 0
  let consumed = false
  /** @type {Map<string, { count: number, resetAt: number }>} */
  const attempts = new Map()

  /**
   * Mint a fresh one-time code and persist it for the desktop operator.
   * @returns {Promise<object>}
   */
  async function rotate() {
    code = randomBytes(8).toString('hex').toUpperCase()
    expiresAt = Date.now() + ttlSeconds * 1000
    consumed = false
    const payload = {
      v: 1,
      base: currentBase(),
      code,
      expiresAt: new Date(expiresAt).toISOString(),
      port: webServer.port,
      pid: process.pid,
      createdAt: new Date().toISOString(),
    }
    await mkdir(directory, { recursive: true })
    const temporary = `${file}.${String(process.pid)}.tmp`
    await writeFile(temporary, `${JSON.stringify(payload, null, 2)}\n`, 'utf8')
    await rename(temporary, file)
    return payload
  }

  /**
   * Count failed attempts per remote address.
   * @param {import('node:http').IncomingMessage} req
   * @returns {boolean}
   */
  function rateLimited(req) {
    const key = req.socket?.remoteAddress ?? 'unknown'
    const now = Date.now()
    const record = attempts.get(key)
    if (record === undefined || now >= record.resetAt) {
      attempts.set(key, { count: 1, resetAt: now + 60_000 })
      return false
    }
    record.count += 1
    return record.count > maxAttempts
  }

  /**
   * GET: loopback-only page with the code. POST: exchange the code for a
   * tokenized URL.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handlePair(req, res) {
    if (req.method === 'GET') {
      if (!isLoopback(req)) {
        res.writeHead(404)
        res.end()
        return
      }
      if (code === '' || consumed || Date.now() > expiresAt) {
        try {
          await rotate()
        } catch (error) {
          ctx.logger?.warn?.(`dsh-mobile-bridge: rotate failed: ${String(error)}`)
        }
      }
      const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DSH Mobile 配对</title>
<style>
body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;max-width:36rem;margin:3rem auto;padding:0 1.25rem;color:#1f2328}
code,pre{background:#f6f8fa;border:1px solid #d0d7de;border-radius:.5rem;padding:.25rem .5rem}
pre{font-size:1.75rem;letter-spacing:.18em;text-align:center;padding:1rem}
.muted{color:#57606a;font-size:.9rem;line-height:1.6}
</style>
</head>
<body>
<h1>DSH Mobile 配对</h1>
<p>在手机 App 中输入下面的配对码（M0 手动输入；M1 改为扫码）：</p>
<pre>${escapeHtml(code)}</pre>
<p>服务地址：<code>${escapeHtml(currentBase())}</code></p>
<p>有效期至：<code>${escapeHtml(new Date(expiresAt).toISOString())}</code></p>
<p class="muted">此页面仅本机回环地址可访问。配对码一次性使用；重启 DSH 后会重新生成。</p>
</body>
</html>`
      sendHtml(res, 200, html)
      return
    }

    if (req.method !== 'POST') {
      res.writeHead(405, { allow: 'GET, POST' })
      res.end()
      return
    }

    if (rateLimited(req)) {
      sendJson(res, 429, { error: 'rate-limited', hint: 'wait one minute and retry' })
      return
    }

    if (consumed || Date.now() > expiresAt) {
      sendJson(res, 410, { error: 'pairing-expired', hint: 'restart DSH or rotate the pairing code' })
      return
    }

    let body
    try {
      body = await readJson(req)
    } catch {
      sendJson(res, 400, { error: 'bad-json' })
      return
    }

    const presented = body !== null && typeof body === 'object' ? body.code : undefined
    if (!safeEqual(presented, code)) {
      sendJson(res, 403, { error: 'bad-code' })
      return
    }

    // Burn the code before issuing the URL: a retry must not replay it.
    consumed = true
    let url
    try {
      url = connection.authenticatedUrl(`${currentBase()}/`)
    } catch (error) {
      sendJson(res, 500, {
        error: 'auth-unavailable',
        message: error instanceof Error ? error.message : String(error),
      })
      return
    }
    sendJson(res, 200, { url })
  }

  /**
   * Health and version handshake for the app.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  function handleHello(req, res) {
    if (req.method !== 'GET') {
      res.writeHead(405, { allow: 'GET' })
      res.end()
      return
    }
    sendJson(res, 200, {
      ok: true,
      name: '@dsh-mobile/bridge',
      version: VERSION,
      dsh: process.env.DSH_CLIENT_VERSION ?? null,
      port: webServer.port,
      base: currentBase(),
      now: new Date().toISOString(),
    })
  }

  try {
    await rotate()
  } catch (error) {
    ctx.logger?.warn?.(`dsh-mobile-bridge: failed to write pairing file: ${String(error)}`)
  }

  ctx.effect(() => {
    return () => {
      void unlink(file).catch(() => {})
    }
  }, 'dsh-mobile-bridge: pairing file cleanup')

  ctx.effect(
    () => webServer.register({ kind: 'exact', path: '/mobile/pair', handler: handlePair }),
    'dsh-mobile-bridge: /mobile/pair',
  )
  ctx.effect(
    () => webServer.register({ kind: 'exact', path: '/mobile/hello', handler: handleHello }),
    'dsh-mobile-bridge: /mobile/hello',
  )
}
