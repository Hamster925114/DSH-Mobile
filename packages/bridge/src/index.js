/**
 * @dsh-mobile/bridge — pairing and health routes for DSH Mobile.
 *
 * Mounted by the DSH desktop profile. It deliberately does only three things:
 *
 * 1. Mint a short-lived, one-time pairing code at startup and write it to
 *    `$DSH_HOME/mobile/pairing.json` (and optionally show it on a loopback-only
 *    page).
 * 2. Exchange that code for a revocable device token and the process launch URL
 *    via `ctx.connection.authenticatedUrl()`. The phone stores the device token
 *    and trades the launch URL for the normal 30-day browser cookie.
 * 3. Gate phone traffic through a loopback device proxy so revoking a device
 *    takes effect on its next HTTP or WebSocket request.
 * 4. Expose `/mobile/hello`, `/mobile/devices`, and token refresh endpoints.
 *
 * @module @dsh-mobile/bridge
 */

import { randomBytes, timingSafeEqual } from 'node:crypto'
import { mkdir, rename, unlink, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import QRCode from 'qrcode'
import { DeviceStore } from './devices.js'
import { startDeviceProxy } from './proxy.js'

/** Stable Cordis plugin name. */
export const name = 'dsh-mobile-bridge'

/** Services required before the pairing routes can be registered. */
export const inject = ['webServer', 'connection']

const VERSION = '0.1.0'
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
    throw new Error(`dsh-mobile-bridge: base URL is not valid: ${JSON.stringify(value)}`)
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`dsh-mobile-bridge: base URL must be http(s), got ${url.protocol}`)
  }
  if (url.search !== '' || url.hash !== '') {
    throw new Error('dsh-mobile-bridge: base URL must not contain a query or fragment')
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
 * Whether a host is a loopback literal. Used only for UI hints; the real
 * Host/Origin fence lives in @deepseek-ai/dsh-client-connection.
 * @param {string} host
 * @returns {boolean}
 */
function isLoopbackHost(host) {
  return host === 'localhost' || host === '::1' || /^127\./.test(host) || /^::ffff:127\./i.test(host)
}

/**
 * Whether a base URL points at this machine only.
 * @param {string} value
 * @returns {boolean}
 */
function isLoopbackUrl(value) {
  try {
    return isLoopbackHost(new URL(value).hostname)
  } catch {
    return false
  }
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
 * Read a device token from the Authorization or X-DSH-Device-Token headers.
 * @param {import('node:http').IncomingMessage} req
 * @returns {string | undefined}
 */
function bearerToken(req) {
  const authorization = req.headers?.authorization
  if (typeof authorization === 'string' && /^Bearer\s+/i.test(authorization)) {
    return authorization.replace(/^Bearer\s+/i, '').trim()
  }
  const direct = req.headers?.['x-dsh-device-token']
  return typeof direct === 'string' && direct.trim() !== '' ? direct.trim() : undefined
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
 * @param {{
 *   publicUrl?: string,
 *   endpoints?: Array<{ id?: string, label?: string, url: string, kind?: string }>,
 *   pairingTtlSeconds?: number,
 *   maxAttemptsPerMinute?: number,
 *   deviceTtlDays?: number,
 *   maxDevices?: number,
 *   deviceProxy?: boolean,
 *   deviceProxyPort?: number,
 * }} [config]
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
  const deviceTtlDays =
    Number.isSafeInteger(config.deviceTtlDays) && config.deviceTtlDays > 0
      ? config.deviceTtlDays
      : 30
  const maxDevices =
    Number.isSafeInteger(config.maxDevices) && config.maxDevices > 0 ? config.maxDevices : 50

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

  /** The origin of the listener itself, or undefined before listen(). */
  function bindOrigin() {
    try {
      return webServer.getOrigin().replace(/\/+$/, '')
    } catch {
      return undefined
    }
  }

  /**
   * Every phone-reachable base URL this profile knows about, in display order.
   *
   * `endpoints` is the explicit list for LAN / Tailscale / frp. `publicUrl` is
   * kept as a one-entry legacy alias. When DSH binds a concrete LAN address we
   * append that origin automatically, so a single LAN profile needs no extra
   * config. A loopback bind is intentionally not advertised as LAN.
   *
   * @returns {Array<{ id: string, label: string, url: string, kind: string }>}
   */
  function endpoints() {
    /** @type {Array<{ id: string, label: string, url: string, kind: string }>} */
    const list = []
    const seen = new Set()
    function add(id, label, url, kind) {
      const normalized = normalizeBaseUrl(url)
      if (seen.has(normalized)) return
      seen.add(normalized)
      list.push({ id, label, url: normalized, kind })
    }

    if (Array.isArray(config.endpoints)) {
      config.endpoints.forEach((entry, index) => {
        if (entry === null || typeof entry !== 'object') return
        if (typeof entry.url !== 'string' || entry.url.trim() === '') return
        add(
          String(entry.id ?? `endpoint-${String(index + 1)}`),
          String(entry.label ?? entry.id ?? `连接 ${String(index + 1)}`),
          entry.url,
          String(entry.kind ?? 'custom'),
        )
      })
    }

    if (typeof config.publicUrl === 'string' && config.publicUrl.trim() !== '') {
      add('public', '公网 / 自定义', config.publicUrl, 'custom')
    }

    const bound = bindOrigin()
    if (bound !== undefined && typeof webServer.host === 'string' && !isLoopbackHost(webServer.host)) {
      add('lan', '局域网', bound, 'lan')
    }

    if (list.length === 0) add('local', '本机', currentBase(), 'local')
    return list
  }

  const home = process.env.DSH_HOME?.trim() || join(homedir(), '.dsh')
  const directory = join(home, 'mobile')
  const file = join(directory, 'pairing.json')
  const devices = new DeviceStore({
    directory,
    ttlDays: deviceTtlDays,
    maxDevices,
    logger: ctx.logger,
  })
  await devices.load()

  /** @type {{ close: () => Promise<void>, port: number } | undefined} */
  let deviceProxy
  const proxyEnabled = config.deviceProxy !== false
  const proxyPort =
    Number.isSafeInteger(config.deviceProxyPort) && config.deviceProxyPort > 0
      ? config.deviceProxyPort
      : Number(webServer.port) + 1

  if (proxyEnabled) {
    try {
      deviceProxy = await startDeviceProxy({
        targetHost: webServer.host ?? '127.0.0.1',
        targetPort: Number(webServer.port),
        listenPort: proxyPort,
        store: devices,
        logger: ctx.logger,
      })
      ctx.logger?.info?.(
        `dsh-mobile-bridge: device proxy listening on 127.0.0.1:${String(deviceProxy.port)} -> ${String(webServer.host ?? '127.0.0.1')}:${String(webServer.port)}`,
      )
    } catch (error) {
      ctx.logger?.warn?.(
        `dsh-mobile-bridge: device proxy did not start (${String(error)}); Tailscale Serve should keep pointing at DSH until this is fixed`,
      )
      deviceProxy = undefined
    }
  }

  ctx.effect(() => () => {
    void deviceProxy?.close()
    return undefined
  }, 'dsh-mobile-bridge: device proxy')

  const seenFlush = setInterval(() => {
    void devices.flush().catch(() => {})
  }, 30_000)
  ctx.effect(() => () => {
    clearInterval(seenFlush)
    return undefined
  }, 'dsh-mobile-bridge: device last-seen flush')

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
    const endpointList = endpoints()
    const payload = {
      v: 1,
      base: endpointList[0]?.url ?? currentBase(),
      endpoints: endpointList,
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
   * The pairing page shows a one-time login code, so keep it on the desktop.
   *
   * Loopback is the normal case. When DSH binds a concrete LAN/Tailscale IP
   * there is no loopback listener, so a browser on the same machine connects
   * with remoteAddress === localAddress; allow only that, and only when the
   * Host header names the bind address. A reverse proxy (frp / Tailscale
   * serve) sees 127.0.0.1 as its peer but carries the public Host, and is
   * rejected so the code page is never exposed through the tunnel.
   *
   * @param {import('node:http').IncomingMessage} req
   * @returns {boolean}
   */
  function isLocalPairingRequest(req) {
    // Never accept a request that a reverse proxy forwarded, even if the
    // proxy runs on this machine. The pairing code page is desktop-only.
    if (req.headers?.['x-forwarded-for'] !== undefined || req.headers?.forwarded !== undefined) {
      return false
    }
    const hostHeader = String(req.headers?.host ?? '').toLowerCase()
    if (hostHeader === '') return isLoopback(req)
    const host = hostHeader.startsWith('[')
      ? hostHeader.slice(1, hostHeader.indexOf(']'))
      : hostHeader.split(':')[0]
    const bind = String(webServer.host ?? '').toLowerCase()
    const hostMatchesBind = host === bind || (isLoopbackHost(host) && isLoopbackHost(bind))
    if (!hostMatchesBind) return false
    if (isLoopbackHost(bind)) return isLoopback(req)
    const remote = req.socket?.remoteAddress
    const local = req.socket?.localAddress
    return (
      typeof remote === 'string' &&
      typeof local === 'string' &&
      remote === local &&
      !isLoopback(req)
    )
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
      if (!isLocalPairingRequest(req)) {
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
      let endpointList
      try {
        endpointList = endpoints()
      } catch (error) {
        sendHtml(
          res,
          500,
          `<h1>DSH Mobile 配对配置错误</h1><p>${escapeHtml(
            error instanceof Error ? error.message : String(error),
          )}</p>`,
        )
        return
      }
      const loopbackOnly = endpointList.every((endpoint) => isLoopbackUrl(endpoint.url))
      const cards = []
      for (const endpoint of endpointList) {
        const pairingUrl =
          `dshmobile://pair?base=${encodeURIComponent(endpoint.url)}&code=${encodeURIComponent(code)}` +
          `&kind=${encodeURIComponent(endpoint.kind)}`
        let qrSvg = ''
        try {
          qrSvg = await QRCode.toString(pairingUrl, {
            type: 'svg',
            margin: 1,
            width: 200,
            errorCorrectionLevel: 'M',
          })
        } catch (error) {
          ctx.logger?.warn?.(`dsh-mobile-bridge: QR generation failed: ${String(error)}`)
        }
        cards.push(`<section class="endpoint">
<h2>${escapeHtml(endpoint.label)}</h2>
<div class="qr">${qrSvg}</div>
<p class="url"><code>${escapeHtml(endpoint.url)}</code></p>
</section>`)
      }
      const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DSH Mobile 配对</title>
<style>
body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;max-width:42rem;margin:3rem auto;padding:0 1.25rem;color:#1f2328}
code,pre{background:#f6f8fa;border:1px solid #d0d7de;border-radius:.5rem;padding:.25rem .5rem}
pre{font-size:1.75rem;letter-spacing:.18em;text-align:center;padding:1rem}
.endpoints{display:grid;grid-template-columns:repeat(auto-fit,minmax(15rem,1fr));gap:1rem;margin:1.5rem 0}
.endpoint{border:1px solid #d0d7de;border-radius:.75rem;padding:1rem;background:#fff}
.endpoint h2{font-size:1rem;margin:0 0 .5rem}
.endpoint .url{word-break:break-all;font-size:.82rem;line-height:1.5}
.qr{display:flex;justify-content:center;margin:.5rem 0}
.qr svg{width:200px;max-width:70vw;height:auto}
.warn{background:#fff8c5;border:1px solid #d4a72c;border-radius:.5rem;padding:.75rem 1rem;line-height:1.6}
.muted{color:#57606a;font-size:.9rem;line-height:1.6}
</style>
</head>
<body>
<h1>DSH Mobile 配对</h1>
${loopbackOnly ? '<p class="warn">当前没有手机可达的地址：DSH 绑定的是回环地址。请把 webserver.host 改成电脑的局域网 IP，或配置 Tailscale / frp 的 endpoints 后重启 DSH。详见 docs/lan-setup.md。</p>' : ''}
<p>选择一种连接方式，用 DSH Mobile 扫描对应二维码。三种方式可以同时保存，之后在 App 的「连接管理」里切换。</p>
<div class="endpoints">${cards.join('')}</div>
<p>配对码（所有连接共用）：</p>
<pre>${escapeHtml(code)}</pre>
<p>有效期至：<code>${escapeHtml(new Date(expiresAt).toISOString())}</code></p>
<p class="muted">此页面仅本机回环地址可访问。配对码一次性使用；重启 DSH 后会重新生成。</p>
<p class="muted"><a href="/mobile/devices/ui">设备管理 / 撤销丢失的手机</a></p>
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

    let endpointList
    try {
      endpointList = endpoints()
    } catch (error) {
      sendJson(res, 500, {
        error: 'endpoint-invalid',
        message: error instanceof Error ? error.message : String(error),
      })
      return
    }

    let requestedBase = endpointList[0]?.url
    const presentedBase =
      body !== null && typeof body === 'object' && typeof body.base === 'string' ? body.base.trim() : ''
    if (presentedBase !== '') {
      let normalized
      try {
        normalized = normalizeBaseUrl(presentedBase)
      } catch {
        sendJson(res, 400, { error: 'bad-base' })
        return
      }
      const match = endpointList.find((endpoint) => endpoint.url === normalized)
      if (match === undefined) {
        sendJson(res, 400, {
          error: 'unknown-base',
          hint: 'use one of the endpoints listed on /mobile/pair',
        })
        return
      }
      requestedBase = match.url
    }

    if (requestedBase === undefined) {
      sendJson(res, 500, { error: 'no-endpoint' })
      return
    }

    let url
    try {
      url = connection.authenticatedUrl(`${requestedBase}/`)
    } catch (error) {
      sendJson(res, 500, {
        error: 'auth-unavailable',
        message: error instanceof Error ? error.message : String(error),
      })
      return
    }

    let issued
    try {
      issued = await devices.register({
        name: body?.deviceName,
        platform: body?.platform,
        appVersion: body?.appVersion,
      })
    } catch (error) {
      if (error instanceof Error && error.message === 'device-limit') {
        sendJson(res, 429, {
          error: 'device-limit',
          hint: 'revoke an old device on the desktop device page before pairing another one',
        })
        return
      }
      sendJson(res, 500, {
        error: 'device-store-failed',
        message: error instanceof Error ? error.message : String(error),
      })
      return
    }

    // Burn the code only after the device record exists and the launch URL is
    // ready; a failed registration must not consume a valid pairing code.
    consumed = true
    sendJson(res, 200, {
      url,
      base: requestedBase,
      deviceId: issued.device.id,
      deviceToken: issued.token,
      deviceName: issued.device.name,
      deviceExpiresAt: issued.device.expiresAt,
    })
  }

  /**
   * Admin routes are reachable from the desktop loopback page or from a phone
   * that already holds a valid DSH owner cookie. A device token alone is never
   * enough to list or revoke other devices.
   * @param {import('node:http').IncomingMessage} req
   * @returns {boolean}
   */
  function isAdminRequest(req) {
    if (isLocalPairingRequest(req)) return true
    try {
      return typeof connection.requestRejection === 'function'
        ? connection.requestRejection(req) === undefined
        : false
    } catch {
      return false
    }
  }

  /**
   * List devices for the management screen.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handleDevices(req, res) {
    if (req.method !== 'GET') {
      res.writeHead(405, { allow: 'GET' })
      res.end()
      return
    }
    if (!isAdminRequest(req)) {
      sendJson(res, 403, { error: 'forbidden' })
      return
    }
    try {
      sendJson(res, 200, {
        devices: await devices.list(),
        proxyEnabled: deviceProxy !== undefined,
        proxyPort: deviceProxy?.port ?? null,
      })
    } catch (error) {
      sendJson(res, 500, { error: 'device-store-failed', message: String(error) })
    }
  }

  /**
   * Desktop-friendly device management page. This is the recovery path when
   * the phone itself is lost: it uses the loopback pairing-page fence and
   * never needs a device token.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handleDevicesPage(req, res) {
    if (req.method !== 'GET') {
      res.writeHead(405, { allow: 'GET' })
      res.end()
      return
    }
    if (!isAdminRequest(req)) {
      sendHtml(res, 403, '<h1>403</h1><p>设备管理页面只允许本机或已登录的 DSH 浏览器访问。</p>')
      return
    }
    let list
    try {
      list = await devices.list()
    } catch (error) {
      sendHtml(res, 500, `<h1>设备列表读取失败</h1><pre>${escapeHtml(String(error))}</pre>`)
      return
    }
    const rows = list
      .map((device) => {
        const revoked = device.revoked
        return `<tr>
<td>${escapeHtml(device.name)}</td>
<td>${escapeHtml(device.platform)}${device.appVersion ? ` · ${escapeHtml(device.appVersion)}` : ''}</td>
<td>${escapeHtml(device.createdAt)}</td>
<td>${escapeHtml(device.lastSeenAt ?? '—')}</td>
<td>${revoked ? '已撤销' : '<button data-revoke="' + escapeHtml(device.id) + '">撤销</button>'}</td>
</tr>`
      })
      .join('')
    const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DSH Mobile 设备管理</title>
<style>
body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;max-width:72rem;margin:2rem auto;padding:0 1rem;color:#1f2328}
table{width:100%;border-collapse:collapse;margin:1rem 0}
th,td{border-bottom:1px solid #d0d7de;text-align:left;padding:.55rem;vertical-align:top;font-size:.9rem}
th{color:#57606a;font-weight:600}button{padding:.3rem .65rem}
.muted{color:#57606a;font-size:.9rem}code{background:#f6f8fa;padding:.1rem .3rem;border-radius:.3rem}
</style></head><body>
<h1>DSH Mobile 设备管理</h1>
<p class="muted">此页面仅桌面本机可访问。撤销后该设备下一次请求立即失效；设备令牌明文只保存在手机 Keystore 中。</p>
<p>代理：${deviceProxy === undefined ? '未启动（撤销不会即时生效）' : `127.0.0.1:${escapeHtml(String(deviceProxy.port))}`}</p>
<table><thead><tr><th>设备</th><th>平台</th><th>创建</th><th>最近使用</th><th>操作</th></tr></thead>
<tbody>${rows || '<tr><td colspan="5">还没有已登记设备</td></tr>'}</tbody></table>
<p><a href="/mobile/pair">返回配对页</a></p>
<script>
document.querySelectorAll('[data-revoke]').forEach((button) => {
  button.addEventListener('click', async () => {
    if (!confirm('撤销这台设备？')) return
    button.disabled = true
    const response = await fetch('/mobile/devices/revoke', {
      method: 'POST',
      headers: {'content-type': 'application/json'},
      body: JSON.stringify({id: button.dataset.revoke})
    })
    if (response.ok) location.reload()
    else { alert('撤销失败：HTTP ' + response.status); button.disabled = false }
  })
})
</script>
</body></html>`
    sendHtml(res, 200, html)
  }

  /**
   * Revoke one device. Revocation is checked by the device proxy before every
   * HTTP request and WebSocket upgrade, so it takes effect immediately.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handleRevokeDevice(req, res) {
    if (req.method !== 'POST') {
      res.writeHead(405, { allow: 'POST' })
      res.end()
      return
    }
    if (!isAdminRequest(req)) {
      sendJson(res, 403, { error: 'forbidden' })
      return
    }
    let body
    try {
      body = await readJson(req)
    } catch {
      sendJson(res, 400, { error: 'bad-json' })
      return
    }
    const id = body !== null && typeof body === 'object' ? body.id : undefined
    if (typeof id !== 'string' || id === '') {
      sendJson(res, 400, { error: 'missing-device-id' })
      return
    }
    const revoked = await devices.revoke(id)
    if (!revoked) {
      sendJson(res, 404, { error: 'unknown-device' })
      return
    }
    sendJson(res, 200, { ok: true, id })
  }

  /**
   * Rename a device from the management screen.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handleRenameDevice(req, res) {
    if (req.method !== 'POST') {
      res.writeHead(405, { allow: 'POST' })
      res.end()
      return
    }
    if (!isAdminRequest(req)) {
      sendJson(res, 403, { error: 'forbidden' })
      return
    }
    let body
    try {
      body = await readJson(req)
    } catch {
      sendJson(res, 400, { error: 'bad-json' })
      return
    }
    const id = body !== null && typeof body === 'object' ? body.id : undefined
    if (typeof id !== 'string' || id === '') {
      sendJson(res, 400, { error: 'missing-device-id' })
      return
    }
    const device = await devices.rename(id, body?.name)
    if (device === null) {
      sendJson(res, 404, { error: 'unknown-device' })
      return
    }
    sendJson(res, 200, { ok: true, device })
  }

  /**
   * Validate a device token without issuing a browser session.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handleDeviceMe(req, res) {
    if (req.method !== 'GET') {
      res.writeHead(405, { allow: 'GET' })
      res.end()
      return
    }
    const token = bearerToken(req)
    const device = await devices.verify(token)
    if (device === null) {
      sendJson(res, 401, { error: 'device-invalid' })
      return
    }
    await devices.flush().catch(() => {})
    sendJson(res, 200, { ok: true, device })
  }

  /**
   * Exchange a valid device token for a fresh DSH launch URL. This is used
   * when the browser cookie expired but the device is still trusted.
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handleTokenExchange(req, res) {
    if (req.method !== 'POST') {
      res.writeHead(405, { allow: 'POST' })
      res.end()
      return
    }
    const token = bearerToken(req)
    const device = await devices.verify(token)
    if (device === null) {
      sendJson(res, 401, { error: 'device-invalid' })
      return
    }
    let body = {}
    try {
      body = await readJson(req)
    } catch {
      body = {}
    }
    const requested = body !== null && typeof body === 'object' ? body.base : undefined
    let endpointList
    try {
      endpointList = endpoints()
    } catch {
      endpointList = []
    }
    let selected = endpointList[0]?.url
    if (typeof requested === 'string' && requested.trim() !== '') {
      try {
        const normalized = normalizeBaseUrl(requested.trim())
        selected = endpointList.find((entry) => entry.url === normalized)?.url
      } catch {
        selected = undefined
      }
    }
    if (selected === undefined) {
      sendJson(res, 400, { error: 'unknown-base' })
      return
    }
    let url
    try {
      url = connection.authenticatedUrl(`${selected}/`)
    } catch (error) {
      sendJson(res, 500, { error: 'auth-unavailable', message: String(error) })
      return
    }
    const renewed = await devices.renew(device.id)
    sendJson(res, 200, {
      url,
      base: selected,
      deviceId: device.id,
      deviceExpiresAt: renewed?.expiresAt ?? device.expiresAt,
    })
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
    let endpointList
    try {
      endpointList = endpoints()
    } catch {
      endpointList = [{ id: 'local', label: '本机', url: currentBase(), kind: 'local' }]
    }
    sendJson(res, 200, {
      ok: true,
      name: '@dsh-mobile/bridge',
      version: VERSION,
      dsh: process.env.DSH_CLIENT_VERSION ?? null,
      port: webServer.port,
      base: endpointList[0]?.url ?? currentBase(),
      endpoints: endpointList,
      deviceProxy: deviceProxy === undefined ? null : { port: deviceProxy.port },
      deviceTtlDays,
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
  ctx.effect(
    () => webServer.register({ kind: 'exact', path: '/mobile/devices', handler: handleDevices }),
    'dsh-mobile-bridge: /mobile/devices',
  )
  ctx.effect(
    () =>
      webServer.register({
        kind: 'exact',
        path: '/mobile/devices/ui',
        handler: handleDevicesPage,
      }),
    'dsh-mobile-bridge: /mobile/devices/ui',
  )
  ctx.effect(
    () =>
      webServer.register({
        kind: 'exact',
        path: '/mobile/devices/revoke',
        handler: handleRevokeDevice,
      }),
    'dsh-mobile-bridge: /mobile/devices/revoke',
  )
  ctx.effect(
    () =>
      webServer.register({
        kind: 'exact',
        path: '/mobile/devices/rename',
        handler: handleRenameDevice,
      }),
    'dsh-mobile-bridge: /mobile/devices/rename',
  )
  ctx.effect(
    () =>
      webServer.register({
        kind: 'exact',
        path: '/mobile/device/me',
        handler: handleDeviceMe,
      }),
    'dsh-mobile-bridge: /mobile/device/me',
  )
  ctx.effect(
    () =>
      webServer.register({
        kind: 'exact',
        path: '/mobile/token/exchange',
        handler: handleTokenExchange,
      }),
    'dsh-mobile-bridge: /mobile/token/exchange',
  )
}
