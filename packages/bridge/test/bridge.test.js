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
  const ctx = {
    webServer: {
      protocol: 'http:',
      port: 53985,
      getOrigin: () => 'http://127.0.0.1:53985',
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
      return () => dispose?.()
    },
    logger: { warn: () => {} },
  }

  await apply(ctx, config)

  return {
    home,
    routes,
    async cleanup() {
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
  const { method = 'GET', body, remoteAddress = '127.0.0.1' } = options
  const chunks = body === undefined ? [] : [Buffer.from(body, 'utf8')]
  return {
    method,
    socket: { remoteAddress },
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
    assert.deepEqual(JSON.parse(response.body), {
      url: 'https://example.test/?token=test-token',
    })
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

test('GET /mobile/pair warns when the base is loopback', async () => {
  const bridge = await createBridge()
  try {
    const route = bridge.routes.get('/mobile/pair')
    const page = makeResponse()
    await route.handler(makeRequest({ remoteAddress: '127.0.0.1' }), page)
    assert.equal(page.status, 200)
    assert.match(page.body, /publicUrl 为空/)
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
