const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const compiler = require('vue/compiler-sfc')
const { randomUUID } = require('node:crypto')

const createPage = (api) => {
  const filename = path.join(__dirname, 'index.vue')
  const { descriptor, errors } = compiler.parse(fs.readFileSync(filename, 'utf8'), { filename })
  assert.deepEqual(errors, [])
  const script = compiler.compileScript(descriptor, { id: 'pharmacy-ai-test' })
  const compiled = ts.transpileModule(script.content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  const imports = {
    vue,
    '@/components/MarkdownView/index.vue': {},
    '@/api/pharmacy/ai': { PharmacyAiApi: api },
    './InventoryPurchaseDialog.vue': {},
    '@/utils/formatTime': { formatDate: (value, pattern) => require('dayjs')(value).format(pattern) },
    '@/directives/permission/hasPermi': { hasPermission: () => true }
  }
  const storage = new Map()
  const sandbox = {
    ...vue,
    module,
    exports: module.exports,
    require: (name) => {
      assert.ok(Object.hasOwn(imports, name), `Unexpected import: ${name}`)
      return imports[name]
    },
    crypto: { randomUUID },
    AbortController,
    sessionStorage: {
      setItem: (key, value) => storage.set(key, value),
      getItem: (key) => storage.get(key),
      removeItem: (key) => storage.delete(key)
    },
    useMessage: () => ({ success() {}, error() {} }),
    useRouter: () => ({ push() {} }),
    onMounted() {},
    onBeforeUnmount() {}
  }
  vm.runInNewContext(compiled, sandbox, { filename })
  return module.exports.default.setup({}, { expose() {} })
}

const emit = (callback, event, data) => callback({ event, data: JSON.stringify(data) })

test('purchase preview renders server date arrays and epoch expiry as readable dates', () => {
  const page = createPage({})
  assert.equal(page.previewDate([2026, 10, 13]), '2026-10-13')
  assert.equal(page.previewDate(new Date(2026, 9, 13, 16, 30).getTime(), 'YYYY-MM-DD HH:mm:ss'), '2026-10-13 16:30:00')
  assert.equal(page.previewDate(null), '未指定')
})

test('partial answer and tool result survive a correlated upstream error', async () => {
  const page = createPage({
    stream: async (_, __, callback) => {
      emit(callback, 'metadata', { requestId: 'synthetic-request' })
      emit(callback, 'tool_result', {
        tool: 'getCurrentStoreInventory',
        result: { availableQty: 52 }
      })
      emit(callback, 'delta', { content: '部分回答' })
      emit(callback, 'error', { message: 'AI 回答未能完整生成', requestId: 'synthetic-request' })
      emit(callback, 'done', { finishReason: 'error' })
    }
  })
  page.content.value = 'synthetic question'
  await page.send()
  const answer = page.messages.value[1]
  assert.equal(answer.text, '部分回答')
  assert.equal(answer.toolResult.result.availableQty, 52)
  assert.equal(answer.errorMessage, 'AI 回答未能完整生成')
  assert.equal(answer.requestId, 'synthetic-request')
  assert.equal(page.generating.value, false)
})

test('stopping an old stream does not clear the new stream generation state', async () => {
  const pending = []
  const page = createPage({
    stream: (_, signal) => new Promise((resolve) => pending.push({ signal, resolve }))
  })
  page.content.value = 'first question'
  const first = page.send()
  page.stop()
  page.content.value = 'second question'
  const second = page.send()
  pending[0].resolve()
  await first
  assert.equal(page.messages.value[1].stopped, true)
  assert.equal(page.generating.value, true)
  assert.equal(pending[1].signal.aborted, false)
  pending[1].resolve()
  await second
  assert.equal(page.generating.value, false)
})

test('preview is displayed without issuing confirmation', async () => {
  let confirmations = 0
  const preview = {
    commandId: 9,
    tool: 'CREATE_PURCHASE_DRAFT',
    status: 'PENDING',
    confirmationToken: 'synthetic-token'
  }
  const page = createPage({
    stream: async (_, __, callback) => {
      emit(callback, 'tool_preview', preview)
      emit(callback, 'done', { finishReason: 'tool_confirmation_required' })
    },
    confirm: () => {
      confirmations++
    }
  })
  page.content.value = 'synthetic request'
  await page.send()
  assert.equal(page.messages.value[1].preview.commandId, 9)
  assert.equal(page.commands.value[0].status, 'PENDING')
  assert.equal(confirmations, 0)
})

test('confirmed procurement exposes the real draft ID and removes the confirmation token', async () => {
  let confirmations = 0
  const page = createPage({
    confirm: async () => {
      confirmations++
      return { purchaseOrderId: 99, purchaseStatus: 'DRAFT', status: 'EXECUTED' }
    }
  })
  const preview = {
    commandId: 9,
    tool: 'CREATE_PURCHASE_DRAFT',
    status: 'PENDING',
    confirmationToken: 'synthetic-token'
  }
  await page.confirmCommand(preview)
  assert.equal(preview.status, 'EXECUTED')
  assert.equal(preview.result.purchaseOrderId, 99)
  assert.equal(preview.result.purchaseStatus, 'DRAFT')
  assert.equal(preview.confirmationToken, undefined)
  assert.equal(confirmations, 1)
})

test('failed confirmation retrieves server status without falsely claiming failure or retrying', async () => {
  let confirmations = 0
  const page = createPage({
    confirm: async () => {
      confirmations++
      throw new Error('synthetic response lost')
    },
    recentCommands: async () => [{ commandId: 9, status: 'EXECUTED' }]
  })
  const preview = {
    commandId: 9,
    tool: 'CREATE_PURCHASE_DRAFT',
    status: 'PENDING',
    confirmationToken: 'synthetic-token'
  }
  await page.confirmCommand(preview)
  assert.equal(preview.status, 'EXECUTED')
  assert.equal(preview.confirmationToken, undefined)
  assert.equal(preview.errorMessage, 'synthetic response lost')
  assert.equal(confirmations, 1)
})

test('a repeated confirm click cannot send a second confirmation while the first is pending', async () => {
  let confirmations = 0
  let finish
  const page = createPage({
    confirm: () => {
      confirmations++
      return new Promise((resolve) => {
        finish = resolve
      })
    }
  })
  const preview = {
    commandId: 9,
    tool: 'CREATE_PURCHASE_DRAFT',
    status: 'PENDING',
    confirmationToken: 'synthetic-token'
  }
  const first = page.confirmCommand(preview)
  await page.confirmCommand(preview)
  assert.equal(confirmations, 1)
  finish({ purchaseOrderId: 99, purchaseStatus: 'DRAFT' })
  await first
  await page.confirmCommand(preview)
  assert.equal(confirmations, 1)
})

test('failed cancel retrieves cancelled status and cannot leave an active confirm button', async () => {
  const page = createPage({
    cancel: async () => {
      throw new Error('synthetic response lost')
    },
    recentCommands: async () => [{ commandId: 9, status: 'CANCELLED' }]
  })
  const preview = {
    commandId: 9,
    tool: 'CREATE_PURCHASE_DRAFT',
    status: 'PENDING',
    confirmationToken: 'synthetic-token'
  }
  await page.cancelCommand(preview)
  assert.equal(preview.status, 'CANCELLED')
  assert.equal(preview.confirmationToken, undefined)
})
