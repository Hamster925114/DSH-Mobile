import assert from 'node:assert/strict'
import { appendFile, mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { AuditLog } from '../src/audit.js'

async function withAudit(fn) {
  const directory = await mkdtemp(join(tmpdir(), 'dsh-mobile-audit-'))
  try {
    return await fn({ directory, audit: new AuditLog({ directory }) })
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

test('audit records newest-first and never writes token material', async () => {
  await withAudit(async ({ directory, audit }) => {
    await audit.record({
      event: 'pair',
      deviceId: 'dev1',
      deviceName: 'Pixel',
      platform: 'android',
      actor: 'device',
      remote: '100.64.0.2',
      detail: 'pairing code accepted',
    })
    await audit.record({
      event: 'login',
      deviceId: 'dev1',
      deviceName: 'Pixel',
      actor: 'device',
      remote: '100.64.0.2',
      detail: 'token exchange / renew',
    })
    const events = await audit.recent(10)
    assert.equal(events.length, 2)
    assert.equal(events[0].event, 'login')
    assert.equal(events[1].event, 'pair')
    assert.equal(events[0].deviceName, 'Pixel')
    const raw = await readFile(join(directory, 'audit.jsonl'), 'utf8')
    assert.equal(raw.includes('dshm_'), false)
    assert.equal(raw.includes('token='), false)
  })
})

test('audit skips a truncated line and throttles repeated denials', async () => {
  await withAudit(async ({ directory, audit }) => {
    await audit.record({ event: 'pair', deviceId: 'dev1' })
    await audit.record({ event: 'login', deviceId: 'dev1' })
    assert.equal(audit.shouldRecordDenied('100.64.0.2'), true)
    assert.equal(audit.shouldRecordDenied('100.64.0.2'), false)
    assert.equal(audit.shouldRecordLogin('dev1'), true)
    assert.equal(audit.shouldRecordLogin('dev1'), false)
    await appendFile(join(directory, 'audit.jsonl'), '{"event":', 'utf8')
    const events = await audit.recent(10)
    assert.equal(events.length, 2)
  })
})
