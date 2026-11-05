/**
 * Persistent device registry for DSH Mobile.
 *
 * Only a SHA-256 digest of a device token is written to disk. The raw token is
 * returned exactly once at pairing time and is then held by the Android
 * Keystore. Revocation is a server-side lookup failure, so a lost phone can be
 * cut off without rotating the desktop browser credential.
 *
 * @module @dsh-mobile/bridge/devices
 */

import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { chmod, mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const FORMAT_VERSION = 1
const TOKEN_PREFIX = 'dshm'
const MAX_NAME_LENGTH = 80
const MAX_APP_VERSION_LENGTH = 40

/**
 * Hash a raw device token.
 * @param {string} token
 * @returns {string}
 */
export function hashToken(token) {
  return createHash('sha256').update(token, 'utf8').digest('hex')
}

/**
 * Make a display name safe for the desktop list and logs.
 * @param {unknown} value
 * @param {string} fallback
 * @returns {string}
 */
function cleanLabel(value, fallback) {
  if (typeof value !== 'string') return fallback
  const cleaned = value
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return cleaned === '' ? fallback : cleaned.slice(0, MAX_NAME_LENGTH)
}

/**
 * Constant-time digest comparison.
 * @param {string} left
 * @param {string} right
 * @returns {boolean}
 */
function equalHash(left, right) {
  const a = Buffer.from(left, 'hex')
  const b = Buffer.from(right, 'hex')
  return a.length === b.length && timingSafeEqual(a, b)
}

/**
 * Strip secret material before sending a device to a client.
 * @param {object} device
 * @returns {object}
 */
function publicDevice(device) {
  return {
    id: device.id,
    name: device.name,
    platform: device.platform,
    appVersion: device.appVersion,
    createdAt: device.createdAt,
    lastSeenAt: device.lastSeenAt,
    expiresAt: device.expiresAt,
    revokedAt: device.revokedAt,
    revoked: device.revokedAt !== null && device.revokedAt !== undefined,
  }
}

/**
 * Device registry backed by one JSON file.
 */
export class DeviceStore {
  /**
   * @param {{
   *   directory: string,
   *   file?: string,
   *   ttlDays?: number,
   *   maxDevices?: number,
   *   now?: () => number,
   *   logger?: { warn?: (message: string) => void },
   * }} options
   */
  constructor(options) {
    this.directory = options.directory
    this.file = options.file ?? join(options.directory, 'devices.json')
    this.ttlMilliseconds = Math.max(1, options.ttlDays ?? 30) * 24 * 60 * 60 * 1000
    this.maxDevices = Math.max(1, options.maxDevices ?? 50)
    this.now = options.now ?? Date.now
    this.logger = options.logger
    /** @type {Map<string, object>} */
    this.byId = new Map()
    /** @type {Map<string, string>} */
    this.byHash = new Map()
    this.loaded = false
    this.dirty = false
    this.writeChain = Promise.resolve()
  }

  /**
   * Read the registry from disk. A missing file is an empty registry; malformed
   * JSON is a hard error so a corrupt file cannot silently un-revoke a device.
   * @returns {Promise<void>}
   */
  async load() {
    if (this.loaded) return
    let raw
    try {
      raw = await readFile(this.file, 'utf8')
    } catch (error) {
      if (error?.code === 'ENOENT') {
        this.loaded = true
        return
      }
      throw error
    }
    let parsed
    try {
      parsed = JSON.parse(raw)
    } catch {
      throw new Error(`dsh-mobile-bridge: devices file is not valid JSON: ${this.file}`)
    }
    if (!parsed || parsed.v !== FORMAT_VERSION || !Array.isArray(parsed.devices)) {
      throw new Error(`dsh-mobile-bridge: unsupported devices file format: ${this.file}`)
    }
    for (const entry of parsed.devices) {
      if (
        entry === null ||
        typeof entry !== 'object' ||
        typeof entry.id !== 'string' ||
        typeof entry.tokenHash !== 'string'
      ) {
        continue
      }
      const device = {
        id: entry.id,
        name: cleanLabel(entry.name, 'Android 设备'),
        platform: cleanLabel(entry.platform, 'android'),
        appVersion: cleanLabel(entry.appVersion, ''),
        tokenHash: entry.tokenHash,
        createdAt: typeof entry.createdAt === 'string' ? entry.createdAt : new Date(this.now()).toISOString(),
        lastSeenAt: typeof entry.lastSeenAt === 'string' ? entry.lastSeenAt : null,
        expiresAt:
          typeof entry.expiresAt === 'string'
            ? entry.expiresAt
            : new Date(this.now() + this.ttlMilliseconds).toISOString(),
        revokedAt: typeof entry.revokedAt === 'string' ? entry.revokedAt : null,
      }
      this.byId.set(device.id, device)
      this.byHash.set(device.tokenHash, device.id)
    }
    this.loaded = true
  }

  /**
   * Register a newly paired device and return its one-time raw token.
   * @param {{ name?: unknown, platform?: unknown, appVersion?: unknown }} fields
   * @returns {Promise<{ token: string, device: object }>}
   */
  async register(fields = {}) {
    await this.load()
    const active = [...this.byId.values()].filter(
      (device) => device.revokedAt === null && Date.parse(device.expiresAt) > this.now(),
    )
    if (active.length >= this.maxDevices) {
      throw new Error('device-limit')
    }
    const id = randomBytes(6).toString('hex')
    const secret = randomBytes(32).toString('base64url')
    const token = `${TOKEN_PREFIX}_${id}_${secret}`
    const now = new Date(this.now()).toISOString()
    const device = {
      id,
      name: cleanLabel(fields.name, 'Android 设备'),
      platform: cleanLabel(fields.platform, 'android'),
      appVersion: cleanLabel(fields.appVersion, '').slice(0, MAX_APP_VERSION_LENGTH),
      tokenHash: hashToken(token),
      createdAt: now,
      lastSeenAt: now,
      expiresAt: new Date(this.now() + this.ttlMilliseconds).toISOString(),
      revokedAt: null,
    }
    this.byId.set(id, device)
    this.byHash.set(device.tokenHash, id)
    this.dirty = true
    await this.flush()
    return { token, device: publicDevice(device) }
  }

  /**
   * Resolve a raw bearer token. Invalid, expired, or revoked tokens all return
   * null so callers cannot distinguish the failure modes.
   * @param {string | undefined | null} token
   * @returns {Promise<object | null>}
   */
  async verify(token) {
    if (typeof token !== 'string' || token.length < 20) return null
    if (!token.startsWith(`${TOKEN_PREFIX}_`)) return null
    await this.load()
    const digest = hashToken(token)
    const id = this.byHash.get(digest)
    if (id === undefined) return null
    const device = this.byId.get(id)
    if (device === undefined) return null
    if (!equalHash(device.tokenHash, digest)) return null
    if (device.revokedAt !== null) return null
    if (Date.parse(device.expiresAt) <= this.now()) return null
    device.lastSeenAt = new Date(this.now()).toISOString()
    this.dirty = true
    return publicDevice(device)
  }

  /**
   * Extend a valid token's lifetime and persist the new last-seen timestamp.
   * @param {string} id
   * @returns {Promise<object | null>}
   */
  async renew(id) {
    await this.load()
    const device = this.byId.get(id)
    if (device === undefined || device.revokedAt !== null) return null
    const now = this.now()
    device.lastSeenAt = new Date(now).toISOString()
    device.expiresAt = new Date(now + this.ttlMilliseconds).toISOString()
    this.dirty = true
    await this.flush()
    return publicDevice(device)
  }

  /**
   * Mark one device revoked. The raw token is never needed for this operation.
   * @param {string} id
   * @returns {Promise<boolean>}
   */
  async revoke(id) {
    await this.load()
    const device = this.byId.get(id)
    if (device === undefined || device.revokedAt !== null) return false
    device.revokedAt = new Date(this.now()).toISOString()
    this.dirty = true
    await this.flush()
    return true
  }

  /**
   * Rename a device; revocation is unaffected.
   * @param {string} id
   * @param {unknown} name
   * @returns {Promise<object | null>}
   */
  async rename(id, name) {
    await this.load()
    const device = this.byId.get(id)
    if (device === undefined) return null
    device.name = cleanLabel(name, device.name)
    this.dirty = true
    await this.flush()
    return publicDevice(device)
  }

  /**
   * List every device, newest first. Revoked records stay for audit.
   * @returns {Promise<object[]>}
   */
  async list() {
    await this.load()
    return [...this.byId.values()]
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
      .map(publicDevice)
  }

  /**
   * Flush pending last-seen updates. Concurrent callers share one write.
   * @returns {Promise<void>}
   */
  async flush() {
    if (!this.loaded) return
    this.writeChain = this.writeChain.then(async () => {
      if (!this.dirty) return
      this.dirty = false
      const payload = {
        v: FORMAT_VERSION,
        devices: [...this.byId.values()].map((device) => ({
          id: device.id,
          name: device.name,
          platform: device.platform,
          appVersion: device.appVersion,
          tokenHash: device.tokenHash,
          createdAt: device.createdAt,
          lastSeenAt: device.lastSeenAt,
          expiresAt: device.expiresAt,
          revokedAt: device.revokedAt,
        })),
      }
      await mkdir(this.directory, { recursive: true })
      const temporary = `${this.file}.${String(process.pid)}.tmp`
      await writeFile(temporary, `${JSON.stringify(payload, null, 2)}\n`, {
        encoding: 'utf8',
        mode: 0o600,
      })
      await rename(temporary, this.file)
      await chmod(this.file, 0o600).catch(() => {})
    })
    await this.writeChain
  }
}
