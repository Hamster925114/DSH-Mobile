/**
 * Append-only audit history for device pairing and access.
 *
 * The log deliberately stores no device tokens and no DSH cookies. It records
 * only the event, device identity, actor, and a client address so a lost phone
 * can be investigated without leaking credentials.
 *
 * @module @dsh-mobile/bridge/audit
 */

import { appendFile, mkdir, readFile, rename, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'

const MAX_FILE_BYTES = 2 * 1024 * 1024
const DEFAULT_LIMIT = 100
const MAX_LIMIT = 500

/**
 * Keep an address useful for diagnosis but bounded.
 * @param {unknown} value
 * @returns {string | null}
 */
function cleanAddress(value) {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (trimmed === '') return null
  return trimmed.slice(0, 120)
}

/**
 * Audit log backed by one JSONL file plus one rotated file.
 */
export class AuditLog {
  /**
   * @param {{
   *   directory: string,
   *   file?: string,
   *   maxBytes?: number,
   *   logger?: { warn?: (message: string) => void },
   * }} options
   */
  constructor(options) {
    this.directory = options.directory
    this.file = options.file ?? join(options.directory, 'audit.jsonl')
    this.rotated = `${this.file}.1`
    this.maxBytes = options.maxBytes ?? MAX_FILE_BYTES
    this.logger = options.logger
    this.chain = Promise.resolve()
    /** @type {Map<string, number>} */
    this.deniedAt = new Map()
    /** @type {Map<string, number>} */
    this.loginAt = new Map()
  }

  /**
   * Append one event. Failures are reported but never break pairing or access.
   * @param {{
   *   event: string,
   *   deviceId?: string | null,
   *   deviceName?: string | null,
   *   platform?: string | null,
   *   actor?: string,
   *   remote?: string | null,
   *   detail?: string | null,
   *   expiresAt?: string | null,
   * }} entry
   * @returns {Promise<void>}
   */
  async record(entry) {
    const line = JSON.stringify({
      ts: new Date().toISOString(),
      event: String(entry.event),
      deviceId: entry.deviceId ?? null,
      deviceName: entry.deviceName ?? null,
      platform: entry.platform ?? null,
      actor: entry.actor ?? 'system',
      remote: cleanAddress(entry.remote),
      detail: entry.detail == null ? null : String(entry.detail).slice(0, 500),
      expiresAt: entry.expiresAt ?? null,
    })
    this.chain = this.chain.then(async () => {
      await mkdir(this.directory, { recursive: true })
      try {
        const info = await stat(this.file)
        if (info.size >= this.maxBytes) {
          await rm(this.rotated, { force: true })
          await rename(this.file, this.rotated)
        }
      } catch (error) {
        if (error?.code !== 'ENOENT') throw error
      }
      await appendFile(this.file, `${line}\n`, { encoding: 'utf8', mode: 0o600 })
    })
    try {
      await this.chain
    } catch (error) {
      this.logger?.warn?.(`dsh-mobile-bridge: audit append failed: ${String(error)}`)
      this.chain = Promise.resolve()
    }
  }

  /**
   * Throttle repeated denial events from the same client. This keeps a revoked
   * phone refreshing its WebView from filling the log with identical rows.
   * @param {string} key
   * @param {number} [windowMs]
   * @returns {boolean} true when the caller should record this denial
   */
  shouldRecordDenied(key, windowMs = 60_000) {
    const now = Date.now()
    const previous = this.deniedAt.get(key) ?? 0
    if (now - previous < windowMs) return false
    this.deniedAt.set(key, now)
    if (this.deniedAt.size > 500) {
      for (const [candidate, at] of this.deniedAt) {
        if (now - at > windowMs) this.deniedAt.delete(candidate)
      }
    }
    return true
  }

  /**
   * Throttle login/validation rows so a foreground app that reconnects often
   * does not turn the audit trail into a request log.
   * @param {string} deviceId
   * @param {number} [windowMs]
   * @returns {boolean}
   */
  shouldRecordLogin(deviceId, windowMs = 5 * 60_000) {
    const now = Date.now()
    const previous = this.loginAt.get(deviceId) ?? 0
    if (now - previous < windowMs) return false
    this.loginAt.set(deviceId, now)
    return true
  }

  /**
   * Read newest-first events. Malformed lines are skipped so one partial write
   * cannot make the whole history unreadable.
   * @param {number} [limit]
   * @returns {Promise<object[]>}
   */
  async recent(limit = DEFAULT_LIMIT) {
    const count = Math.max(1, Math.min(MAX_LIMIT, Number.isSafeInteger(limit) ? limit : DEFAULT_LIMIT))
    let raw = ''
    try {
      raw = await readFile(this.file, 'utf8')
    } catch (error) {
      if (error?.code === 'ENOENT') return []
      throw error
    }
    const lines = raw.split('\n').filter((line) => line.trim() !== '')
    const events = []
    for (let index = lines.length - 1; index >= 0 && events.length < count; index -= 1) {
      try {
        const parsed = JSON.parse(lines[index])
        if (parsed !== null && typeof parsed === 'object') events.push(parsed)
      } catch {
        // Ignore a truncated final line from a crash.
      }
    }
    return events
  }
}
