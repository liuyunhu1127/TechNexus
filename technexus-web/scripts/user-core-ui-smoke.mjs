import { Buffer } from 'node:buffer'
import { spawn, spawnSync } from 'node:child_process'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
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

const state = {
  blockedUpload: false,
  createdBody: null,
  requests: [],
  tagsFail: false,
  uploadedBytes: 0,
  profile: { id: 'ui-user', displayName: '原昵称', bio: '', status: 'ACTIVE', version: 4 },
  security: { email: 'ui@example.com', verified: false, bootstrapAdmin: false, mailEnabled: true },
  avatar: null,
  object: null,
}
const oldCode = 'A'.repeat(22) + '-_'
const newCode = 'B'.repeat(22) + '_-'
const resetToken = '018f0000-0000-7000-8000-000000000001.' + newCode

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function readBody(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks)
}

function json(response, status, value, extraHeaders = {}) {
  response.writeHead(status, {
    'Access-Control-Allow-Headers': 'Authorization,Content-Type,Idempotency-Key,X-CSRF-Token',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
    'Access-Control-Allow-Origin': '*',
    'Content-Type': 'application/json; charset=utf-8',
    ...extraHeaders,
  })
  response.end(JSON.stringify(value))
}

async function mockApi(request, response, address) {
  const url = new URL(request.url ?? '/', `http://${request.headers.host}`)
  state.requests.push(`${request.method} ${url.pathname}`)
  if (request.method === 'OPTIONS') {
    json(response, 204, null)
    return
  }
  if (request.method === 'PUT' && url.pathname === '/objects/file-1') {
    state.object = await readBody(request)
    state.uploadedBytes = state.object.length
    json(response, 200, {})
    return
  }
  if (request.method === 'GET' && url.pathname === '/objects/file-1' && state.avatar) {
    response.writeHead(200, { 'Content-Type': 'image/png' })
    return response.end(state.object)
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/auth/login') {
    json(response, 200, { accessToken: 'ui-access-token', csrfToken: 'ui-csrf-token' }, {
      'Set-Cookie': 'technexus_refresh=ui-refresh; Path=/api/v1/auth; HttpOnly; SameSite=Lax',
    })
    return
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/auth/refresh') {
    json(response, 200, { accessToken: 'ui-access-token', csrfToken: 'ui-csrf-token' })
    return
  }
  if (url.pathname === '/api/v1/me/avatar') {
    if (request.method === 'PUT') {
      const input = JSON.parse(await readBody(request))
      assert(input.expectedVersion === state.profile.version && input.fileId === 'file-1', 'Avatar must reference the latest profile and scanned file')
      state.avatar = `http://127.0.0.1:${address.port}/objects/file-1`
      state.profile.version++
      return json(response, 200, { data: { version: state.profile.version } })
    }
    return json(response, 200, { data: { url: state.avatar, version: state.profile.version } })
  }
  if (url.pathname === '/api/v1/me/security') return json(response, 200, { data: state.security })
  if (['/api/v1/me/email-verification', '/api/v1/me/email-change'].includes(url.pathname)) {
    const input = JSON.parse(await readBody(request))
    assert(input.password, 'Current password missing from email request')
    if (url.pathname.endsWith('email-change')) assert(state.security.verified && input.email === 'new@example.test', 'Email change request invalid')
    return json(response, 202, { data: { requestId: 'local-email-action' } })
  }
  if (url.pathname === '/api/v1/me/email-verification/confirm') {
    const input = JSON.parse(await readBody(request))
    assert(input.requestId === 'local-email-action' && input.code === oldCode, 'Email verification payload invalid')
    state.security.verified = true
    return json(response, 204)
  }
  if (url.pathname === '/api/v1/me/email-change/confirm') {
    const input = JSON.parse(await readBody(request))
    assert(input.requestId === 'local-email-action' && input.oldCode === oldCode && input.newCode === newCode, 'Dual email codes missing')
    state.security.email = 'new@example.test'
    return json(response, 204)
  }
  if (url.pathname === '/api/v1/auth/forgot-password') return json(response, 202, { message: '已接收' })
  if (url.pathname === '/api/v1/auth/reset-password') {
    const input = JSON.parse(await readBody(request))
    assert(input.token === resetToken && input.password === 'Browser-new-password!', 'Password reset payload invalid')
    return json(response, 204)
  }
  if (url.pathname === '/api/v1/me') {
    assert(request.headers.authorization === 'Bearer ui-access-token', 'Personal center request missing authentication')
    if (request.method === 'PATCH') {
      const input = JSON.parse((await readBody(request)).toString('utf8'))
      assert(input.expectedVersion === state.profile.version, 'Personal center update missing expected version')
      state.profile = { ...state.profile, displayName: input.displayName, bio: input.bio, version: state.profile.version + 1 }
    }
    json(response, 200, { data: state.profile })
    return
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/contents') {
    json(response, 200, { items: [] })
    return
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/tags') {
    if (state.tagsFail) json(response, 503, { code: 'DEPENDENCY_UNAVAILABLE', message: 'tag store unavailable' })
    else json(response, 200, { items: [{ id: 'tag-1', name: 'Java' }, { id: 'tag-2', name: 'DDD' }] })
    return
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/files/uploads') {
    const input = JSON.parse(await readBody(request))
    json(response, 201, {
      data: {
        expiresAt: '2026-10-29T18:00:00Z',
        fileId: 'file-1',
        requiredHeaders: { 'Content-Type': input.declaredMime },
        state: 'UPLOADING',
        uploadId: 'upload-1',
        uploadUrl: `http://127.0.0.1:${address.port}/objects/file-1`,
      },
    })
    return
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/files/uploads/upload-1/complete') {
    json(response, 200, { data: { id: 'file-1', state: 'SCANNING' } })
    return
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/files/file-1') {
    json(response, 200, { data: { id: 'file-1', state: state.blockedUpload ? 'BLOCKED' : 'AVAILABLE', detectedMime: 'text/plain' } })
    return
  }
  if (request.method === 'POST' && url.pathname === '/api/v1/contents') {
    state.createdBody = JSON.parse((await readBody(request)).toString('utf8'))
    json(response, 201, { data: contentView() })
    return
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/contents/content-1') {
    json(response, 200, { data: contentView() })
    return
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/contents/forbidden') {
    json(response, 403, { code: 'ACCESS_DENIED', message: 'forbidden' })
    return
  }
  json(response, 404, { code: 'RESOURCE_NOT_FOUND', message: `${request.method} ${url.pathname}` })
}

function contentView() {
  return {
    body: state.createdBody?.body ?? '浏览器自动化正文',
    fileIds: state.createdBody?.fileIds ?? ['file-1'],
    id: 'content-1',
    state: 'DRAFT',
    summary: state.createdBody?.summary ?? 'UI smoke',
    tagIds: state.createdBody?.tagIds ?? ['tag-1'],
    title: state.createdBody?.title ?? '浏览器自动化内容',
    type: 'DOCUMENT',
    version: 0,
    visibility: 'PUBLIC',
  }
}

async function listen(server) {
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolvePromise)
  })
  return server.address()
}

async function freePorts(count) {
  const reservations = []
  try {
    for (let index = 0; index < count; index++) {
      const server = createServer()
      const address = await listen(server)
      reservations.push({ address, server })
    }
    return reservations.map(reservation => reservation.address.port)
  } finally {
    await Promise.all(reservations.map(reservation => new Promise(resolvePromise => reservation.server.close(resolvePromise))))
  }
}

async function waitForHttp(url, child, output, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child?.exitCode !== null) throw new Error(`Process exited before ${url}\n${output()}`)
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) })
      if (response.ok) return
    } catch {
      // Service is still starting.
    }
    await wait(200)
  }
  throw new Error(`Timed out waiting for ${url}\n${output()}`)
}

async function stopChild(child) {
  if (!child || child.exitCode !== null) return
  if (process.platform === 'win32') {
    spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    await wait(250)
    return
  }
  const exited = new Promise(resolvePromise => child.once('exit', resolvePromise))
  child.kill()
  await Promise.race([exited, wait(5000)])
  if (child.exitCode === null) {
    child.kill('SIGKILL')
    await Promise.race([exited, wait(5000)])
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
      if (!message.id) return
      const pending = this.pending.get(message.id)
      if (!pending) return
      this.pending.delete(message.id)
      if (message.error) pending.reject(new Error(message.error.message))
      else pending.resolve(message.result)
    })
    const rejectPending = (event) => {
      const error = new Error(`Chrome DevTools connection closed${event?.message ? `: ${event.message}` : ''}`)
      for (const pending of this.pending.values()) pending.reject(error)
      this.pending.clear()
    }
    this.socket.addEventListener('close', rejectPending)
    this.socket.addEventListener('error', rejectPending)
    await this.send('Page.enable')
    await this.send('Runtime.enable')
    await this.send('DOM.enable')
  }

  send(method, params = {}) {
    const id = ++this.nextId
    return new Promise((resolvePromise, reject) => {
      if (this.socket.readyState !== 1) {
        reject(new Error(`Chrome DevTools connection is not open while sending ${method}`))
        return
      }
      this.pending.set(id, { reject, resolve: resolvePromise })
      this.socket.send(JSON.stringify({ id, method, params }))
    })
  }

  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', { awaitPromise: true, expression, returnByValue: true })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
    return result.result.value
  }

  async navigate(url) {
    await this.send('Page.navigate', { url })
    await this.waitFor(() => this.evaluate('document.readyState === "complete"'))
  }

  async waitFor(check, message = 'browser condition', timeoutMs = 15000) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (await check()) return
      await wait(100)
    }
    throw new Error(`Timed out waiting for ${message}`)
  }

  close() {
    this.socket.close()
  }
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

async function click(browser, selector) {
  const clicked = await browser.evaluate(`(() => {
    const element = document.querySelector(${JSON.stringify(selector)})
    if (!element) return false
    element.click()
    return true
  })()`)
  assert(clicked, `Missing clickable element: ${selector}`)
}

async function setFile(browser, path) {
  const documentNode = await browser.send('DOM.getDocument', { depth: 1 })
  const input = await browser.send('DOM.querySelector', { nodeId: documentNode.root.nodeId, selector: 'input[type="file"]' })
  assert(input.nodeId, 'Missing file input')
  await browser.send('DOM.setFileInputFiles', { files: [path], nodeId: input.nodeId })
}

async function main() {
  const apiServer = createServer((request, response) => {
    mockApi(request, response, apiServer.address()).catch((error) => json(response, 500, { message: error.message }))
  })
  const apiAddress = await listen(apiServer)
  const [webPort, chromePort] = await freePorts(2)
  const tempRoot = await mkdtemp(join(tmpdir(), 'technexus-ui-'))
  const uploadPath = join(tempRoot, 'safe-attachment.txt')
  await writeFile(uploadPath, 'TechNexus safe UI attachment', 'utf8')

  let nuxtOutput = ''
  const nuxt = spawn(process.execPath, [join(root, 'node_modules', '@nuxt', 'cli', 'bin', 'nuxi.mjs'), 'dev', '--host', '127.0.0.1', '--port', String(webPort)], {
    cwd: root,
    env: { ...process.env, TECHNEXUS_API_PROXY_TARGET: `http://127.0.0.1:${apiAddress.port}/api/**` },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const rememberNuxt = chunk => { nuxtOutput = `${nuxtOutput}${chunk}`.slice(-12000) }
  nuxt.stdout.on('data', rememberNuxt)
  nuxt.stderr.on('data', rememberNuxt)

  let chrome
  let chromeOutput = ''
  let browser
  try {
    await waitForHttp(`http://127.0.0.1:${webPort}/login`, nuxt, () => nuxtOutput)
    process.stdout.write('ui_smoke_step=nuxt-ready\n')
    const chromePath = chromeCandidates.find(path => path && process.getBuiltinModule('node:fs').existsSync(path))
    assert(chromePath, 'Chrome or Edge executable was not found; set TECHNEXUS_CHROME_PATH')
    chrome = spawn(chromePath, [
      '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
      `--remote-debugging-port=${chromePort}`, `--user-data-dir=${join(tempRoot, 'chrome-profile')}`, 'about:blank',
    ], { stdio: ['ignore', 'ignore', 'pipe'] })
    chrome.stderr.on('data', chunk => { chromeOutput = `${chromeOutput}${chunk}`.slice(-8000) })
    await waitForHttp(`http://127.0.0.1:${chromePort}/json/version`, chrome, () => chromeOutput || 'Chrome failed to expose DevTools')
    const page = await fetch(`http://127.0.0.1:${chromePort}/json/new?http://127.0.0.1:${webPort}/login`, { method: 'PUT' }).then(response => response.json())
    browser = new CdpClient(page.webSocketDebuggerUrl)
    await browser.connect()
    await browser.waitFor(() => browser.evaluate('document.body && document.body.innerText.includes("登录 TechNexus")'), 'login page')
    await browser.waitFor(() => browser.evaluate('Boolean(document.querySelector(".auth-card form")?.__vueParentComponent)'), 'Vue hydration')
    process.stdout.write('ui_smoke_step=chrome-ready\n')

    await setValue(browser, 'input[type="email"]', 'ui@example.com')
    await setValue(browser, 'input[type="password"]', 'correct-horse-battery-staple')
    await click(browser, '.auth-card form button')
    try {
      await browser.waitFor(() => browser.evaluate('location.pathname === "/contents"'), 'login redirect')
    } catch {
      const snapshot = await browser.evaluate('({ url: location.href, text: document.body.innerText })')
      throw new Error(`Login redirect failed: ${JSON.stringify(snapshot)}; requests=${state.requests.join(',')}`)
    }
    process.stdout.write('ui_smoke_step=login-pass\n')

    await click(browser, 'nav a[href="/me"]')
    await browser.waitFor(() => browser.evaluate('document.querySelector("input[autocomplete=nickname]")?.value === "原昵称"'), 'load profile')
    await setValue(browser, 'input[autocomplete="nickname"]', '用户端测试昵称')
    await setValue(browser, 'textarea[maxlength="1000"]', '个人中心回归')
    await click(browser, '.editor-card form button[type="submit"]')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("个人资料已保存")'), 'profile save')
    assert(state.profile.displayName === '用户端测试昵称' && state.profile.bio === '个人中心回归', 'Profile update not applied')
    process.stdout.write('ui_smoke_step=profile-pass\n')

    await browser.navigate(`http://127.0.0.1:${webPort}/contents/new`)
    await browser.waitFor(() => browser.evaluate('document.querySelectorAll("input[type=checkbox]").length === 2'), 'tag options')
    await click(browser, 'input[type="checkbox"]')
    await setFile(browser, uploadPath)
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("safe-attachment.txt")'), 'selected file')
    await click(browser, '.file-upload-row button')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("文件已通过安全扫描并加入附件列表")'), 'available attachment', 20000)
    await setValue(browser, 'input[maxlength="160"]', '浏览器自动化内容')
    await setValue(browser, 'textarea[maxlength="500"]', '真实 Chrome UI smoke')
    await setValue(browser, 'textarea[maxlength="200000"]', '浏览器自动化正文')
    await click(browser, 'form.editor-card .inline-actions .button.primary')
    await browser.waitFor(() => browser.evaluate('location.pathname === "/contents/content-1"'), 'content detail redirect')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("已关联 1 个标签") && document.body.innerText.includes("下载附件")'), 'saved associations')
    process.stdout.write('ui_smoke_step=association-pass\n')

    assert(state.uploadedBytes > 0, 'Object upload did not receive bytes')
    assert(state.createdBody?.tagIds?.[0] === 'tag-1', 'Selected tag was not sent to /api/v1/contents')
    assert(state.createdBody?.fileIds?.[0] === 'file-1', 'Available file was not sent to /api/v1/contents')

    state.createdBody.body = '# 安全富文本\n<img src=x onerror="window.__tnxXss=true">\n```java\nSystem.out.println("safe");\n```'
    await browser.navigate(`http://127.0.0.1:${webPort}/contents/content-1?case=rich-text-security`)
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("安全富文本") && document.body.innerText.includes("<img src=x")'), 'safe markdown body')
    assert(await browser.evaluate('window.__tnxXss !== true && document.querySelector(".safe-markdown img") === null'), 'Rich text payload executed as HTML')
    process.stdout.write('ui_smoke_step=rich-text-security-pass\n')

    state.tagsFail = true
    await browser.navigate(`http://127.0.0.1:${webPort}/contents/new?case=tags-fail`)
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("标签暂时无法加载")'), 'tag dependency error')

    state.tagsFail = false
    state.blockedUpload = true
    await browser.navigate(`http://127.0.0.1:${webPort}/contents/new?case=file-blocked`)
    await browser.waitFor(() => browser.evaluate('document.querySelector("input[type=file]") !== null'), 'file form')
    await setFile(browser, uploadPath)
    await click(browser, '.file-upload-row button')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("文件扫描结果：BLOCKED")'), 'blocked attachment feedback', 20000)
    assert(await browser.evaluate('document.querySelectorAll(".attachment-list li").length === 0'), 'Blocked file must not be attachable')

    await browser.navigate(`http://127.0.0.1:${webPort}/contents/forbidden`)
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("无权查看")'), 'permission error')
    process.stdout.write('ui_smoke_step=failure-feedback-pass\n')

    state.blockedUpload = false
    await browser.navigate(`http://127.0.0.1:${webPort}/me`)
    await browser.waitFor(() => browser.evaluate('document.querySelector(".avatar-editor input[type=file]:enabled") !== null'), 'avatar editor loaded')
    await browser.evaluate(`(async () => {
      const canvas = document.createElement('canvas')
      canvas.width = 16
      canvas.height = 8
      canvas.getContext('2d').fillRect(0, 0, 8, 8)
      const img = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'))
      const transfer = new DataTransfer()
      transfer.items.add(new File([img], 'avatar.png', { type: 'image/png' }))
      const input = document.querySelector('.avatar-editor input[type=file]')
      input.files = transfer.files
      input.dispatchEvent(new Event('change', { bubbles: true }))
    })()`)
    await browser.waitFor(() => browser.evaluate('document.querySelector(".avatar-actions .primary") !== null'), 'cropped avatar preview')
    await click(browser, '.avatar-actions .primary')
    await browser.waitFor(() => browser.evaluate('document.querySelector(".account-link img")?.naturalWidth === 512'), 'avatar displayed in navigation')
    process.stdout.write('ui_smoke_step=avatar-pass\n')

    await click(browser, '.email-settings > button')
    await setValue(browser, '.email-settings input[type=password]', 'correct-horse-battery-staple')
    await click(browser, '.email-settings button[type=submit]')
    await browser.waitFor(() => browser.evaluate('document.querySelector(".email-settings input[autocomplete=one-time-code]") !== null'), 'email verification form')
    await setValue(browser, '.email-settings input[autocomplete=one-time-code]', oldCode)
    await click(browser, '.email-settings button[type=submit]')
    await browser.waitFor(() => browser.evaluate('document.querySelector(".email-settings > button")?.textContent.includes("换绑邮箱")'), 'email verified in UI')
    await click(browser, '.email-settings > button')
    await setValue(browser, '.email-settings input[type=email]', 'new@example.test')
    await setValue(browser, '.email-settings input[type=password]', 'correct-horse-battery-staple')
    await click(browser, '.email-settings button[type=submit]')
    await browser.waitFor(() => browser.evaluate('document.querySelector(".email-settings input[autocomplete=off]") !== null'), 'dual-code form')
    await setValue(browser, '.email-settings input[autocomplete=one-time-code]', oldCode)
    await setValue(browser, '.email-settings input[autocomplete=off]', newCode)
    await click(browser, '.email-settings button[type=submit]')
    await browser.waitFor(() => browser.evaluate('location.pathname === "/login" && location.search.includes("emailChanged=1")'), 'relogin after email change')
    assert(state.security.email === 'new@example.test', 'Email change not applied')
    process.stdout.write('ui_smoke_step=email-change-pass\n')

    await click(browser, '.auth-card a[href="/forgot-password"]')
    await browser.waitFor(() => browser.evaluate('location.pathname === "/forgot-password" && !!document.querySelector("input[type=email]")'), 'forgot password form')
    await setValue(browser, 'input[type=email]', state.security.email)
    await click(browser, '.auth-card button[type=submit]')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("如果该邮箱符合重置条件")'), 'generic forgot password acknowledgment')
    await browser.navigate(`http://127.0.0.1:${webPort}/reset-password#token=${resetToken}`)
    await browser.waitFor(() => browser.evaluate('!location.hash && document.querySelectorAll("input[type=password]").length === 2'), 'reset form and token removed from location')
    await setValue(browser, '.auth-card label:nth-of-type(1) input', 'Browser-new-password!')
    await setValue(browser, '.auth-card label:nth-of-type(2) input', 'Different-password!')
    await click(browser, '.auth-card button[type=submit]')
    await browser.waitFor(() => browser.evaluate('document.body.innerText.includes("两次输入的密码不一致")'), 'password mismatch')
    await setValue(browser, '.auth-card label:nth-of-type(2) input', 'Browser-new-password!')
    await click(browser, '.auth-card button[type=submit]')
    await browser.waitFor(() => browser.evaluate('location.pathname === "/login" && location.search.includes("passwordReset=1")'), 'relogin after password reset')
    process.stdout.write('ui_smoke_step=password-reset-pass\n')

    process.stdout.write('ui_smoke_tests=9\nui_smoke_browser=Chromium-CDP\nui_smoke_gate=PASS\n')
  } finally {
    browser?.close()
    await stopChild(chrome)
    await stopChild(nuxt)
    const apiClosed = new Promise(resolvePromise => apiServer.close(resolvePromise))
    apiServer.closeAllConnections()
    await apiClosed
    try {
      await rm(tempRoot, { force: true, maxRetries: 10, recursive: true, retryDelay: 250 })
    } catch (error) {
      process.stderr.write(`Temporary UI directory cleanup warning: ${error.message}\n`)
    }
  }
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`)
  process.exitCode = 1
})
