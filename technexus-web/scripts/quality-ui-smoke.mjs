import { spawn, spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { setTimeout as wait } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
const browserCandidates = [
  { name: 'Microsoft Edge', path: process.env.TECHNEXUS_EDGE_PATH ?? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' },
  { name: 'Google Chrome', path: process.env.TECHNEXUS_CHROME_PATH ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe' },
].filter(candidate => existsSync(candidate.path))

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

function json(response, status, value) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify(value))
}

function mockApi(request, response) {
  const url = new URL(request.url ?? '/', `http://${request.headers.host}`)
  if (request.method === 'GET' && url.pathname === '/api/v1/contents') {
    json(response, 200, { items: [{ id: 'content-1', type: 'DOCUMENT', title: '可访问性测试内容', summary: '用于桌面和移动视口检查', state: 'PUBLISHED' }] })
    return
  }
  if (request.method === 'GET' && url.pathname === '/api/v1/demands') {
    json(response, 200, { items: [{ id: 'demand-1', title: '可访问性测试需求', description: '用于响应式阅读检查', state: 'PUBLISHED' }] })
    return
  }
  json(response, 404, { code: 'RESOURCE_NOT_FOUND' })
}

async function listen(server) {
  await new Promise((resolvePromise, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolvePromise)
  })
  return server.address()
}

async function freePort() {
  const server = createServer()
  const address = await listen(server)
  await new Promise(resolvePromise => server.close(resolvePromise))
  return address.port
}

async function waitForHttp(url, child, output, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (child?.exitCode !== null) throw new Error(`Process exited before ${url}\n${output()}`)
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) })
      if (response.status < 500) return response
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
  child.kill('SIGTERM')
  await Promise.race([new Promise(resolvePromise => child.once('exit', resolvePromise)), wait(5000)])
  if (child.exitCode === null) child.kill('SIGKILL')
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
    const rejectPending = () => {
      const error = new Error('Browser DevTools connection closed')
      for (const pending of this.pending.values()) pending.reject(error)
      this.pending.clear()
    }
    this.socket.addEventListener('close', rejectPending)
    this.socket.addEventListener('error', rejectPending)
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
    const response = await this.send('Runtime.evaluate', { awaitPromise: true, expression, returnByValue: true })
    if (response.exceptionDetails) throw new Error(response.exceptionDetails.text)
    return response.result.value
  }

  async navigate(url) {
    await this.send('Page.navigate', { url })
    await this.waitFor(() => this.evaluate('document.readyState === "complete"'), `load ${url}`)
  }

  async waitFor(check, message, timeoutMs = 15000) {
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

const auditExpression = `(() => {
  const issues = []
  const visible = element => {
    const style = getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0
  }
  const accessibleName = element => {
    const aria = element.getAttribute('aria-label') || element.getAttribute('aria-labelledby')
    if (aria) return aria
    if (element.id) {
      const label = document.querySelector('label[for="' + CSS.escape(element.id) + '"]')
      if (label?.innerText.trim()) return label.innerText.trim()
    }
    const wrappingLabel = element.closest('label')
    return wrappingLabel?.innerText.trim() || element.innerText?.trim() || element.getAttribute('title') || element.getAttribute('alt') || ''
  }
  const rgb = value => {
    const match = value.match(/rgba?\\((\\d+)[ ,]+(\\d+)[ ,]+(\\d+)(?:[ ,/]+([\\d.]+))?\\)/)
    return match ? { r: +match[1], g: +match[2], b: +match[3], a: match[4] === undefined ? 1 : +match[4] } : null
  }
  const luminance = color => {
    const values = [color.r, color.g, color.b].map(value => { const channel = value / 255; return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4 })
    return .2126 * values[0] + .7152 * values[1] + .0722 * values[2]
  }
  const background = element => {
    for (let current = element; current; current = current.parentElement) {
      const color = rgb(getComputedStyle(current).backgroundColor)
      if (color?.a === 1) return color
    }
    return { r: 255, g: 255, b: 255, a: 1 }
  }
  if (document.documentElement.lang !== 'zh-CN') issues.push('html lang must be zh-CN')
  if (!document.title.trim() || !document.title.includes('TechNexus')) issues.push('document title is missing or unbranded')
  if (!document.querySelector('meta[name="description"]')?.content.trim()) issues.push('meta description is missing')
  if (document.querySelectorAll('main').length !== 1) issues.push('page must have exactly one main landmark')
  if (document.querySelectorAll('h1').length !== 1) issues.push('page must have exactly one h1')
  if (document.documentElement.scrollWidth > innerWidth + 1) issues.push('horizontal overflow')
  const headings = [...document.querySelectorAll('h1,h2,h3,h4,h5,h6')].filter(visible)
  let lastLevel = 0
  for (const heading of headings) {
    const level = Number(heading.tagName.slice(1))
    if (lastLevel && level > lastLevel + 1) issues.push('heading level skipped before ' + heading.innerText.trim())
    lastLevel = level
  }
  for (const element of document.querySelectorAll('input,select,textarea,button,a[href]')) {
    if (visible(element) && !accessibleName(element)) issues.push('interactive element lacks accessible name: ' + element.tagName)
  }
  for (const element of document.querySelectorAll('button,.button,nav a,input,select,textarea')) {
    if (!visible(element)) continue
    const rect = element.getBoundingClientRect()
    if (rect.height < 43.5) issues.push('target height below 44px: ' + (accessibleName(element) || element.tagName))
  }
  for (const element of document.querySelectorAll('h1,h2,p,label,a,button')) {
    if (!visible(element) || !element.innerText.trim()) continue
    const style = getComputedStyle(element)
    const foreground = rgb(style.color)
    const behind = background(element)
    if (!foreground) continue
    const light = Math.max(luminance(foreground), luminance(behind))
    const dark = Math.min(luminance(foreground), luminance(behind))
    const ratio = (light + .05) / (dark + .05)
    const large = parseFloat(style.fontSize) >= 24 || (parseFloat(style.fontSize) >= 18.66 && Number(style.fontWeight) >= 700)
    if (ratio < (large ? 3 : 4.5)) issues.push('text contrast too low: ' + ratio.toFixed(2) + ' ' + element.innerText.trim().slice(0, 30))
  }
  return { issues: [...new Set(issues)], title: document.title, width: innerWidth, scrollWidth: document.documentElement.scrollWidth }
})()`

async function press(browser, key, code = key) {
  await browser.send('Input.dispatchKeyEvent', { type: 'keyDown', key, code })
  await browser.send('Input.dispatchKeyEvent', { type: 'keyUp', key, code })
}

async function auditBrowser(candidate, baseUrl, tempRoot) {
  process.stdout.write(`quality_step=browser-start name=${candidate.name}\n`)
  const debugPort = await freePort()
  let output = ''
  const child = spawn(candidate.path, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${debugPort}`, `--user-data-dir=${join(tempRoot, candidate.name.replaceAll(' ', '-'))}`, 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] })
  child.stderr.on('data', chunk => { output = `${output}${chunk}`.slice(-8000) })
  let browser
  try {
    await waitForHttp(`http://127.0.0.1:${debugPort}/json/version`, child, () => output)
    const page = await fetch(`http://127.0.0.1:${debugPort}/json/new?${baseUrl}`, { method: 'PUT' }).then(response => response.json())
    browser = new CdpClient(page.webSocketDebuggerUrl)
    await browser.connect()
    const audits = []
    const routes = ['/', '/contents', '/demands', '/login', '/register', '/does-not-exist']
    for (const viewport of [{ name: 'desktop', width: 1440, height: 900 }, { name: 'mobile', width: 390, height: 844 }]) {
      await browser.send('Emulation.setDeviceMetricsOverride', { width: viewport.width, height: viewport.height, deviceScaleFactor: 1, mobile: viewport.name === 'mobile' })
      for (const route of routes) {
        process.stdout.write(`quality_step=route browser=${candidate.name} viewport=${viewport.name} route=${route}\n`)
        await browser.navigate(`${baseUrl}${route}`)
        await browser.waitFor(() => browser.evaluate('Boolean(document.querySelector("main h1"))'), `${viewport.name} ${route} content`)
        const audit = await browser.evaluate(auditExpression)
        audits.push({ viewport: viewport.name, route, ...audit })
      }
    }
    await browser.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
    await browser.navigate(baseUrl)
    await browser.evaluate('document.body.focus()')
    await press(browser, 'Tab')
    const skipFocused = await browser.evaluate('document.activeElement?.classList.contains("skip-link") === true')
    await press(browser, 'Enter')
    await wait(150)
    const mainReached = await browser.evaluate('document.activeElement?.id === "main-content" || location.hash === "#main-content"')
    if (!skipFocused) audits.push({ viewport: 'keyboard', route: '/', issues: ['first Tab does not focus skip link'] })
    if (!mainReached) audits.push({ viewport: 'keyboard', route: '/', issues: ['skip link does not reach main content'] })
    process.stdout.write(`quality_step=browser-complete name=${candidate.name}\n`)
    return { browser: candidate.name, audits }
  } finally {
    browser?.close()
    await stopChild(child)
  }
}

async function main() {
  assert(browserCandidates.length > 0, 'No supported Chromium browser found')
  const apiServer = createServer(mockApi)
  const apiAddress = await listen(apiServer)
  const webPort = await freePort()
  const baseUrl = `http://127.0.0.1:${webPort}`
  const tempRoot = await mkdtemp(join(tmpdir(), 'technexus-quality-'))
  let nuxtOutput = ''
  const nuxt = spawn(process.execPath, [join(root, 'node_modules', '@nuxt', 'cli', 'bin', 'nuxi.mjs'), 'dev', '--host', '127.0.0.1', '--port', String(webPort)], {
    cwd: root,
    env: { ...process.env, TECHNEXUS_API_PROXY_TARGET: `http://127.0.0.1:${apiAddress.port}/api/**` },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const remember = chunk => { nuxtOutput = `${nuxtOutput}${chunk}`.slice(-12000) }
  nuxt.stdout.on('data', remember)
  nuxt.stderr.on('data', remember)
  try {
    await waitForHttp(baseUrl, nuxt, () => nuxtOutput)
    const ssrResults = []
    for (const route of ['/', '/contents', '/demands', '/login', '/register']) {
      const response = await fetch(`${baseUrl}${route}`)
      const html = await response.text()
      const issues = []
      if (response.status !== 200) issues.push(`SSR status ${response.status}`)
      if (!/<html[^>]+lang="zh-CN"/.test(html)) issues.push('SSR lang missing')
      if (!/<title>[^<]*TechNexus[^<]*<\/title>/.test(html)) issues.push('SSR title missing')
      if (!/<meta[^>]+name="description"[^>]+content="[^"]+"/.test(html)) issues.push('SSR description missing')
      ssrResults.push({ route, status: response.status, issues })
      process.stdout.write(`quality_step=ssr route=${route} issues=${issues.length}\n`)
    }
    const browserResults = []
    for (const candidate of browserCandidates) browserResults.push(await auditBrowser(candidate, baseUrl, tempRoot))
    const issues = [
      ...ssrResults.flatMap(item => item.issues.map(issue => `SSR ${item.route}: ${issue}`)),
      ...browserResults.flatMap(result => result.audits.flatMap(audit => audit.issues.map(issue => `${result.browser} ${audit.viewport} ${audit.route}: ${issue}`))),
    ]
    const summary = {
      classification: browserCandidates.length >= 2 ? 'LOCAL_BROWSER_MATRIX' : 'LOCAL_SINGLE_ENGINE',
      gatingEvidence: false,
      browsers: browserCandidates.map(candidate => candidate.name),
      viewports: ['1440x900', '390x844'],
      ssrRoutes: ssrResults.length,
      browserRouteChecks: browserResults.reduce((count, result) => count + result.audits.length, 0),
      issues,
      result: issues.length === 0 ? 'PASS_LOCAL' : 'FAIL',
    }
    process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`)
    if (issues.length > 0) process.exitCode = 1
  } finally {
    await stopChild(nuxt)
    const apiClosed = new Promise(resolvePromise => apiServer.close(resolvePromise))
    apiServer.closeAllConnections()
    await apiClosed
    await rm(tempRoot, { force: true, maxRetries: 10, recursive: true, retryDelay: 250 })
  }
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`)
  process.exitCode = 1
})
