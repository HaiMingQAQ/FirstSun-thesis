const { test, after } = require('node:test')
const assert = require('node:assert/strict')
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { fetchEventSource } = require('@microsoft/fetch-event-source')

// Exercise the actual SSE client with local HTTP fixtures and synthetic authentication.
global.window = { fetch, setTimeout, clearTimeout }
global.document = { hidden: false, addEventListener() {}, removeEventListener() {} }
after(() => {
  delete global.window
  delete global.document
})

const loadApi = (baseUrl) => {
  const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, 'index.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  const module = { exports: {} }
  const dependencies = {
    '@/config/axios': {},
    '@/config/axios/config': { config: { base_url: baseUrl } },
    '@/utils/auth': { getAccessToken: () => 'synthetic-test-token' },
    '@microsoft/fetch-event-source': { fetchEventSource }
  }
  vm.runInThisContext(`(function(exports, require, module) {${source}\n})`)(
    module.exports,
    (name) => {
      assert.ok(Object.hasOwn(dependencies, name), `Unexpected dependency: ${name}`)
      return dependencies[name]
    },
    module
  )
  return module.exports.PharmacyAiApi
}

const fixture = async (handler) => {
  let requests = 0
  const server = http.createServer((request, response) => {
    requests++
    handler(request, response)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  return {
    api: loadApi(`http://127.0.0.1:${server.address().port}`),
    count: () => requests,
    close: async () => {
      server.closeAllConnections()
      await new Promise((resolve) => server.close(resolve))
    }
  }
}

const event = (type, data) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`
const request = { content: 'synthetic question', clientMessageId: 'synthetic-id' }

test('normal SSE finishes once and exposes the correlation ID', async () => {
  const server = await fixture((_, response) => {
    response.writeHead(200, { 'Content-Type': 'text/event-stream' })
    response.end(
      event('metadata', { requestId: 'synthetic-request' }) +
        event('delta', { content: '52' }) +
        event('done', { finishReason: 'stop' })
    )
  })
  try {
    const messages = []
    await server.api.stream(request, new AbortController().signal, (message) =>
      messages.push(message)
    )
    assert.deepEqual(
      messages.map((message) => message.event),
      ['metadata', 'delta', 'done']
    )
    assert.equal(JSON.parse(messages[0].data).requestId, 'synthetic-request')
    assert.equal(server.count(), 1)
  } finally {
    await server.close()
  }
})

test('HTTP failure rejects without reconnecting or replaying the POST', async () => {
  const server = await fixture((_, response) => {
    response.writeHead(503, { 'Content-Type': 'application/json' })
    response.end('{"error":"synthetic failure"}')
  })
  try {
    await assert.rejects(server.api.stream(request, new AbortController().signal, () => {}))
    assert.equal(server.count(), 1)
  } finally {
    await server.close()
  }
})

test('closed SSE without done preserves partial events and reports interruption', async () => {
  const server = await fixture((_, response) => {
    response.writeHead(200, { 'Content-Type': 'text/event-stream' })
    response.end(
      event('metadata', { requestId: 'synthetic-request' }) + event('delta', { content: 'partial' })
    )
  })
  try {
    const messages = []
    await assert.rejects(
      server.api.stream(request, new AbortController().signal, (message) => messages.push(message))
    )
    assert.deepEqual(
      messages.map((message) => message.event),
      ['metadata', 'delta']
    )
    assert.equal(server.count(), 1)
  } finally {
    await server.close()
  }
})

test('error then done remains an explicit failed request', async () => {
  const server = await fixture((_, response) => {
    response.writeHead(200, { 'Content-Type': 'text/event-stream' })
    response.end(
      event('error', {
        requestId: 'synthetic-request',
        errorCode: 'UPSTREAM_RESPONSE_TRUNCATED',
        message: 'synthetic failure'
      }) + event('done', { finishReason: 'error' })
    )
  })
  try {
    const messages = []
    await server.api.stream(request, new AbortController().signal, (message) =>
      messages.push(message)
    )
    assert.equal(JSON.parse(messages[0].data).errorCode, 'UPSTREAM_RESPONSE_TRUNCATED')
    assert.equal(JSON.parse(messages[1].data).finishReason, 'error')
    assert.equal(server.count(), 1)
  } finally {
    await server.close()
  }
})

test('abort stops the stream without a synthetic error or replay', async () => {
  const server = await fixture((_, response) => {
    response.writeHead(200, { 'Content-Type': 'text/event-stream' })
    response.write(event('metadata', { requestId: 'synthetic-request' }))
  })
  try {
    const controller = new AbortController()
    const messages = []
    await server.api.stream(request, controller.signal, (message) => {
      messages.push(message)
      controller.abort()
    })
    assert.deepEqual(
      messages.map((message) => message.event),
      ['metadata']
    )
    assert.equal(server.count(), 1)
  } finally {
    await server.close()
  }
})
