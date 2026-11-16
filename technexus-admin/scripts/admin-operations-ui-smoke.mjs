import { Buffer } from 'node:buffer'
import { spawn, spawnSync } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { setTimeout as wait } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
const chromeCandidates = [
  process.env.TECHNEXUS_CHROME_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
].filter(Boolean)
const tagOne = '018f0000-0000-7000-8000-000000000001'
const tagTwo = '018f0000-0000-7000-8000-000000000002'
const batchId = '018f0000-0000-7000-8000-000000000003'
const actorId = '018f0000-0000-7000-8000-000000000004'
const state = { requests: [], configPut: null }

function assert(condition, message) {
  if (!condition) throw new Error(message)
}
async function body(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : null
}
function json(response, status, value) {
  response.writeHead(status, {
    'Access-Control-Allow-Headers': 'Authorization,Content-Type,Idempotency-Key',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS',
		'Access-Control-Allow-Origin': response.req?.headers.origin ?? '*',
		'Access-Control-Allow-Credentials': 'true',
    'Content-Type': 'application/json; charset=utf-8',
  })
  response.end(JSON.stringify(value))
}
function batch() {
  return {
    id: batchId, operation: 'TAG_DISABLE', status: 'PARTIAL_FAILED', totalCount: 2, successCount: 1,
    failureCount: 1, requestedBy: actorId, reason: 'browser partial failure', createdAt: '2026-10-29T12:00:00Z',
    items: [
      { sequence: 1, targetType: 'TAG', targetId: tagOne, requestedVersion: 1, status: 'SUCCEEDED', errorCode: null, resultVersion: 2 },
      { sequence: 2, targetType: 'TAG', targetId: tagTwo, requestedVersion: 0, status: 'FAILED', errorCode: 'VERSION_CONFLICT', resultVersion: null },
    ],
  }
}
async function mockApi(request, response) {
  const url = new URL(request.url ?? '/', `http://${request.headers.host}`)
  state.requests.push({ method: request.method, path: url.pathname, auth: request.headers.authorization, idempotency: request.headers['idempotency-key'] })
  if (request.method === 'OPTIONS') return json(response, 204, null)
  if (request.headers.authorization === 'Bearer reviewer-token') return json(response, 403, { code: 'ACCESS_DENIED' })
  if (request.method === 'GET' && url.pathname === '/api/v1/admin/config/feature.content.ai') {
    return json(response, 200, { data: { key: 'feature.content.ai', value: { enabled: false }, version: 3 } })
  }
  if (request.method === 'PUT' && url.pathname === '/api/v1/admin/config/feature.content.ai') {
    state.configPut = await body(request)
    return json(response, 409, { code: 'VERSION_CONFLICT' })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/admin/tag-batches') return json(response, 200, { data: batch() })
  if (request.method === 'GET' && url.pathname === '/api/v1/admin/batches') return json(response, 200, { items: [batch()] })
  if (request.method === 'GET' && url.pathname === `/api/v1/admin/batches/${batchId}`) return json(response, 200, { data: batch() })
  if (request.method === 'GET' && url.pathname === '/api/v1/admin/operation-audits') {
    return json(response, 200, { items: [{ id: 1, actorId, action: 'TAG_UPDATE', target: { tagId: tagOne }, createdAt: '2026-10-29T12:01:00Z' }] })
  }
  return json(response, 404, { code: 'RESOURCE_NOT_FOUND', path: url.pathname })
}
async function listen(server) {
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolvePromise)
  })
  return server.address()
}
async function freePorts(count) {
  const values = []
  try {
    for (let index = 0; index < count; index++) {
      const server = createServer()
      values.push({ server, address: await listen(server) })
    }
    return values.map(value => value.address.port)
  } finally {
    await Promise.all(values.map(value => new Promise(resolvePromise => value.server.close(resolvePromise))))
  }
}
async function waitForHttp(url, child, output, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child?.exitCode !== null) throw new Error(`Process exited before ${url}\n${output()}`)
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) })
      if (response.ok) return
    } catch { /* still starting */ }
    await wait(200)
  }
  throw new Error(`Timed out waiting for ${url}\n${output()}`)
}
async function stopChild(child) {
  if (!child || child.exitCode !== null) return
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    await wait(250)
  } else {
    child.kill('SIGKILL')
  }
}
class CdpClient {
  constructor(url) {
    this.nextId = 0
    this.pending = new Map()
    this.socket = new WebSocket(url)
  }
  async connect() {
    await new Promise((resolvePromise, reject) => {
      this.socket.addEventListener('open', resolvePromise, { once: true })
      this.socket.addEventListener('error', reject, { once: true })
    })
    this.socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data)
      const pending = this.pending.get(message.id)
      if (!pending) return
      this.pending.delete(message.id)
      if (message.error) pending.reject(new Error(message.error.message))
      else pending.resolve(message.result)
    })
    await this.send('Page.enable')
    await this.send('Runtime.enable')
  }
  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolvePromise, reject) => {
      this.pending.set(id, { reject, resolve: resolvePromise })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', { awaitPromise: true, expression, returnByValue: true })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
    return result.result.value
  }
  async waitFor(check, message, timeoutMs = 15000) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (await check()) return
      await wait(100)
    }
    throw new Error(`Timed out waiting for ${message}`)
  }
  close() { this.socket.close() }
}
async function setValue(browser, selector, value) {
  const changed = await browser.evaluate(`(() => {
    const element = document.querySelector(${JSON.stringify(selector)})
    if (!element) return false
    const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')
    descriptor.set.call(element, ${JSON.stringify(value)})
    element.dispatchEvent(new Event('input', { bubbles: true }))
    element.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  })()`)
  assert(changed, `Missing form element: ${selector}`)
}
async function clickText(browser, containerSelector, text) {
  const clicked = await browser.evaluate(`(() => {
    const root = document.querySelector(${JSON.stringify(containerSelector)}) ?? document
    const element = [...root.querySelectorAll('button')].find(value => value.textContent.trim() === ${JSON.stringify(text)})
    if (!element) return false
    element.click()
    return true
  })()`)
  assert(clicked, `Missing button ${text} in ${containerSelector}`)
}
async function main() {
  const apiServer = createServer((request, response) => mockApi(request, response).catch(error => json(response, 500, { message: error.message })))
  const apiAddress = await listen(apiServer)
  const [webPort, chromePort] = await freePorts(2)
  const tempRoot = await mkdtemp(join(tmpdir(), 'technexus-admin-ui-'))
  let viteOutput = ''
  const vite = spawn(process.execPath, [join(root, 'node_modules', 'vite', 'bin', 'vite.js'), '--host', '127.0.0.1', '--port', String(webPort)], {
    cwd: root,
    env: { ...process.env, VITE_API_BASE: `http://127.0.0.1:${apiAddress.port}/api/v1` },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const rememberVite = chunk => { viteOutput = `${viteOutput}${chunk}`.slice(-12000) }
  vite.stdout.on('data', rememberVite)
  vite.stderr.on('data', rememberVite)
  let chrome
  let browser
  try {
    await waitForHttp(`http://127.0.0.1:${webPort}`, vite, () => viteOutput)
    const chromePath = chromeCandidates.find(path => path && process.getBuiltinModule('node:fs').existsSync(path))
    assert(chromePath, 'Chrome or Edge executable was not found')
    chrome = spawn(chromePath, ['--headless=new', '--disable-gpu', '--no-first-run', `--remote-debugging-port=${chromePort}`, `--user-data-dir=${join(tempRoot, 'profile')}`, 'about:blank'], { stdio: 'ignore' })
    await waitForHttp(`http://127.0.0.1:${chromePort}/json/version`, chrome, () => 'Chrome DevTools unavailable')
    const page = await fetch(`http://127.0.0.1:${chromePort}/json/new?http://127.0.0.1:${webPort}`, { method: 'PUT' }).then(response => response.json())
    browser = new CdpClient(page.webSocketDebuggerUrl)
    await browser.connect()
    await browser.waitFor(() => browser.evaluate('document.body?.innerText.includes("运营总览")'), 'admin shell')
		await browser.waitFor(() => browser.evaluate('Boolean(document.querySelector("main")?.__vueParentComponent)'), 'Vue hydration')

    await setValue(browser, 'label.token input', 'reviewer-token')
		await browser.waitFor(() => browser.evaluate('!document.querySelector("main .actions button")?.disabled'), 'dashboard button enabled')
    await clickText(browser, 'main', '刷新数据')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("HTTP 403")'), 'reviewer isolation')
    process.stdout.write('admin_ui_step=role-isolation-pass\n')

    await setValue(browser, 'label.token input', 'admin-token')
    await clickText(browser, 'nav', '配置中心')
    const systemPanel = '.panel:has(h2:first-child)'
    await clickText(browser, systemPanel, '读取')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("配置已加载")'), 'config load')
    await browser.evaluate(`(() => {
      const panel = [...document.querySelectorAll('.panel')].find(value => value.querySelector('h2')?.textContent === '系统参数')
      const area = panel.querySelectorAll('textarea')[1]
      const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(area), 'value')
      descriptor.set.call(area, 'stale browser update')
      area.dispatchEvent(new Event('input', { bubbles: true }))
    })()`)
    await clickText(browser, systemPanel, '保存配置')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("HTTP 409")'), 'version conflict')
    assert(state.configPut?.expectedVersion === 3, 'Expected version was not sent')
    process.stdout.write('admin_ui_step=version-conflict-pass\n')

    await clickText(browser, 'nav', '运营能力')
    await browser.evaluate(`(() => {
      const panel = [...document.querySelectorAll('.panel')].find(value => value.querySelector('h2')?.textContent === '标签批处理与逐项结果')
      const areas = panel.querySelectorAll('textarea')
      const values = [${JSON.stringify(JSON.stringify([{ tagId: tagOne, expectedVersion: 1 }, { tagId: tagTwo, expectedVersion: 0 }]))}, 'browser partial failure']
      areas.forEach((area, index) => {
        const descriptor = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(area), 'value')
        descriptor.set.call(area, values[index])
        area.dispatchEvent(new Event('input', { bubbles: true }))
      })
      panel.querySelector('form button').click()
    })()`)
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("PARTIAL_FAILED") && document.body.innerText.includes("VERSION_CONFLICT")'), 'partial batch')
    const batchRequest = state.requests.find(value => value.method === 'POST' && value.path.endsWith('/tag-batches'))
    assert(batchRequest?.idempotency, 'Batch mutation did not send Idempotency-Key')
    process.stdout.write('admin_ui_step=partial-batch-pass\n')

    await browser.evaluate(`(() => {
      const panel = [...document.querySelectorAll('.panel')].find(value => value.querySelector('h2')?.textContent === '特权操作审计')
      panel.querySelector('button').click()
    })()`)
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("TAG_UPDATE")'), 'operation audit')
    process.stdout.write('admin_ui_step=audit-pass\n')
    process.stdout.write('admin_ui_smoke_tests=4\nadmin_ui_smoke_browser=Chromium-CDP\nadmin_ui_smoke_gate=PASS\n')
  } finally {
    browser?.close()
    await stopChild(chrome)
    await stopChild(vite)
    apiServer.closeAllConnections()
    await new Promise(resolvePromise => apiServer.close(resolvePromise))
    await rm(tempRoot, { force: true, recursive: true })
  }
}
main().catch(error => {
  process.stderr.write(`${error.stack ?? error}\n`)
  process.exitCode = 1
})
