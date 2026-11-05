import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import test from 'node:test'

import { startDeviceProxy } from '../src/proxy.js'

/**
 * Start an upstream echo server and a proxy in front of it.
 * @param {(token: string) => boolean} valid
 */
async function createFixture(valid = (token) => token === 'good-token') {
  const upstream = http.createServer((req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ url: req.url, cookie: req.headers.cookie ?? '' }))
  })
  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve))
  const upstreamPort = upstream.address().port
  const proxy = await startDeviceProxy({
    targetHost: '127.0.0.1',
    targetPort: upstreamPort,
    listenPort: 0,
    store: {
      verify: async (token) => (token !== undefined && valid(token) ? { id: 'dev1' } : null),
    },
  })
  return {
    proxy,
    upstream,
    async cleanup() {
      await proxy.close()
      await new Promise((resolve) => upstream.close(resolve))
    },
  }
}

test('proxy rejects a request without a device token', async () => {
  const fixture = await createFixture()
  try {
    const response = await fetch(`http://127.0.0.1:${fixture.proxy.port}/`)
    assert.equal(response.status, 401)
  } finally {
    await fixture.cleanup()
  }
})

test('proxy leaves pairing and hello paths public for first contact', async () => {
  const fixture = await createFixture()
  try {
    for (const path of ['/mobile/pair', '/mobile/hello']) {
      const response = await fetch(`http://127.0.0.1:${fixture.proxy.port}${path}`)
      assert.equal(response.status, 200)
      assert.equal(response.headers.get('set-cookie'), null)
    }
  } finally {
    await fixture.cleanup()
  }
})

test('proxy admits a valid cookie and refreshes the cookie', async () => {
  const fixture = await createFixture()
  try {
    const response = await fetch(`http://127.0.0.1:${fixture.proxy.port}/api/hello?x=1`, {
      headers: { cookie: 'dsh_mobile_device=good-token' },
    })
    assert.equal(response.status, 200)
    const body = await response.json()
    assert.equal(body.url, '/api/hello?x=1')
    assert.match(body.cookie, /dsh_mobile_device=good-token/)
    const setCookie = response.headers.get('set-cookie') ?? ''
    assert.match(setCookie, /dsh_mobile_device=good-token/)
    assert.match(setCookie, /HttpOnly/)
  } finally {
    await fixture.cleanup()
  }
})

test('proxy preserves DSH plugin-bundle query syntax', async () => {
  const fixture = await createFixture()
  try {
    const path =
      '/plugins/??@deepseek-ai/dsh-api-gateway/client.js,@deepseek-ai/dsh-client-ui-theme/client.js&rev=abc'
    const response = await fetch(`http://127.0.0.1:${fixture.proxy.port}${path}`, {
      headers: { cookie: 'dsh_mobile_device=good-token' },
    })
    assert.equal(response.status, 200)
    assert.equal((await response.json()).url, path)
  } finally {
    await fixture.cleanup()
  }
})

test('proxy accepts a one-time device query and strips it upstream', async () => {
  const fixture = await createFixture()
  try {
    const response = await fetch(
      `http://127.0.0.1:${fixture.proxy.port}/path?device=good-token&keep=1`,
    )
    assert.equal(response.status, 200)
    const body = await response.json()
    assert.equal(body.url, '/path?keep=1')
    assert.match(body.cookie, /dsh_mobile_device=good-token/)
  } finally {
    await fixture.cleanup()
  }
})

test('proxy rejects a revoked token immediately', async () => {
  let allowed = true
  const fixture = await createFixture(() => allowed)
  try {
    const first = await fetch(`http://127.0.0.1:${fixture.proxy.port}/`, {
      headers: { cookie: 'dsh_mobile_device=tok' },
    })
    assert.equal(first.status, 200)
    allowed = false
    const second = await fetch(`http://127.0.0.1:${fixture.proxy.port}/`, {
      headers: { cookie: 'dsh_mobile_device=tok' },
    })
    assert.equal(second.status, 401)
  } finally {
    await fixture.cleanup()
  }
})

test('proxy gates WebSocket upgrades too', async () => {
  const upstream = http.createServer()
  upstream.on('upgrade', (_req, socket) => {
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n',
    )
    socket.end()
  })
  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve))
  const proxy = await startDeviceProxy({
    targetHost: '127.0.0.1',
    targetPort: upstream.address().port,
    listenPort: 0,
    store: {
      verify: async (token) => (token === 'ws-token' ? { id: 'ws' } : null),
    },
  })
  try {
    const status = await new Promise((resolve, reject) => {
      const socket = net.connect(proxy.port, '127.0.0.1', () => {
        socket.write(
          'GET /api/remote.mux HTTP/1.1\r\n' +
            `Host: 127.0.0.1:${proxy.port}\r\n` +
            'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
            'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n' +
            'Sec-WebSocket-Version: 13\r\n' +
            'Cookie: dsh_mobile_device=ws-token\r\n\r\n',
        )
      })
      let data = ''
      socket.on('data', (chunk) => {
        data += chunk.toString()
        if (data.includes('\r\n')) {
          socket.destroy()
          resolve(Number(data.slice(9, 12)))
        }
      })
      socket.on('error', reject)
    })
    assert.equal(status, 101)
  } finally {
    await proxy.close()
    await new Promise((resolve) => upstream.close(resolve))
  }
})
