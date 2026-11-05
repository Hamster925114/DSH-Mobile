import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { apply } from '../src/index.js'

/**
 * Build a fake Cordis context that captures registered routes.
 * @param {object} [config]
 */
async function createBridge(config = {}) {
  const home = await mkdtemp(join(tmpdir(), 'dsh-mobile-bridge-'))
  const previousHome = process.env.DSH_HOME
  process.env.DSH_HOME = home

  /** @type {Map<string, { kind: string, path: string, handler: Function }>} */
  const routes = new Map()
  /** @type {Array<() => void>} */
  const disposers = []
  const ctx = {
    webServer: {
      protocol: 'http:',
      port: 53985,
      host: config.host ?? '127.0.0.1',
      getOrigin: () => `http://${String(config.host ?? '127.0.0.1')}:53985`,
      register: (route) => {
        routes.set(route.path, route)
        return () => routes.delete(route.path)
      },
    },
    connection: {
      authenticatedUrl: (base) => `${base}?token=test-token`,
    },
    effect: (fn) => {
      const dispose = fn()
      disposers.push(dispose)
      return () => dispose?.()
    },
    logger: { warn: () => {} },
  }

  await apply(ctx, { deviceProxy: false, ...config })

  return {
    home,
    routes,
    async cleanup() {
      for (const dispose of disposers.reverse()) {
        try { dispose?.() } catch {}
      }
      await new Promise((resolve) => setTimeout(resolve, 20))
      await rm(home, { recursive: true, force: true })
      if (previousHome === undefined) delete process.env.DSH_HOME
      else process.env.DSH_HOME = previousHome
    },
  }
}

/**
 * Minimal IncomingMessage stand-in.
 * @param {{ method?: string, body?: string, remoteAddress?: string }} [options]
 */
function makeRequest(options = {}) {
  const {
    method = 'GET',
    body,
    remoteAddress = '127.0.0.1',
    localAddress = remoteAddress,
    headers = {},
  } = options
  const chunks = body === undefined ? [] : [Buffer.from(body, 'utf8')]
  return {
    method,
    headers,
    socket: { remoteAddress, localAddress },
    async *[Symbol.asyncIterator]() {
      yield* chunks
    },
  }
}

/** Minimal ServerResponse stand-in. */
function makeResponse() {
  const response = { status: 0, headers: {}, body: '' }
  response.writeHead = (status, headers = {}) => {
    response.status = status
    response.headers = headers
  }
  response.end = (body = '') => {
    response.body = body
  }
  return response
}

test('GET /mobile/hello returns bridge metadata', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const route = bridge.routes.get('/mobile/hello')
    assert.ok(route, 'hello route registered')
    const response = makeResponse()
    await route.handler(makeRequest(), response)

    assert.equal(response.status, 200)
    const payload = JSON.parse(response.body)
    assert.equal(payload.ok, true)
    assert.equal(payload.name, '@dsh-mobile/bridge')
    assert.equal(payload.port, 53985)
    assert.equal(payload.base, 'https://example.test')
  } finally {
    await bridge.cleanup()
  }
})

test('POST /mobile/pair exchanges a valid one-time code', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const pairing = JSON.parse(await readFile(join(bridge.home, 'mobile', 'pairing.json'), 'utf8'))
    assert.equal(pairing.v, 1)
    assert.equal(pairing.base, 'https://example.test')
    assert.equal(typeof pairing.code, 'string')
    assert.equal(pairing.code.length, 16)

    const route = bridge.routes.get('/mobile/pair')
    const response = makeResponse()
    await route.handler(
      makeRequest({
        method: 'POST',
        body: JSON.stringify({ code: pairing.code }),
      }),
      response,
    )

    assert.equal(response.status, 200)
    const payload = JSON.parse(response.body)
    assert.equal(payload.url, 'https://example.test/?token=test-token')
    assert.equal(payload.base, 'https://example.test')
    assert.match(payload.deviceId, /^[0-9a-f]{12}$/)
    assert.match(payload.deviceToken, /^dshm_[0-9a-f]{12}_[A-Za-z0-9_-]+$/)
    const devicesFile = JSON.parse(await readFile(join(bridge.home, 'mobile', 'devices.json'), 'utf8'))
    assert.equal(devicesFile.devices.length, 1)
    assert.equal(devicesFile.devices[0].tokenHash.length, 64)
    assert.equal(devicesFile.devices[0].token, undefined)
    assert.equal(JSON.stringify(devicesFile).includes(payload.deviceToken), false)
  } finally {
    await bridge.cleanup()
  }
})

test('POST /mobile/pair rejects a wrong code', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const route = bridge.routes.get('/mobile/pair')
    const response = makeResponse()
    await route.handler(
      makeRequest({
        method: 'POST',
        body: JSON.stringify({ code: '0000000000000000' }),
      }),
      response,
    )

    assert.equal(response.status, 403)
    assert.equal(JSON.parse(response.body).error, 'bad-code')
  } finally {
    await bridge.cleanup()
  }
})

test('POST /mobile/pair burns the code after one use', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const pairing = JSON.parse(await readFile(join(bridge.home, 'mobile', 'pairing.json'), 'utf8'))
    const route = bridge.routes.get('/mobile/pair')

    const first = makeResponse()
    await route.handler(
      makeRequest({ method: 'POST', body: JSON.stringify({ code: pairing.code }) }),
      first,
    )
    assert.equal(first.status, 200)

    const second = makeResponse()
    await route.handler(
      makeRequest({ method: 'POST', body: JSON.stringify({ code: pairing.code }) }),
      second,
    )
    assert.equal(second.status, 410)
    assert.equal(JSON.parse(second.body).error, 'pairing-expired')
  } finally {
    await bridge.cleanup()
  }
})

test('GET /mobile/pair rotates after the code was consumed', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const pairingFile = join(bridge.home, 'mobile', 'pairing.json')
    const first = JSON.parse(await readFile(pairingFile, 'utf8'))
    const route = bridge.routes.get('/mobile/pair')

    const paired = makeResponse()
    await route.handler(
      makeRequest({ method: 'POST', body: JSON.stringify({ code: first.code }) }),
      paired,
    )
    assert.equal(paired.status, 200)

    const page = makeResponse()
    await route.handler(makeRequest({ remoteAddress: '127.0.0.1' }), page)
    assert.equal(page.status, 200)

    const second = JSON.parse(await readFile(pairingFile, 'utf8'))
    assert.notEqual(second.code, first.code)
  } finally {
    await bridge.cleanup()
  }
})

test('GET /mobile/pair renders the code only over loopback', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const route = bridge.routes.get('/mobile/pair')

    const local = makeResponse()
    await route.handler(makeRequest({ remoteAddress: '127.0.0.1' }), local)
    assert.equal(local.status, 200)
    assert.match(local.body, /DSH Mobile 配对/)
    assert.match(local.body, /<svg/)

    const remote = makeResponse()
    await route.handler(makeRequest({ remoteAddress: '203.0.113.7' }), remote)
    assert.equal(remote.status, 404)
  } finally {
    await bridge.cleanup()
  }
})

test('GET /mobile/pair allows a local browser on a LAN bind', async () => {
  const bridge = await createBridge({ host: '192.168.1.10' })
  try {
    const route = bridge.routes.get('/mobile/pair')
    const local = makeResponse()
    await route.handler(
      makeRequest({
        remoteAddress: '192.168.1.10',
        localAddress: '192.168.1.10',
        headers: { host: '192.168.1.10:53985' },
      }),
      local,
    )
    assert.equal(local.status, 200)
    assert.match(local.body, /192\.168\.1\.10:53985/)

    const phone = makeResponse()
    await route.handler(
      makeRequest({
        remoteAddress: '192.168.1.20',
        localAddress: '192.168.1.10',
        headers: { host: '192.168.1.10:53985' },
      }),
      phone,
    )
    assert.equal(phone.status, 404)
  } finally {
    await bridge.cleanup()
  }
})

test('GET /mobile/pair rejects a public proxy Host', async () => {
  const bridge = await createBridge({ publicUrl: 'https://dsh.example.com' })
  try {
    const route = bridge.routes.get('/mobile/pair')
    const response = makeResponse()
    await route.handler(
      makeRequest({
        remoteAddress: '127.0.0.1',
        localAddress: '127.0.0.1',
        headers: { host: 'dsh.example.com' },
      }),
      response,
    )
    assert.equal(response.status, 404)
  } finally {
    await bridge.cleanup()
  }
})

test('GET /mobile/pair rejects a forwarded proxy request', async () => {
  const bridge = await createBridge()
  try {
    const route = bridge.routes.get('/mobile/pair')
    const response = makeResponse()
    await route.handler(
      makeRequest({
        remoteAddress: '127.0.0.1',
        localAddress: '127.0.0.1',
        headers: {
          host: '127.0.0.1:53985',
          'x-forwarded-for': '203.0.113.9',
        },
      }),
      response,
    )
    assert.equal(response.status, 404)
  } finally {
    await bridge.cleanup()
  }
})

test('GET /mobile/pair warns when the base is loopback', async () => {
  const bridge = await createBridge()
  try {
    const route = bridge.routes.get('/mobile/pair')
    const page = makeResponse()
    await route.handler(makeRequest({ remoteAddress: '127.0.0.1' }), page)
    assert.equal(page.status, 200)
    assert.match(page.body, /没有手机可达的地址/)
  } finally {
    await bridge.cleanup()
  }
})

test('GET /mobile/pair lists every configured endpoint', async () => {
  const bridge = await createBridge({
    endpoints: [
      { id: 'lan', label: '局域网', url: 'http://192.168.1.10:53985', kind: 'lan' },
      { id: 'tailscale', label: 'Tailscale', url: 'https://phone.example.ts.net', kind: 'tailscale' },
    ],
  })
  try {
    const pairing = JSON.parse(await readFile(join(bridge.home, 'mobile', 'pairing.json'), 'utf8'))
    assert.deepEqual(
      pairing.endpoints.map((endpoint) => endpoint.id),
      ['lan', 'tailscale'],
    )
    assert.equal(pairing.base, 'http://192.168.1.10:53985')

    const route = bridge.routes.get('/mobile/pair')
    const page = makeResponse()
    await route.handler(makeRequest({ remoteAddress: '127.0.0.1' }), page)
    assert.equal(page.status, 200)
    assert.match(page.body, /局域网/)
    assert.match(page.body, /Tailscale/)
    assert.equal((page.body.match(/<svg/g) ?? []).length, 2)
  } finally {
    await bridge.cleanup()
  }
})

test('POST /mobile/pair can select a configured endpoint', async () => {
  const bridge = await createBridge({
    endpoints: [
      { id: 'lan', label: '局域网', url: 'http://192.168.1.10:53985', kind: 'lan' },
      { id: 'tailscale', label: 'Tailscale', url: 'https://phone.example.ts.net', kind: 'tailscale' },
    ],
  })
  try {
    const pairing = JSON.parse(await readFile(join(bridge.home, 'mobile', 'pairing.json'), 'utf8'))
    const route = bridge.routes.get('/mobile/pair')

    const response = makeResponse()
    await route.handler(
      makeRequest({
        method: 'POST',
        body: JSON.stringify({ code: pairing.code, base: 'https://phone.example.ts.net' }),
      }),
      response,
    )
    assert.equal(response.status, 200)
    const payload = JSON.parse(response.body)
    assert.equal(payload.url, 'https://phone.example.ts.net/?token=test-token')
    assert.equal(payload.base, 'https://phone.example.ts.net')
    assert.match(payload.deviceToken, /^dshm_/)
  } finally {
    await bridge.cleanup()
  }
})

test('POST /mobile/pair rejects an endpoint outside the allowlist', async () => {
  const bridge = await createBridge({
    endpoints: [{ id: 'lan', label: '局域网', url: 'http://192.168.1.10:53985' }],
  })
  try {
    const pairing = JSON.parse(await readFile(join(bridge.home, 'mobile', 'pairing.json'), 'utf8'))
    const route = bridge.routes.get('/mobile/pair')
    const response = makeResponse()
    await route.handler(
      makeRequest({
        method: 'POST',
        body: JSON.stringify({ code: pairing.code, base: 'https://evil.example' }),
      }),
      response,
    )
    assert.equal(response.status, 400)
    assert.equal(JSON.parse(response.body).error, 'unknown-base')
  } finally {
    await bridge.cleanup()
  }
})

test('POST /mobile/pair rate-limits repeated attempts', async () => {
  const bridge = await createBridge({
    publicUrl: 'https://example.test',
    maxAttemptsPerMinute: 2,
  })
  try {
    const route = bridge.routes.get('/mobile/pair')
    for (let index = 0; index < 2; index += 1) {
      const response = makeResponse()
      await route.handler(
        makeRequest({ method: 'POST', body: JSON.stringify({ code: 'BAD' }) }),
        response,
      )
      assert.equal(response.status, 403)
    }

    const limited = makeResponse()
    await route.handler(
      makeRequest({ method: 'POST', body: JSON.stringify({ code: 'BAD' }) }),
      limited,
    )
    assert.equal(limited.status, 429)
  } finally {
    await bridge.cleanup()
  }
})

test('device token can refresh a browser session and is revoked immediately', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const pairing = JSON.parse(await readFile(join(bridge.home, 'mobile', 'pairing.json'), 'utf8'))
    const pairRoute = bridge.routes.get('/mobile/pair')
    const paired = makeResponse()
    await pairRoute.handler(
      makeRequest({
        method: 'POST',
        body: JSON.stringify({ code: pairing.code, deviceName: 'Pixel 9', appVersion: '0.1.0' }),
      }),
      paired,
    )
    assert.equal(paired.status, 200)
    const issued = JSON.parse(paired.body)

    const exchangeRoute = bridge.routes.get('/mobile/token/exchange')
    const refreshed = makeResponse()
    await exchangeRoute.handler(
      makeRequest({
        method: 'POST',
        headers: { authorization: `Bearer ${issued.deviceToken}` },
        body: JSON.stringify({ base: 'https://example.test' }),
      }),
      refreshed,
    )
    assert.equal(refreshed.status, 200)
    assert.equal(JSON.parse(refreshed.body).url, 'https://example.test/?token=test-token')

    const listRoute = bridge.routes.get('/mobile/devices')
    const list = makeResponse()
    await listRoute.handler(makeRequest(), list)
    assert.equal(list.status, 200)
    const listed = JSON.parse(list.body)
    assert.equal(listed.devices.length, 1)
    assert.equal(listed.devices[0].name, 'Pixel 9')
    assert.equal(listed.proxyEnabled, false)

    const revokeRoute = bridge.routes.get('/mobile/devices/revoke')
    const revoked = makeResponse()
    await revokeRoute.handler(
      makeRequest({ method: 'POST', body: JSON.stringify({ id: issued.deviceId }) }),
      revoked,
    )
    assert.equal(revoked.status, 200)

    const rejected = makeResponse()
    await exchangeRoute.handler(
      makeRequest({
        method: 'POST',
        headers: { authorization: `Bearer ${issued.deviceToken}` },
        body: JSON.stringify({ base: 'https://example.test' }),
      }),
      rejected,
    )
    assert.equal(rejected.status, 401)
    assert.equal(JSON.parse(rejected.body).error, 'device-invalid')
  } finally {
    await bridge.cleanup()
  }
})

test('device list rejects a remote caller without a DSH owner cookie', async () => {
  const bridge = await createBridge({ publicUrl: 'https://example.test' })
  try {
    const route = bridge.routes.get('/mobile/devices')
    const response = makeResponse()
    await route.handler(makeRequest({ remoteAddress: '203.0.113.7' }), response)
    assert.equal(response.status, 403)
  } finally {
    await bridge.cleanup()
  }
})
