const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const compiler = require('vue/compiler-sfc')

const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function evaluate(source, imports, extra = {}) {
  imports = { vue, ...imports }
  const module = { exports: {} }
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  vm.runInNewContext(js, { ...vue, module, exports: module.exports, require: (name) => {
    assert.ok(Object.hasOwn(imports, name), `Unexpected import ${name}`)
    return imports[name]
  }, ...extra })
  return module.exports
}
function rules(name) {
  return evaluate(fs.readFileSync(path.join(__dirname, name), 'utf8'), {})
}
function page(file, imports) {
  const filename = path.join(__dirname, file)
  const { descriptor } = compiler.parse(fs.readFileSync(filename, 'utf8'), { filename })
  const hooks = {}, revoked = [], created = []
  const source = compiler.compileScript(descriptor, { id: 'lifecycle-regression' }).content
  const lifecycle = Object.fromEntries(['onMounted', 'onActivated', 'onDeactivated', 'onBeforeUnmount', 'onUnmounted'].map((name) => [name, (callback) => { hooks[name] = callback }]))
  const component = evaluate(source, imports, {
    ...lifecycle, useRoute: () => ({ query: {} }), useMessage: () => ({ error() {}, warning() {}, success() {} }),
    setTimeout: () => 1, clearTimeout() {}, Blob,
    URL: { createObjectURL: () => { const url = `blob:${created.length}`; created.push(url); return url }, revokeObjectURL: (url) => revoked.push(url) }
  })
  return { state: component.default.setup({}, { expose() {} }), hooks, revoked, created }
}
const record = (id) => ({ id, wxMemberId: 7, images: '[1]', reviewStatus: 0, status: 0, prescribedItems: '[{"drugId":1,"qty":1}]' })
function review(api) {
  return page('prescription/review.vue', {
    '@/api/pharmacy/prescription/prescRecord': { PrescRecordApi: api },
    '@/utils/permission': { checkPermi: () => true }, '@/utils/formatTime': { formatDate: () => '' },
    './review-flow': { ...rules('prescription/review-flow.ts'), materialSummary: () => ({ ok: true }), privateMaterialIds: () => [1] }
  })
}

for (const hook of ['onBeforeUnmount', 'onDeactivated']) {
  test(`open prescription releases URLs on ${hook}, late material cannot return`, async () => {
    const opened = review({ getPrescRecord: async (id) => record(id), getPrivateMaterial: async () => new Blob(['x'], { type: 'image/png' }) })
    await opened.state.openReview({ id: 1 })
    assert.equal(opened.state.materialUrls.value.length, 1)
    assert.equal(opened.revoked.length, 0)
    opened.hooks[hook]()
    assert.equal(opened.state.materialUrls.value.length, 0)
    assert.deepEqual(opened.revoked, ['blob:0'])
    const late = deferred()
    let calls = 0
    const p = review({ getPrescRecord: async (id) => record(id), getPrivateMaterial: () => ++calls === 1 ? Promise.resolve(new Blob(['x'], { type: 'image/png' })) : late.promise })
    await p.state.openReview({ id: 1 })
    assert.equal(p.state.reviewVisible.value, true)
    const second = p.state.openReview({ id: 2 })
    await Promise.resolve()
    ;(p.hooks[hook] || p.hooks.onUnmounted)()
    late.resolve(new Blob(['late'], { type: 'image/png' }))
    await second
    assert.equal(p.state.reviewVisible.value, false)
    assert.equal(p.state.materialUrls.value.length, 0)
    assert.deepEqual(p.revoked, ['blob:0'])
  })
}
test('old dialog closed animation cannot clear newly opened prescription', async () => {
  const p = review({ getPrescRecord: async (id) => record(id), getPrivateMaterial: async () => new Blob(['x'], { type: 'image/png' }) })
  await p.state.openReview({ id: 1 })
  p.state.reviewVisible.value = false
  await p.state.openReview({ id: 2 })
  p.state.finishReview()
  assert.equal(p.state.reviewTarget.value.id, 2)
  assert.equal(p.state.materialUrls.value.length, 1)
})
for (const outcome of ['resolve', 'reject']) {
  test(`identity reset prevents late consultation ${outcome} from touching a new retry`, async () => {
    const r = rules('consultation/reception.ts'), late = deferred()
    const p = page('consultation/index.vue', { './reception': r, '@/api/pharmacy/consultation': {
      messages: async (id) => ({ conversation: { id, mine: true }, list: [], hasMore: false }), send: () => late.promise
    } })
    await p.state.open({ id: 8, mine: true })
    p.state.input.value = 'old identity body'
    const sending = p.state.reply()
    r.pendingSends.reset()
    const next = { conversationId: 8, clientRequestId: 'new_identity_request', content: 'new body' }
    r.pendingSends.remember(next)
    outcome === 'resolve' ? late.resolve() : late.reject(new Error('late'))
    await sending
    assert.equal(r.pendingSends.get(8)?.clientRequestId, next.clientRequestId)
    assert.equal(p.state.input.value, '')
  })
}

test('same identity keeps retry across route departure; user or tenant changes clear it', async () => {
  const r = rules('consultation/reception.ts')
  r.pendingSends.bindIdentity(1, 901, undefined)
  const p = page('consultation/index.vue', { './reception': r, '@/api/pharmacy/consultation': {
    messages: async (id) => ({ conversation: { id, mine: true }, list: [], hasMore: false }),
    send: async () => { throw new Error('uncertain') }
  } })
  await p.state.open({ id: 8, mine: true })
  p.state.input.value = 'retry body'
  await p.state.reply()
  const first = r.pendingSends.get(8)
  p.hooks.onDeactivated()
  r.pendingSends.bindIdentity(1, 901, undefined)
  await p.state.open({ id: 8, mine: true })
  assert.equal(p.state.failed.value.clientRequestId, first.clientRequestId)
  assert.equal(p.state.input.value, 'retry body')
  for (const identity of [[2, 901, undefined], [2, 902, undefined], [2, 902, 903]]) {
    r.pendingSends.bindIdentity(...identity)
    assert.equal(r.pendingSends.get(8), undefined)
    assert.equal(p.state.conversation.value, undefined)
    r.pendingSends.remember(first)
  }
})

test('actual auth and user store paths clear retry on token removal, reset and failed logout', async () => {
  const r = rules('consultation/reception.ts'), storage = new Map()
  const cache = { CACHE_KEY: { USER: 'USER', TenantId: 'tenant', VisitTenantId: 'visit' }, useCache: () => ({ wsCache: {
    get: (key) => storage.get(key), set: (key, value) => storage.set(key, value), delete: (key) => storage.delete(key)
  } }), deleteUserCache() {} }
  const auth = evaluate(fs.readFileSync(path.join(__dirname, '../../utils/auth.ts'), 'utf8'), {
    '@/hooks/web/useCache': cache, '@/utils/jsencrypt': { encrypt: (x) => x, decrypt: (x) => x },
    '@/views/pharmacy/consultation/reception': r
  })
  let options
  evaluate(fs.readFileSync(path.join(__dirname, '../../store/modules/user.ts'), 'utf8'), {
    '@/store': { store: {} }, pinia: { defineStore: (_, value) => { options = value; return () => ({}) } },
    '@/utils/auth': auth, '@/hooks/web/useCache': cache, '@/api/login': { loginOut: async () => { throw new Error('offline') } },
    './aiAssistant': { useAiAssistantStoreWithOut: () => ({ reset() {} }) }, '@/views/pharmacy/consultation/reception': r
  })
  const user = { ...options.state(), ...options.actions }
  const saved = { conversationId: 8, content: 'private', clientRequestId: 'request' }
  for (const action of [() => auth.removeToken(), () => user.resetState(), () => auth.setTenantId(902), () => auth.setVisitTenantId(903), () => auth.removeTenantContext()]) {
    r.pendingSends.remember(saved)
    action()
    assert.equal(r.pendingSends.get(8), undefined)
  }
  r.pendingSends.remember(saved)
  await assert.rejects(user.loginOut(), /offline/)
  assert.equal(r.pendingSends.get(8), undefined)
  storage.set('ACCESS_TOKEN', 'synthetic')
  storage.set('USER', { user: { id: 1 }, roles: [], permissions: [] })
  await user.setUserInfoAction()
  r.pendingSends.remember(saved)
  await user.setUserInfoAction()
  assert.equal(r.pendingSends.get(8).content, 'private')
  storage.set('USER', { user: { id: 2 }, roles: [], permissions: [] })
  await user.setUserInfoAction()
  assert.equal(r.pendingSends.get(8), undefined)
})

test('expired login clears retry before relogin confirmation', async () => {
  const r = rules('consultation/reception.ts')
  r.pendingSends.remember({ conversationId: 8, content: 'private', clientRequestId: 'request' })
  const source = fs.readFileSync(path.join(__dirname, '../../config/axios/service.ts'), 'utf8')
  const fn = source.slice(source.indexOf('const handleAuthorized ='), source.indexOf('export { service }'))
  const handler = evaluate(fn + '\nexport { handleAuthorized }', {}, {
    pendingSends: r.pendingSends, useI18n: () => ({ t: (x) => x }), isRelogin: { show: false },
    window: { location: { href: '/work' } }, ElMessageBox: { confirm: () => new Promise(() => {}) }
  })
  await assert.rejects(handler.handleAuthorized())
  assert.equal(r.pendingSends.get(8), undefined)
})

for (const outcome of ['resolve', 'reject']) {
  test(`late prescription reload ${outcome} cannot change newly opened record or loading lock`, async () => {
    const late = deferred()
    let reload = false
    const p = review({ getPrescRecord: (id) => reload ? late.promise : Promise.resolve(record(id)), getPrivateMaterial: async () => new Blob(['x'], { type: 'image/png' }) })
    await p.state.openReview({ id: 1 })
    reload = true
    const old = p.state.reloadReview()
    p.hooks.onDeactivated()
    reload = false
    p.hooks.onActivated()
    await p.state.openReview({ id: 2 })
    p.state.reviewLoading.value = true
    outcome === 'resolve' ? late.resolve(record(1)) : late.reject(new Error('late'))
    await old
    assert.equal(p.state.reviewTarget.value.id, 2)
    assert.equal(p.state.submitState.value, 'idle')
    assert.equal(p.state.reviewLoading.value, true)
  })
}

test('late prescription detail cannot reopen after destruction', async () => {
  const late = deferred(), p = review({ getPrescRecord: () => late.promise })
  const loading = p.state.openReview({ id: 1 })
  p.hooks.onBeforeUnmount()
  late.resolve(record(1))
  await loading
  assert.equal(p.state.reviewVisible.value, false)
  assert.equal(p.state.reviewTarget.value, null)
})

test('old closed animation cannot invalidate pending new prescription detail', async () => {
  const late = deferred(), p = review({ getPrescRecord: () => late.promise })
  const opening = p.state.openReview({ id: 2 })
  p.state.finishReview()
  late.resolve({ ...record(2), wxMemberId: null })
  await opening
  assert.equal(p.state.reviewVisible.value, true)
  assert.equal(p.state.reviewTarget.value.id, 2)
})

test('late submit completion cannot clear a new prescription loading lock', async () => {
  const late = deferred(), p = review({ getPrescRecord: async (id) => record(id), getPrivateMaterial: async () => new Blob(['x'], { type: 'image/png' }), reviewPrescRecord: () => late.promise })
  await p.state.openReview({ id: 1 })
  p.state.reviewForm.reviewStatus = 2
  p.state.reviewForm.reviewOpinion = 'Synthetic rejection'
  const sending = p.state.submitReview()
  p.hooks.onDeactivated()
  p.hooks.onActivated()
  await p.state.openReview({ id: 2 })
  p.state.reviewLoading.value = true
  late.resolve()
  await sending
  assert.equal(p.state.reviewTarget.value.id, 2)
  assert.equal(p.state.reviewLoading.value, true)
  assert.equal(p.state.reviewVisible.value, true)
})

test('first prescription mount and activation issue only one list request', async () => {
  const pending = deferred()
  let requests = 0
  const p = review({ getPrescRecordPage: () => { requests++; return pending.promise } })
  const mounted = p.hooks.onMounted()
  await p.hooks.onActivated()
  assert.equal(requests, 1)
  assert.equal(p.state.loading.value, true)
  pending.resolve({ list: [record(1)], total: 1 })
  await mounted
  assert.equal(p.state.loading.value, false)
  assert.equal(p.state.list.value[0].id, 1)
})

for (const outcome of ['resolve', 'reject']) {
  for (const order of ['old-first', 'new-first']) {
    test(`prescription list resumes after reactivation; ${outcome} ${order} cannot affect new list`, async () => {
      const pending = []
      const p = review({ getPrescRecordPage: () => {
        const request = deferred()
        pending.push(request)
        return request.promise
      } })
      const mounted = p.hooks.onMounted()
      await p.hooks.onActivated()
      p.hooks.onDeactivated()
      assert.equal(p.state.loading.value, false)
      const activated = p.hooks.onActivated()
      assert.equal(pending.length, 2)
      assert.equal(p.state.loading.value, true)
      const finishOld = async () => {
        outcome === 'resolve' ? pending[0].resolve({ list: [record(1)], total: 100 }) : pending[0].reject(new Error('obsolete list failure'))
        await mounted
      }
      if (order === 'old-first') {
        await finishOld()
        assert.equal(p.state.list.value.length, 0)
        assert.equal(p.state.loading.value, true)
        assert.equal(p.state.listError.value, '')
      }
      pending[1].resolve({ list: [record(2)], total: 1 })
      await activated
      if (order === 'new-first') await finishOld()
      assert.equal(p.state.list.value.length, 1)
      assert.equal(p.state.list.value[0].id, 2)
      assert.equal(p.state.total.value, 1)
      assert.equal(p.state.loading.value, false)
      assert.equal(p.state.listError.value, '')
    })
  }
}
