#!/usr/bin/env node
/**
 * M0 auth smoke test.
 *
 * Run this on the desktop after the bridge plugin is installed and DSH has
 * restarted. It performs the same exchange the Android app will perform:
 *
 *   pairing.json -> POST /mobile/pair -> tokenized URL
 *   -> GET tokenized URL (capture browser cookie)
 *   -> POST /api/session/list with the cookie
 *
 * Usage:
 *   node tools/smoke-auth.mjs
 *   DSH_HOME=/path/to/home node tools/smoke-auth.mjs
 */

import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'

const home = process.env.DSH_HOME?.trim() || join(homedir(), '.dsh')
const pairingFile = join(home, 'mobile', 'pairing.json')
const timeout = 15_000

/**
 * @param {string} label
 * @param {() => Promise<Response>} run
 * @returns {Promise<Response>}
 */
async function request(label, run) {
  try {
    return await run()
  } catch (error) {
    const cause = error instanceof Error && 'cause' in error ? error.cause : undefined
    const code = cause && typeof cause === 'object' && 'code' in cause ? String(cause.code) : ''
    const message = error instanceof Error ? error.message : String(error)
    console.error(`[fail] ${label}: ${message}${code ? ` (${code})` : ''}`)
    if (code === 'DEPTH_ZERO_SELF_SIGNED_CERT' || code === 'UNABLE_TO_VERIFY_LEAF_SIGNATURE') {
      console.error('       自签证书：先用受信任证书，或临时设置 NODE_TLS_REJECT_UNAUTHORIZED=0 仅做本地验证。')
    }
    process.exitCode = 1
    throw error
  }
}

/**
 * @param {string} label
 * @param {Response} response
 */
async function expectOk(label, response) {
  if (response.ok) return
  const text = await response.text().catch(() => '')
  console.error(`[fail] ${label}: HTTP ${response.status} ${text.slice(0, 300)}`)
  if (response.status === 401) {
    console.error('       401：Cookie 未生效/已过期；确认 tokenized URL 与 API 使用同一 Host。')
  } else if (response.status === 403) {
    console.error('       403：Host/Origin 被拒绝；配置 connection.trustedHosts，或让反代改写 Host: 127.0.0.1:53985。')
  } else if (response.status === 410) {
    console.error('       410：配对码已过期或已使用；重启 DSH 重新生成。')
  }
  process.exitCode = 1
  throw new Error(`${label} failed with ${String(response.status)}`)
}

/** @param {Headers} headers */
function cookieFrom(headers) {
  const list =
    typeof headers.getSetCookie === 'function'
      ? headers.getSetCookie()
      : [headers.get('set-cookie')].filter((value) => typeof value === 'string' && value !== '')
  return list
    .map((value) => value.split(';', 1)[0]?.trim() ?? '')
    .filter((value) => value !== '')
    .join('; ')
}

async function main() {
  const pairing = JSON.parse(await readFile(pairingFile, 'utf8'))
  console.log(`[ok] pairing file: ${pairingFile}`)
  console.log(`     base=${pairing.base} port=${String(pairing.port)} expires=${pairing.expiresAt}`)

  const helloResponse = await request('GET /mobile/hello', () =>
    fetch(`${pairing.base}/mobile/hello`, { signal: AbortSignal.timeout(timeout) }),
  )
  await expectOk('GET /mobile/hello', helloResponse)
  const hello = await helloResponse.json()
  console.log(`[ok] bridge ${hello.version} (dsh=${String(hello.dsh)})`)

  const pairResponse = await request('POST /mobile/pair', () =>
    fetch(`${pairing.base}/mobile/pair`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: pairing.code }),
      signal: AbortSignal.timeout(timeout),
    }),
  )
  await expectOk('POST /mobile/pair', pairResponse)
  const pair = await pairResponse.json()
  if (typeof pair.url !== 'string') throw new Error('pair response has no url')
  console.log('[ok] pairing code exchanged for a tokenized URL')

  const tokenResponse = await request('GET tokenized URL', () =>
    fetch(pair.url, { redirect: 'manual', signal: AbortSignal.timeout(timeout) }),
  )
  if (tokenResponse.status !== 303 && tokenResponse.status !== 302 && tokenResponse.status !== 200) {
    await expectOk('GET tokenized URL', tokenResponse)
  }
  const cookie = cookieFrom(tokenResponse.headers)
  if (cookie === '') throw new Error('tokenized URL returned no Set-Cookie')
  console.log(`[ok] browser cookie captured (${cookie.split('=', 1)[0]})`)

  const rpcId = randomUUID()
  const apiResponse = await request('POST /api/session/list', () =>
    fetch(`${pairing.base}/api/session/list`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        cookie,
      },
      body: JSON.stringify({
        type: 'client-request',
        rpcId,
        method: 'session/list',
        payload: { args: { _request: {} } },
      }),
      signal: AbortSignal.timeout(timeout),
    }),
  )
  await expectOk('POST /api/session/list', apiResponse)
  const envelope = await apiResponse.json()
  if (envelope?.result?.ok !== true) {
    console.error('[fail] RPC result:', JSON.stringify(envelope).slice(0, 500))
    process.exitCode = 1
    return
  }

  const value = envelope.result.value
  const sessions = Array.isArray(value)
    ? value
    : Array.isArray(value?.sessions)
      ? value.sessions
      : Array.isArray(value?.items)
        ? value.items
        : []
  console.log(`[ok] session/list: ${String(sessions.length)} session(s)`)
  if (sessions.length > 0) {
    const first = sessions[0]
    console.log(`     first: ${String(first?.id ?? first?.sessionId ?? 'unknown')} ${String(first?.title ?? '')}`.trim())
  }
  console.log('[done] M0 auth path works. The Android app can now reuse this flow.')
}

main().catch((error) => {
  console.error('[fatal]', error instanceof Error ? error.message : String(error))
  process.exitCode = 1
})
