/**
 * canary 的行为测试。
 *
 * 为什么它值得一个 DOM shim：canary 平时**什么都不做**。「装了但永远不触发」
 * 和「正常工作」在肉眼上完全一样，直到下一次官方 DOM 漂移才发现它没生效。
 *
 * 做法是照抄真实加载路径：插件是 IIFE，调用 window.__ModuleLoader__.load，
 * 我们接住 factory 拿到 exports.apply，再按 cordis 的方式调用；
 * 同时捕获 setInterval 的回调，就能手动驱动 reconciler。
 *
 * 运行：cd plugins/dsh-mobile-layout && node --test test/
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const SOURCE = readFileSync(join(here, '..', 'lib', 'client.js'), 'utf8')

class ClassList {
  constructor() { this.set = new Set() }
  add(...c) { c.forEach((x) => this.set.add(x)) }
  remove(...c) { c.forEach((x) => this.set.delete(x)) }
  contains(c) { return this.set.has(c) }
  toggle(c, on) { on ? this.set.add(c) : this.set.delete(c) }
}

class El {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase()
    this.children = []
    this.parentElement = null
    this.attrs = {}
    this.dataset = {}
    this.style = { setProperty() {}, getPropertyValue: () => '' }
    this.classList = new ClassList()
    this.textContent = ''
    this.hidden = false
  }
  setAttribute(k, v) { this.attrs[k] = String(v) }
  getAttribute(k) { return k in this.attrs ? this.attrs[k] : null }
  hasAttribute(k) { return k in this.attrs }
  appendChild(c) { c.parentElement = this; this.children.push(c); return c }
  remove() {
    if (this.parentElement) {
      const i = this.parentElement.children.indexOf(this)
      if (i >= 0) this.parentElement.children.splice(i, 1)
      this.parentElement = null
    }
  }
  addEventListener() {}
  querySelector() { return null }
  querySelectorAll() { return [] }
}

function boot({ withColumns = 3 } = {}) {
  const head = new El('head')
  const body = new El('body')
  const frame = new El('div')
  const overlay = new El('div')
  overlay.setAttribute('data-shell-overlay', '')

  const cols = []
  for (let i = 0; i < withColumns; i++) cols.push(frame.appendChild(new El('div')))
  frame.appendChild(overlay)

  const warned = []
  const intervals = []

  const document = {
    head,
    body,
    createElement: (tag) => new El(tag),
    querySelector(sel) {
      if (sel === '[data-shell-overlay]') return overlay.parentElement ? overlay : null
      if (sel.startsWith('style[data-plugin-css=')) {
        return head.children.find((c) => c.dataset.pluginCss === 'dsh-mobile-layout/styles') ?? null
      }
      return null
    },
    querySelectorAll: () => [],
  }
  const win = { __ModuleLoader__: { load(mod) { win.__mod = mod } } }
  const sandbox = { log: () => {}, warn: (...a) => warned.push(a.join(' ')) }

  new Function('window', 'document', 'console', 'setInterval', 'clearInterval', 'setTimeout', SOURCE)(
    win, document, sandbox,
    (cb, ms) => { const id = { cb, ms, cleared: false }; intervals.push(id); return id },
    (id) => { if (id) id.cleared = true },
    (cb) => { cb() },
  )

  // 真实 loader 会调用 factory 并拿它的返回值当 exports —— mod 本身没有 exports 字段
  const exportsObj = win.__mod.factory(() => ({}))

  return {
    exports: exportsObj, head, body, frame, cols, warned, intervals,
    reconcile: () => intervals.filter((i) => i.ms === 250).forEach((i) => i.cb()),
    layout: () => intervals.filter((i) => i.ms === 300).forEach((i) => i.cb()),
    styleTag: () => head.children.find((c) => c.dataset.pluginCss === 'dsh-mobile-layout/styles'),
    clearColumns() { cols.forEach((c) => c.remove()) },
  }
}

const CANARY = Number((SOURCE.match(/CANARY_MISS_LIMIT\s*=\s*(\d+)/) || [])[1])

test('模块按真实方式装载，且 canary 常量存在', () => {
  const h = boot()
  assert.equal(h.exports.name, 'mobile-layout')
  assert.equal(typeof h.exports.apply, 'function')
  assert.ok(Number.isInteger(CANARY) && CANARY > 0, '拿不到 CANARY_MISS_LIMIT')
})

test('健康布局：注入 CSS 并打上三列标记，canary 不触发', () => {
  const h = boot()
  const ctx = { effect: (fn) => { const d = fn(); return d } }
  h.exports.apply(ctx)
  h.layout()          // 让 installLayout 找到 frame 并 setup
  h.reconcile()

  assert.ok(h.styleTag(), '应当注入 CSS')
  const marked = h.frame.children.filter(
    (c) => c.classList.contains('dshml-col-1') || c.classList.contains('dshml-col-2') || c.classList.contains('dshml-col-3'),
  ).length
  assert.equal(marked, 3, '三列都应被打标记')
  assert.equal(h.warned.length, 0, '健康时不该有任何告警')
})

test('冷启动时列还没渲染完，canary 不得误触发', () => {
  // 只有 1 个 flow 列 —— 相当于 shell 挂载了但列还没出来
  const h = boot({ withColumns: 1 })
  h.exports.apply({ effect: (fn) => fn() })
  h.layout()
  for (let i = 0; i < CANARY + 20; i++) h.reconcile()
  assert.equal(h.warned.length, 0, '未健康过就不该触发 —— 否则每次启动都误报')
  assert.ok(h.styleTag(), 'CSS 不应被移除')
})

test('官方 DOM 漂移（列消失）：持续失败后 canary 触发并自我卸载', () => {
  const h = boot()
  h.exports.apply({ effect: (fn) => fn() })
  h.layout()
  h.reconcile()
  assert.equal(h.warned.length, 0, '前置条件：一开始是健康的')

  // 模拟官方改版：flow 列不见了（我们所有 CSS 锚点都不再成立）
  h.clearColumns()
  for (let i = 0; i < CANARY + 2; i++) h.reconcile()

  assert.equal(h.warned.length, 1, '应当恰好告警一次')
  assert.match(h.warned[0], /canary tripped/)
  assert.match(h.warned[0], /reason:/, '要说明原因，便于排查')
  assert.equal(h.styleTag(), undefined, 'CSS 必须被移除 —— fail-safe 而非 fail-broken')
})

test('触发后不再重复告警（幂等）', () => {
  const h = boot()
  h.exports.apply({ effect: (fn) => fn() })
  h.layout()
  h.reconcile()
  h.clearColumns()
  for (let i = 0; i < CANARY * 3; i++) h.reconcile()
  assert.equal(h.warned.length, 1, '告警只应出现一次')
})

test('瞬时抖动不足以触发（有一次布局闪断就自杀是灾难）', () => {
  const h = boot()
  h.exports.apply({ effect: (fn) => fn() })
  h.layout()
  h.reconcile()
  h.clearColumns()
  for (let i = 0; i < Math.floor(CANARY / 2); i++) h.reconcile()
  assert.equal(h.warned.length, 0, '半程不该触发')
  // 恢复健康 → 计数清零
  const frame = h.frame
  h.cols.forEach((c) => frame.appendChild(c))
  for (let i = 0; i < CANARY * 2; i++) h.reconcile()
  assert.equal(h.warned.length, 0, '恢复后必须清零，不能累积')
})
