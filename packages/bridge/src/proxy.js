/**
 * Device-token gate in front of the DSH web listener.
 *
 * Tailscale Serve (or another tunnel) can point at this loopback listener
 * instead of DSH directly. Every HTTP request and WebSocket upgrade must carry
 * a valid `dsh_mobile_device` cookie; the proxy forwards only approved traffic
 * to the real DSH port. Revoking a device therefore takes effect on the next
 * request instead of waiting for the browser cookie to expire.
 *
 * @module @dsh-mobile/bridge/proxy
 */

import http from 'node:http'
import net from 'node:net'

export const DEVICE_COOKIE = 'dsh_mobile_device'
const MAX_COOKIE_AGE_SECONDS = 30 * 24 * 60 * 60

/**
 * Parse a Cookie header into a plain object.
 * @param {string | undefined} header
 * @returns {Record<string, string>}
 */
function parseCookies(header) {
  const result = {}
  if (typeof header !== 'string') return result
  for (const part of header.split(';')) {
    const at = part.indexOf('=')
    if (at <= 0) continue
    const name = part.slice(0, at).trim()
    if (name === '') continue
    result[name] = part.slice(at + 1).trim()
  }
  return result
}

/**
 * Pull the device token from the query first, then the cookie.
 * @param {URL} url
 * @param {import('node:http').IncomingMessage} req
 * @returns {{ token: string | undefined, fromQuery: boolean }}
 */
function deviceTokenFrom(url, req) {
  const queryToken = url.searchParams.get('device')
  if (typeof queryToken === 'string' && queryToken !== '') {
    return { token: queryToken, fromQuery: true }
  }
  const cookieToken = parseCookies(req.headers.cookie)[DEVICE_COOKIE]
  if (typeof cookieToken === 'string' && cookieToken !== '') {
    return { token: cookieToken, fromQuery: false }
  }
  // Token exchange and device validation may be the very first request after a
  // browser-cookie loss. Accept the bearer here; ordinary SPA requests still
  // need the cookie.
  const authorization = req.headers.authorization
  if (typeof authorization === 'string' && /^Bearer\s+dshm_/i.test(authorization)) {
    return { token: authorization.replace(/^Bearer\s+/i, '').trim(), fromQuery: false }
  }
  const direct = req.headers['x-dsh-device-token']
  if (typeof direct === 'string' && direct.startsWith('dshm_')) {
    return { token: direct.trim(), fromQuery: false }
  }
  return { token: undefined, fromQuery: false }
}

/**
 * Build a Set-Cookie value for a valid device token.
 * @param {string} token
 * @param {boolean} secure
 * @returns {string}
 */
export function deviceCookie(token, secure) {
  return [
    `${DEVICE_COOKIE}=${token}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${String(MAX_COOKIE_AGE_SECONDS)}`,
    ...(secure ? ['Secure'] : []),
  ].join('; ')
}

/**
 * Remove the one-time `device` query parameter before forwarding.
 * @param {URL} url
 * @returns {string}
 */
function forwardedPath(url) {
  const copy = new URL(url.href)
  copy.searchParams.delete('device')
  return `${copy.pathname}${copy.search}`
}

/**
 * Whether the original request arrived over TLS (directly or through Serve).
 * @param {import('node:http').IncomingMessage} req
 * @returns {boolean}
 */
function isSecure(req) {
  return (
    Boolean(req.socket?.encrypted) ||
    String(req.headers['x-forwarded-proto'] ?? '').split(',')[0].trim() === 'https'
  )
}

/**
 * A small denial page for revoked or unpaired clients.
 * @param {import('node:http').ServerResponse} res
 * @param {'revoked' | 'missing'} reason
 */
function deny(res, reason) {
  const message =
    reason === 'revoked'
      ? '这台设备已被撤销，请在电脑上重新生成配对码。'
      : '此设备尚未配对。请在电脑上打开 DSH Mobile 配对页。'
  res.writeHead(401, {
    'content-type': 'text/html; charset=utf-8',
    'cache-control': 'no-store',
  })
  res.end(
    `<!doctype html><html lang="zh-CN"><meta charset="utf-8">` +
      `<title>DSH Mobile</title><body style="font-family:system-ui;max-width:32rem;margin:4rem auto;padding:0 1rem">` +
      `<h2>DSH Mobile 已锁定</h2><p>${message}</p></body></html>`,
  )
}

/**
 * Start the reverse proxy.
 * @param {{
 *   targetHost: string,
 *   targetPort: number,
 *   listenHost?: string,
 *   listenPort: number,
 *   store: import('./devices.js').DeviceStore,
 *   logger?: { warn?: (message: string) => void, info?: (message: string) => void },
 * }} options
 * @returns {Promise<{ close: () => Promise<void>, port: number }>}
 */
export async function startDeviceProxy(options) {
  const targetHost = options.targetHost
  const targetPort = options.targetPort
  const store = options.store

  /**
   * Validate the request and attach the device token.
   * @param {URL} url
   * @param {import('node:http').IncomingMessage} req
   * @returns {Promise<{ device: object, token: string, fromQuery: boolean } | null>}
   */
  async function authorize(url, req) {
    // Pairing and the health handshake must work before this phone has a
    // device cookie. The bridge route itself still enforces the one-time code
    // and loopback-only pairing page.
    if (url.pathname === '/mobile/pair' || url.pathname === '/mobile/hello') {
      return { device: null, token: '', fromQuery: false, public: true }
    }
    const { token, fromQuery } = deviceTokenFrom(url, req)
    if (token === undefined) return null
    const device = await store.verify(token)
    if (device === null) return null
    return { device, token, fromQuery, public: false }
  }

  const server = http.createServer((req, res) => {
    void (async () => {
      let url
      try {
        url = new URL(req.url ?? '/', 'http://dsh-mobile-proxy')
      } catch {
        res.writeHead(400)
        res.end()
        return
      }
      const admitted = await authorize(url, req)
      if (admitted === null) {
        deny(res, 'revoked')
        return
      }

      const headers = { ...req.headers }
      headers.host = req.headers.host ?? `${targetHost}:${String(targetPort)}`
      if (admitted.fromQuery) {
        const cookies = parseCookies(req.headers.cookie)
        cookies[DEVICE_COOKIE] = admitted.token
        headers.cookie = Object.entries(cookies)
          .map(([name, value]) => `${name}=${value}`)
          .join('; ')
      }

      const upstream = http.request(
        {
          host: targetHost,
          port: targetPort,
          method: req.method,
          path: forwardedPath(url),
          headers,
        },
        (upstreamResponse) => {
          const responseHeaders = { ...upstreamResponse.headers }
          const cookies = Array.isArray(upstreamResponse.headers['set-cookie'])
            ? [...upstreamResponse.headers['set-cookie']]
            : []
          // Refresh the device cookie on every admitted non-public exchange.
          // The Android client sets it initially; this also upgrades it to
          // Secure and keeps the browser copy aligned with the token lifetime.
          if (!admitted.public) cookies.push(deviceCookie(admitted.token, isSecure(req)))
          if (cookies.length > 0) responseHeaders['set-cookie'] = cookies
          res.writeHead(upstreamResponse.statusCode ?? 502, responseHeaders)
          upstreamResponse.pipe(res)
        },
      )
      upstream.on('error', (error) => {
        options.logger?.warn?.(`dsh-mobile-bridge: device proxy upstream error: ${String(error)}`)
        if (!res.headersSent) {
          res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' })
          res.end('DSH upstream unavailable')
        } else {
          res.destroy()
        }
      })
      req.pipe(upstream)
    })().catch((error) => {
      options.logger?.warn?.(`dsh-mobile-bridge: device proxy request error: ${String(error)}`)
      if (!res.headersSent) {
        res.writeHead(500)
        res.end()
      } else {
        res.destroy()
      }
    })
  })

  server.on('upgrade', (req, socket, head) => {
    void (async () => {
      let url
      try {
        url = new URL(req.url ?? '/', 'http://dsh-mobile-proxy')
      } catch {
        socket.destroy()
        return
      }
      const admitted = await authorize(url, req)
      if (admitted === null) {
        socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
        socket.destroy()
        return
      }

      const upstream = net.connect({ host: targetHost, port: targetPort }, () => {
        const headers = { ...req.headers }
        headers.host = req.headers.host ?? `${targetHost}:${String(targetPort)}`
        if (admitted.fromQuery) {
          const cookies = parseCookies(req.headers.cookie)
          cookies[DEVICE_COOKIE] = admitted.token
          headers.cookie = Object.entries(cookies)
            .map(([name, value]) => `${name}=${value}`)
            .join('; ')
        }
        const lines = [`${req.method ?? 'GET'} ${forwardedPath(url)} HTTP/1.1`]
        for (const [name, value] of Object.entries(headers)) {
          if (value === undefined) continue
          if (Array.isArray(value)) {
            for (const item of value) lines.push(`${name}: ${item}`)
          } else {
            lines.push(`${name}: ${value}`)
          }
        }
        upstream.write(`${lines.join('\r\n')}\r\n\r\n`)
        if (head.length > 0) upstream.write(head)
        upstream.pipe(socket)
        socket.pipe(upstream)
      })
      upstream.on('error', () => {
        socket.destroy()
      })
      socket.on('error', () => {
        upstream.destroy()
      })
    })().catch((error) => {
      options.logger?.warn?.(`dsh-mobile-bridge: device proxy upgrade error: ${String(error)}`)
      socket.destroy()
    })
  })

  const listenHost = options.listenHost ?? '127.0.0.1'
  await new Promise((resolve, reject) => {
    const onError = (error) => {
      server.off('listening', onListening)
      reject(error)
    }
    const onListening = () => {
      server.off('error', onError)
      resolve()
    }
    server.once('error', onError)
    server.once('listening', onListening)
    server.listen(options.listenPort, listenHost)
  })

  return {
    port: /** @type {import('node:net').AddressInfo} */ (server.address()).port,
    close: () =>
      new Promise((resolve) => {
        server.close(() => resolve())
        server.closeAllConnections?.()
      }),
  }
}
