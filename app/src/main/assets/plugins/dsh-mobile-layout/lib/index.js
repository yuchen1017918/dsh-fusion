// Host half: no host-side behavior — the whole layout overlay lives in the
// client half (lib/client.js), which injects styles and DOM into the running
// WebUI. Kept as a bundle row so cordis loads the package and mounts the
// client bundle through the modules registry. Must be REAL ESM (the cordis
// loader resolves named exports via import()): a CJS-style module.exports in
// a type:module package yields no named exports and the plugin silently
// never mounts.
export const name = 'mobile-layout'
export const inject = []
export function apply(ctx) {
  console.log('[dsh-mobile-layout] host loaded')
}
export default { name, inject, apply }
