/**
 * Host half of @dsh-mobile/client-ui.
 *
 * The actual mobile layout lives in ./client.js and is loaded by the DSH Web
 * client. Keeping the host half empty lets the package be installed in a DSH
 * profile without changing any Host service.
 *
 * @module @dsh-mobile/client-ui
 */

/** Stable Cordis plugin name. */
export const name = 'dsh-mobile-client-ui'

/** No Host services are required. */
export const inject = []

/** No Host-side work. */
export function apply() {}
