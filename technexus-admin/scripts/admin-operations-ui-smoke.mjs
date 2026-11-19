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
  if (request.method === 'POST' && url.pathname === '/api/v1/auth/login') {
    const input = await body(request)
    const accessToken = input.account === 'reviewer@example.com' ? 'reviewer-token' : 'admin-token'
    return json(response, 200, { accessToken, csrfToken: 'test-csrf-token', expiresIn: 900, tokenType: 'Bearer' })
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/auth/logout') {
    response.writeHead(204, { 'Access-Control-Allow-Origin': request.headers.origin ?? '*', 'Access-Control-Allow-Credentials': 'true' })
    return response.end()
  }
  if (request.headers.authorization === 'Bearer reviewer-token') return json(response, 403, { code: 'ACCESS_DENIED' })
  if (request.method === 'GET' && url.pathname === '/api/v1/admin/dashboard') {
    return json(response, 200, { data: { pendingAudit: 2, pendingPricing: 1, failedJobs: 0, storageAlert: false, backupAlert: false } })
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/admin/audit-tasks') {
    return json(response, 200, { items: [{ id: tagOne, targetType: 'CONTENT', targetId: tagTwo, targetVersionId: batchId, state: 'PENDING', version: 0 }] })
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/admin/ai-suggestions') {
    return json(response, 200, { items: [{ id: batchId, kind: 'SUMMARY', targetType: 'CONTENT', targetId: tagOne, targetVersionId: tagTwo, state: 'SUCCEEDED', isSuggestion: true, output: { summary: 'test' }, version: 1 }] })
  }
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
    await browser.waitFor(() => browser.evaluate('document.body?.innerText.includes("管理员登录")'), 'admin login')
		await browser.waitFor(() => browser.evaluate('Boolean(document.querySelector("main")?.__vueParentComponent)'), 'Vue hydration')

    await setValue(browser, '#admin-account', 'reviewer@example.com')
    await setValue(browser, '#admin-password', 'ReviewerPassword!1')
    await clickText(browser, '.login-form', '登录')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("没有管理端权限")'), 'reviewer isolation')
    const loginRequest = state.requests.find(value => value.method === 'POST' && value.path.endsWith('/auth/login'))
    assert(!loginRequest?.auth, 'Login request unexpectedly sent an Authorization header')
    process.stdout.write('admin_ui_step=role-isolation-pass\n')

    await setValue(browser, '#admin-account', 'admin@example.com')
    await setValue(browser, '#admin-password', 'AdminPassword!1')
    await clickText(browser, '.login-form', '登录')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("运营总览")'), 'admin shell')
    process.stdout.write('admin_ui_step=login-pass\n')

    await clickText(browser, 'nav', '审核任务')
    const auditOptions = await browser.evaluate(`[...document.querySelectorAll('.toolbar select option')].map(option => [option.value, option.textContent.trim()])`)
    assert(JSON.stringify(auditOptions) === JSON.stringify([
      ['', '全部'], ['CREATED', '已创建'], ['PENDING', '待审核'], ['PROCESSING', '处理中'], ['APPROVED', '已通过'], ['REJECTED', '已拒绝'],
    ]), 'Audit state values and Chinese labels do not match')
    await clickText(browser, '.toolbar', '查询')
    await browser.waitFor(() => browser.evaluate('document.querySelector("tbody")?.innerText.includes("待审核")'), 'localized audit task state')
    assert(await browser.evaluate('document.querySelector("tbody")?.innerText.includes("内容")'), 'Audit target type was not localized')
    assert(!await browser.evaluate('/PENDING|CONTENT/.test(document.querySelector("tbody")?.innerText ?? "")'), 'Raw audit enum leaked into the table')
    await clickText(browser, 'tbody', '处理')
    const auditDecisionOptions = await browser.evaluate(`[...document.querySelectorAll('.panel form select')].map(select => [...select.options].map(option => [option.value, option.textContent.trim()]))`)
    assert(JSON.stringify(auditDecisionOptions[0]) === JSON.stringify([['APPROVED', '通过'], ['REJECTED', '拒绝']]), 'Audit decision labels do not match')
    assert(auditDecisionOptions[1].some(option => option[0] === 'INVALID_PRICE' && option[1] === '定价无效'), 'Audit reason labels are incomplete')

    await clickText(browser, 'nav', '定价确认')
    const pricingOptions = await browser.evaluate(`[...document.querySelectorAll('main form select option')].map(option => [option.value, option.textContent.trim()])`)
    assert(JSON.stringify(pricingOptions) === JSON.stringify([
      ['DEMAND', '需求'], ['DOCUMENT', '文档'], ['ATTACHMENT', '附件'], ['SERVICE', '服务'],
    ]), 'Pricing target values and Chinese labels do not match')
    process.stdout.write('admin_ui_step=enum-localization-pass\n')

    await clickText(browser, 'nav', 'AI 建议')
    const aiStateOptions = await browser.evaluate(`[...document.querySelectorAll('.toolbar select option')].map(option => [option.value, option.textContent.trim()])`)
    assert(aiStateOptions.some(option => option[0] === 'SUCCEEDED' && option[1] === '已生成'), 'AI state labels do not match')
    const aiFormOptions = await browser.evaluate(`(() => {
      const forms = [...document.querySelectorAll('main form')]
      return forms.map(form => [...form.querySelectorAll('select')].map(select => [...select.options].map(option => [option.value, option.textContent.trim()])))
    })()`)
    assert(aiFormOptions[0][0].some(option => option[0] === 'DEMAND_STRUCTURE' && option[1] === '需求结构化'), 'AI kind labels do not match')
    assert(aiFormOptions[0][1].some(option => option[0] === 'AUDIT_TASK' && option[1] === '审核任务'), 'AI target labels do not match')
    assert(aiFormOptions[1][0].some(option => option[0] === 'EDIT' && option[1] === '编辑后采纳'), 'AI decision labels do not match')
    await clickText(browser, '.toolbar', '查询')
    await browser.waitFor(() => browser.evaluate('document.querySelector("tbody")?.innerText.includes("已生成")'), 'localized AI suggestion')
    assert(await browser.evaluate('document.querySelector("tbody")?.innerText.includes("摘要建议") && document.querySelector("tbody")?.innerText.includes("内容")'), 'AI suggestion table was not fully localized')
    assert(!await browser.evaluate('/SUCCEEDED|SUMMARY|CONTENT/.test(document.querySelector("tbody")?.innerText ?? "")'), 'Raw AI enum leaked into the table')
    process.stdout.write('admin_ui_step=ai-localization-pass\n')

    await clickText(browser, 'nav', '配置中心')
    assert(await browser.evaluate('document.body.innerText.includes("功能开关") && !document.body.innerText.includes("Feature Flag")'), 'Feature Flag wording was not localized')
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
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("部分失败") && document.body.innerText.includes("版本冲突")'), 'localized partial batch')
    assert(!await browser.evaluate('/PARTIAL_FAILED|VERSION_CONFLICT/.test(document.body.innerText)'), 'Raw batch enum leaked into the page')
    const batchRequest = state.requests.find(value => value.method === 'POST' && value.path.endsWith('/tag-batches'))
    assert(batchRequest?.idempotency, 'Batch mutation did not send Idempotency-Key')
    process.stdout.write('admin_ui_step=partial-batch-pass\n')

    await browser.evaluate(`(() => {
      const panel = [...document.querySelectorAll('.panel')].find(value => value.querySelector('h2')?.textContent === '特权操作审计')
      panel.querySelector('button').click()
    })()`)
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("更新标签")'), 'localized operation audit')
    assert(!await browser.evaluate('document.body.innerText.includes("TAG_UPDATE")'), 'Raw audit action leaked into the page')
    process.stdout.write('admin_ui_step=audit-pass\n')

    await clickText(browser, 'header', '退出登录')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("管理员登录")'), 'logout return to login')
    process.stdout.write('admin_ui_step=logout-pass\n')
    process.stdout.write('admin_ui_smoke_tests=8\nadmin_ui_smoke_browser=Chromium-CDP\nadmin_ui_smoke_gate=PASS\n')
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
