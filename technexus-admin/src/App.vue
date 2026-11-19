<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError } from './api'

type Section = 'dashboard' | 'audit' | 'pricing' | 'config' | 'ai' | 'operations'
type Dashboard = { pendingAudit: number, pendingPricing: number, failedJobs: number, storageAlert: boolean, backupAlert: boolean }
type AuditTask = { id: string, targetType: string, targetId: string, targetVersionId: string, state: string, version: number }
type ConfigView = { key: string, value: unknown, version: number }
type Suggestion = { id: string, kind: string, targetType: string, targetId: string, targetVersionId: string, state: string, isSuggestion: true, output: Record<string, unknown>, version: number }
type FeatureFlag = { key: string, enabled: boolean, rule: unknown, version: number }
type OperationAudit = { id: number, actorId: string | null, action: string, target: Record<string, unknown>, createdAt: string }
type ManagedTag = { id: string, name: string, aliases: string[], enabled: boolean, mergedIntoId: string | null, version: number }
type VersionedItems = { version: number, [key: string]: unknown }
type BatchJob = { id: string, operation: string, status: string, totalCount: number, successCount: number, failureCount: number, reason: string, createdAt: string, items: Array<{ sequence: number, targetId: string, status: string, errorCode: string | null, resultVersion: number | null }> }
type AuthResponse = { accessToken: string, csrfToken: string, expiresIn: number, tokenType: string }

const token = ref('')
const csrfToken = ref(sessionStorage.getItem('tn_admin_csrf') ?? '')
const account = ref(sessionStorage.getItem('tn_admin_account') ?? '')
const password = ref('')
const sessionReady = ref(false)
const active = ref<Section>('dashboard')
const busy = ref(false)
const error = ref('')
const message = ref('')
const dashboard = ref<Dashboard | null>(null)
const auditState = ref('PENDING')
const auditTasks = ref<AuditTask[]>([])
const selectedAudit = ref<AuditTask | null>(null)
const auditDecision = ref('APPROVED')
const auditReason = ref('LOW_QUALITY')
const auditNote = ref('')
const price = ref({ targetType: 'DEMAND', targetId: '', reason: '', expectedVersion: 0 })
const config = ref({ key: 'feature.content.ai', value: '{"enabled":false}', reason: '', version: 0 })
const flags = ref<FeatureFlag[]>([])
const flag = ref({ key: 'registration.enabled', enabled: false, rule: '{"percentage":0}', reason: '', version: 0 })
const suggestion = ref({ kind: 'SUMMARY', targetType: 'CONTENT', targetId: '', targetVersionId: '' })
const createdSuggestion = ref<Suggestion | null>(null)
const aiState = ref('')
const suggestions = ref<Suggestion[]>([])
const aiDecision = ref({ id: '', decision: 'ACCEPT', editedOutput: '{}', version: 0 })
const operationAction = ref('')
const operationAudits = ref<OperationAudit[]>([])
const managedTags = ref<ManagedTag[]>([])
const tagForm = ref({ id: '', name: '', aliases: '', enabled: true, version: 0, reason: '' })
const tagMerge = ref({ sourceId: '', targetId: '', sourceVersion: 0, targetVersion: 0, reason: '' })
const categoryConfig = ref({ json: '[]', version: 0, reason: '' })
const recommendationConfig = ref({ json: '[]', version: 0, reason: '' })
const batchForm = ref({ enabled: false, items: '[]', reason: '' })
const batchJobs = ref<BatchJob[]>([])
const selectedBatch = ref<BatchJob | null>(null)
const dashboardCards = computed(() => dashboard.value ? [
  ['待审核', dashboard.value.pendingAudit], ['待定价', dashboard.value.pendingPricing],
  ['失败任务', dashboard.value.failedJobs], ['存储告警', dashboard.value.storageAlert ? '是' : '否'],
  ['备份告警', dashboard.value.backupAlert ? '是' : '否'],
] : [])
const auditStateOptions = [
  { value: 'CREATED', label: '已创建' },
  { value: 'PENDING', label: '待审核' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已拒绝' },
] as const
const pricingTargetTypeOptions = [
  { value: 'DEMAND', label: '需求' },
  { value: 'DOCUMENT', label: '文档' },
  { value: 'ATTACHMENT', label: '附件' },
  { value: 'SERVICE', label: '服务' },
] as const
const auditDecisionOptions = [
  { value: 'APPROVED', label: '通过' },
  { value: 'REJECTED', label: '拒绝' },
] as const
const auditReasonOptions = [
  { value: 'LOW_QUALITY', label: '质量较低' },
  { value: 'INCOMPLETE', label: '内容不完整' },
  { value: 'ILLEGAL', label: '违法违规' },
  { value: 'ADVERTISING', label: '广告营销' },
  { value: 'COPYRIGHT', label: '版权问题' },
  { value: 'SENSITIVE', label: '敏感内容' },
  { value: 'FILE_RISK', label: '文件风险' },
  { value: 'INVALID_PRICE', label: '定价无效' },
  { value: 'OTHER', label: '其他' },
] as const
const aiStateOptions = [
  { value: 'QUEUED', label: '排队中' },
  { value: 'RUNNING', label: '处理中' },
  { value: 'SUCCEEDED', label: '已生成' },
  { value: 'FAILED', label: '生成失败' },
  { value: 'ACCEPTED', label: '已采纳' },
  { value: 'EDITED', label: '编辑后采纳' },
  { value: 'REJECTED', label: '已拒绝' },
] as const
const aiKindOptions = [
  { value: 'TAGS', label: '标签建议' },
  { value: 'SUMMARY', label: '摘要建议' },
  { value: 'CATEGORY', label: '分类建议' },
  { value: 'DEMAND_STRUCTURE', label: '需求结构化' },
  { value: 'AUDIT_REASON', label: '审核原因建议' },
] as const
const aiTargetTypeOptions = [
  { value: 'CONTENT', label: '内容' },
  { value: 'DEMAND', label: '需求' },
  { value: 'AUDIT_TASK', label: '审核任务' },
] as const
const aiDecisionOptions = [
  { value: 'ACCEPT', label: '采纳' },
  { value: 'EDIT', label: '编辑后采纳' },
  { value: 'REJECT', label: '拒绝' },
] as const
const operationActionOptions = [
  { value: 'CONFIG_UPDATE', label: '更新系统参数' },
  { value: 'FEATURE_FLAG_UPDATE', label: '更新功能开关' },
  { value: 'TAG_CREATE', label: '创建标签' },
  { value: 'TAG_UPDATE', label: '更新标签' },
  { value: 'TAG_MERGE', label: '合并标签' },
  { value: 'AI_SUGGESTION_ACCEPT', label: '采纳 AI 建议' },
  { value: 'AI_SUGGESTION_EDIT', label: '编辑后采纳 AI 建议' },
  { value: 'AI_SUGGESTION_REJECT', label: '拒绝 AI 建议' },
] as const
const auditStateLabels = Object.fromEntries(auditStateOptions.map(item => [item.value, item.label]))
const auditTargetTypeLabels: Record<string, string> = { CONTENT: '内容', DEMAND: '需求' }
const aiStateLabels = Object.fromEntries(aiStateOptions.map(item => [item.value, item.label]))
const aiKindLabels = Object.fromEntries(aiKindOptions.map(item => [item.value, item.label]))
const aiTargetTypeLabels = Object.fromEntries(aiTargetTypeOptions.map(item => [item.value, item.label]))
const operationActionLabels = Object.fromEntries(operationActionOptions.map(item => [item.value, item.label]))
const batchOperationLabels: Record<string, string> = { TAG_ENABLE: '批量启用标签', TAG_DISABLE: '批量停用标签' }
const batchStatusLabels: Record<string, string> = {
  RUNNING: '执行中', SUCCEEDED: '全部成功', FAILED: '全部失败', PARTIAL_FAILED: '部分失败',
}
const batchItemStatusLabels: Record<string, string> = { QUEUED: '待处理', RUNNING: '处理中', SUCCEEDED: '成功', FAILED: '失败' }
const errorCodeLabels: Record<string, string> = {
  VERSION_CONFLICT: '版本冲突', RESOURCE_NOT_FOUND: '资源不存在', AUTH_REQUIRED: '操作人不存在',
  TAG_NAME_CONFLICT: '标签名称或别名冲突', TAG_EXISTS: '标签已存在',
}

function auditStateLabel(value: string) { return auditStateLabels[value] ?? value }
function enumLabel(labels: Record<string, string>, value: string | null) { return value ? labels[value] ?? value : '—' }

function persistSession() {
  sessionStorage.setItem('tn_admin_token', token.value)
  sessionStorage.setItem('tn_admin_csrf', csrfToken.value)
  sessionStorage.setItem('tn_admin_account', account.value)
}
function clearSession() {
  token.value = ''
  csrfToken.value = ''
  password.value = ''
  dashboard.value = null
  sessionStorage.removeItem('tn_admin_token')
  sessionStorage.removeItem('tn_admin_csrf')
}
async function verifyAdministrator() {
  dashboard.value = (await api<{ data: Dashboard }>('/admin/dashboard', token.value)).data
}
async function refreshAccessToken() {
  const response = await api<AuthResponse>('/auth/refresh', '', {
    method: 'POST', headers: { 'X-CSRF-Token': csrfToken.value },
  })
  token.value = response.accessToken
  csrfToken.value = response.csrfToken
  persistSession()
}
async function revokeServerSession() {
  if (!csrfToken.value) return
  await api('/auth/logout', token.value, { method: 'POST', headers: { 'X-CSRF-Token': csrfToken.value } })
}
async function login() {
  busy.value = true; error.value = ''; message.value = ''
  try {
    const response = await api<AuthResponse>('/auth/login', '', {
      method: 'POST', body: { account: account.value, password: password.value },
    })
    token.value = response.accessToken
    csrfToken.value = response.csrfToken
    persistSession()
    try {
      await verifyAdministrator()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 403) {
        await revokeServerSession().catch(() => undefined)
        throw new Error('该账号没有管理端权限。')
      }
      throw cause
    }
    password.value = ''
    message.value = '登录成功。'
  } catch (cause) {
    clearSession()
    error.value = cause instanceof ApiError && cause.status === 401
      ? '账号或密码错误。'
      : cause instanceof Error ? cause.message : '登录失败，请稍后重试。'
  } finally { busy.value = false }
}
async function logout() {
  busy.value = true
  try {
    await revokeServerSession()
  } catch { /* The local session must still be cleared if the server is unavailable. */ }
  finally {
    clearSession(); error.value = ''; message.value = ''; busy.value = false
  }
}
async function restoreSession() {
  const storedToken = sessionStorage.getItem('tn_admin_token') ?? ''
  try {
    if (storedToken) {
      token.value = storedToken
      try {
        await verifyAdministrator()
        return
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 401 || !csrfToken.value) throw cause
      }
    }
    if (csrfToken.value) {
      await refreshAccessToken()
      await verifyAdministrator()
    }
  } catch {
    clearSession()
  } finally { sessionReady.value = true }
}
async function run(action: () => Promise<void>, success: string) {
  busy.value = true; error.value = ''; message.value = ''
  try { await action(); message.value = success }
  catch (cause) {
    if (cause instanceof ApiError && cause.status === 401) {
      clearSession(); error.value = '登录已过期，请重新登录。'
    } else {
      error.value = cause instanceof Error ? `操作失败：${cause.message}` : '操作失败，请检查权限和输入。'
    }
  }
  finally { busy.value = false }
}
async function loadDashboard() {
  await run(async () => { dashboard.value = (await api<{ data: Dashboard }>('/admin/dashboard', token.value)).data }, '运营数据已刷新。')
}
async function loadAudits() {
  await run(async () => {
    const query = auditState.value ? `?state=${encodeURIComponent(auditState.value)}` : ''
    auditTasks.value = (await api<{ items: AuditTask[] }>(`/admin/audit-tasks${query}`, token.value)).items
    selectedAudit.value = null
  }, '审核任务已刷新。')
}
async function decideAudit() {
  const task = selectedAudit.value; if (!task) return
  await run(async () => {
    await api(`/admin/audit-tasks/${task.id}/decisions`, token.value, { method: 'POST', body: { decision: auditDecision.value, reasonCode: auditReason.value, note: auditNote.value || null, expectedTaskVersion: task.version } })
    await loadAudits()
  }, '审核决定已保存。')
}
async function confirmPrice() {
  await run(async () => { await api(`/admin/prices/${price.value.targetType}/${price.value.targetId}/confirmations`, token.value, { method: 'POST', body: { mode: 'FREE', amount: { amount: '0.00', currency: 'CNY' }, validFrom: new Date().toISOString(), reason: price.value.reason, expectedVersion: price.value.expectedVersion } }) }, '免费定价已确认。')
}
async function loadConfig() {
  await run(async () => {
    const data = (await api<{ data: ConfigView }>(`/admin/config/${encodeURIComponent(config.value.key)}`, token.value)).data
    config.value.value = JSON.stringify(data.value, null, 2); config.value.version = data.version
  }, '配置已加载。')
}
async function saveConfig() {
  await run(async () => {
    const response = await api<{ data: ConfigView }>(`/admin/config/${encodeURIComponent(config.value.key)}`, token.value, { method: 'PUT', body: { value: JSON.parse(config.value.value), expectedVersion: config.value.version, reason: config.value.reason } })
    config.value.version = response.data.version
  }, '配置已更新并写入操作审计。')
}
async function loadFlags() {
  await run(async () => { flags.value = (await api<{ items: FeatureFlag[] }>('/admin/feature-flags', token.value)).items }, '功能开关已刷新。')
}
function editFlag(value: FeatureFlag) {
  Object.assign(flag.value, { key: value.key, enabled: value.enabled, rule: JSON.stringify(value.rule ?? {}, null, 2), version: value.version })
}
async function saveFlag() {
  await run(async () => {
    const response = await api<{ data: FeatureFlag }>(`/admin/feature-flags/${encodeURIComponent(flag.value.key)}`, token.value, { method: 'PUT', body: { enabled: flag.value.enabled, rule: JSON.parse(flag.value.rule), expectedVersion: flag.value.version, reason: flag.value.reason } })
    editFlag(response.data); await loadFlags()
  }, '功能开关已更新并写入操作审计。')
}
async function loadCategoryConfig() {
  await run(async () => {
    const data = (await api<{ data: VersionedItems & { categories: unknown[] } }>('/admin/category-config', token.value)).data
    categoryConfig.value.json = JSON.stringify(data.categories, null, 2); categoryConfig.value.version = data.version
  }, '分类配置已加载。')
}
async function saveCategoryConfig() {
  await run(async () => {
    const data = (await api<{ data: VersionedItems & { categories: unknown[] } }>('/admin/category-config', token.value, { method: 'PUT', body: { categories: JSON.parse(categoryConfig.value.json), expectedVersion: categoryConfig.value.version, reason: categoryConfig.value.reason } })).data
    categoryConfig.value.version = data.version; categoryConfig.value.json = JSON.stringify(data.categories, null, 2)
  }, '分类配置已更新并审计。')
}
async function loadRecommendationConfig() {
  await run(async () => {
    const data = (await api<{ data: VersionedItems & { items: unknown[] } }>('/admin/recommendation-config', token.value)).data
    recommendationConfig.value.json = JSON.stringify(data.items, null, 2); recommendationConfig.value.version = data.version
  }, '推荐配置已加载。')
}
async function saveRecommendationConfig() {
  await run(async () => {
    const data = (await api<{ data: VersionedItems & { items: unknown[] } }>('/admin/recommendation-config', token.value, { method: 'PUT', body: { items: JSON.parse(recommendationConfig.value.json), expectedVersion: recommendationConfig.value.version, reason: recommendationConfig.value.reason } })).data
    recommendationConfig.value.version = data.version; recommendationConfig.value.json = JSON.stringify(data.items, null, 2)
  }, '推荐配置已更新并审计。')
}
async function createSuggestion() {
  await run(async () => {
    createdSuggestion.value = (await api<{ data: Suggestion }>('/admin/ai-suggestions', token.value, { method: 'POST', body: suggestion.value })).data
    Object.assign(aiDecision.value, { id: createdSuggestion.value.id, version: createdSuggestion.value.version })
  }, 'AI 建议任务已创建；等待完成后再由人工决定。')
}
async function loadSuggestions() {
  await run(async () => {
    const query = aiState.value ? `?state=${encodeURIComponent(aiState.value)}` : ''
    suggestions.value = (await api<{ items: Suggestion[] }>(`/admin/ai-suggestions${query}`, token.value)).items
  }, 'AI 建议列表已刷新。')
}
function selectSuggestion(value: Suggestion) {
  createdSuggestion.value = value
  Object.assign(aiDecision.value, { id: value.id, version: value.version })
}
async function decideSuggestion() {
  await run(async () => {
    const editedOutput = aiDecision.value.decision === 'EDIT' ? JSON.parse(aiDecision.value.editedOutput) : null
    createdSuggestion.value = (await api<{ data: Suggestion }>(`/admin/ai-suggestions/${aiDecision.value.id}/decisions`, token.value, { method: 'POST', body: { decision: aiDecision.value.decision, editedOutput, expectedVersion: aiDecision.value.version } })).data
    aiDecision.value.version = createdSuggestion.value.version
  }, 'AI 建议的人工决定已保存。')
}
async function loadOperationAudits() {
  await run(async () => {
    const query = operationAction.value ? `?action=${encodeURIComponent(operationAction.value)}` : ''
    operationAudits.value = (await api<{ items: OperationAudit[] }>(`/admin/operation-audits${query}`, token.value)).items
  }, '操作审计已刷新。')
}
function aliasValues(value: string) {
  return [...new Set(value.split(/[\n,]/).map(item => item.trim()).filter(Boolean))]
}
async function loadManagedTags() {
  await run(async () => { managedTags.value = (await api<{ items: ManagedTag[] }>('/admin/tags', token.value)).items }, '标签列表已刷新。')
}
function editManagedTag(value: ManagedTag) {
  Object.assign(tagForm.value, { id: value.id, name: value.name, aliases: value.aliases.join('\n'), enabled: value.enabled, version: value.version, reason: '' })
}
function newManagedTag() {
  Object.assign(tagForm.value, { id: '', name: '', aliases: '', enabled: true, version: 0, reason: '' })
}
async function saveManagedTag() {
  await run(async () => {
    if (tagForm.value.id) {
      await api(`/admin/tags/${tagForm.value.id}`, token.value, { method: 'PUT', body: { enabled: tagForm.value.enabled, aliases: aliasValues(tagForm.value.aliases), expectedVersion: tagForm.value.version, reason: tagForm.value.reason } })
    } else {
      await api('/admin/tags', token.value, { method: 'POST', body: { name: tagForm.value.name, aliases: aliasValues(tagForm.value.aliases), reason: tagForm.value.reason } })
    }
    newManagedTag(); await loadManagedTags()
  }, '标签已保存并写入操作审计。')
}
async function mergeManagedTag() {
  await run(async () => {
    await api(`/admin/tags/${tagMerge.value.sourceId}/merges`, token.value, { method: 'POST', body: { targetTagId: tagMerge.value.targetId, expectedSourceVersion: tagMerge.value.sourceVersion, expectedTargetVersion: tagMerge.value.targetVersion, reason: tagMerge.value.reason } })
    await loadManagedTags()
  }, '标签关系与别名已合并。')
}
async function loadBatchJobs() {
  await run(async () => { batchJobs.value = (await api<{ items: BatchJob[] }>('/admin/batches', token.value)).items }, '批量任务已刷新。')
}
async function loadBatchDetail(id: string) {
  await run(async () => { selectedBatch.value = (await api<{ data: BatchJob }>(`/admin/batches/${id}`, token.value)).data }, '批量任务逐项结果已加载。')
}
async function executeTagBatch() {
  await run(async () => {
    selectedBatch.value = (await api<{ data: BatchJob }>('/admin/tag-batches', token.value, { method: 'POST', body: { enabled: batchForm.value.enabled, items: JSON.parse(batchForm.value.items), reason: batchForm.value.reason } })).data
    await loadBatchJobs()
  }, '标签批处理已完成；失败项未回滚成功项。')
}
function show(section: Section) { active.value = section; error.value = ''; message.value = '' }
onMounted(restoreSession)
</script>

<template>
  <div v-if="!sessionReady" class="auth-shell" aria-live="polite">
    <div class="auth-status"><span class="brand-mark">TN</span><p>正在检查管理会话…</p></div>
  </div>
  <div v-else-if="!token" class="auth-shell">
    <main class="login-card">
      <div class="login-brand"><span class="brand-mark">TN</span><div><strong>TechNexus</strong><span>管理端</span></div></div>
      <div class="login-heading"><p>运营管理控制台</p><h1>管理员登录</h1><span>使用具有管理员权限的 TechNexus 账号继续。</span></div>
      <form class="login-form" @submit.prevent="login">
        <label for="admin-account">账号或邮箱<input id="admin-account" v-model.trim="account" name="account" type="text" autocomplete="username" required autofocus placeholder="name@example.com"></label>
        <label for="admin-password">密码<input id="admin-password" v-model="password" name="password" type="password" autocomplete="current-password" required maxlength="128" placeholder="请输入密码"></label>
        <p v-if="error" class="error auth-message" role="alert">{{ error }}</p>
        <button type="submit" :disabled="busy || !account || !password">{{ busy ? '正在验证…' : '登录' }}</button>
      </form>
      <p class="login-note">管理端仅允许管理员账号访问。首次使用请先以部署时配置的 Bootstrap 管理员邮箱在用户端完成注册；登录失败和越权访问会由服务端限流并记录。</p>
    </main>
  </div>
  <div v-else class="shell">
    <aside><strong>TechNexus</strong><span>管理端</span><nav aria-label="管理导航"><button v-for="item in ([['dashboard', '运营总览'], ['audit', '审核任务'], ['pricing', '定价确认'], ['config', '配置中心'], ['ai', 'AI 建议'], ['operations', '运营能力']] as const)" :key="item[0]" :class="{ active: active === item[0] }" @click="show(item[0])">{{ item[1] }}</button></nav></aside>
    <main>
      <header><div><p>运营管理</p><h1>{{ { dashboard: '运营总览', audit: '审核工作台', pricing: '定价中心', config: '配置中心', ai: 'AI 建议', operations: '运营能力边界' }[active] }}</h1></div><div class="session"><span>{{ account }}</span><button class="secondary" :disabled="busy" @click="logout">退出登录</button></div></header>
      <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="message" class="success" role="status">{{ message }}</p>
      <template v-if="active === 'dashboard'"><div class="actions"><button :disabled="busy || !token" @click="loadDashboard">刷新数据</button></div><section class="cards" aria-label="运营指标"><article v-for="card in dashboardCards" :key="String(card[0])"><span>{{ card[0] }}</span><strong>{{ card[1] }}</strong></article><article v-if="!dashboard"><span>等待连接</span><strong>—</strong></article></section></template>
      <section v-else-if="active === 'audit'" class="panel wide"><div class="toolbar"><label>状态<select v-model="auditState"><option value="">全部</option><option v-for="state in auditStateOptions" :key="state.value" :value="state.value">{{ state.label }}</option></select></label><button :disabled="busy || !token" @click="loadAudits">查询</button></div><p v-if="!auditTasks.length" class="muted">暂无已加载任务。</p><div class="table-wrap"><table v-if="auditTasks.length"><thead><tr><th>状态</th><th>目标</th><th>版本</th><th></th></tr></thead><tbody><tr v-for="task in auditTasks" :key="task.id"><td>{{ auditStateLabel(task.state) }}</td><td><code>{{ enumLabel(auditTargetTypeLabels, task.targetType) }} / {{ task.targetId }}</code></td><td>{{ task.version }}</td><td><button class="secondary" @click="selectedAudit = task">处理</button></td></tr></tbody></table></div><form v-if="selectedAudit" class="form-grid" @submit.prevent="decideAudit"><h2>决定任务 {{ selectedAudit.id }}</h2><label>决定<select v-model="auditDecision"><option v-for="decision in auditDecisionOptions" :key="decision.value" :value="decision.value">{{ decision.label }}</option></select></label><label>原因<select v-model="auditReason"><option v-for="reason in auditReasonOptions" :key="reason.value" :value="reason.value">{{ reason.label }}</option></select></label><label class="full">说明<textarea v-model="auditNote" maxlength="2000"></textarea></label><button :disabled="busy">提交人工决定</button></form></section>
      <form v-else-if="active === 'pricing'" class="panel form-grid" @submit.prevent="confirmPrice"><p class="notice">当前版本仅支持免费模式，其他定价模式会由后端拒绝。</p><label>目标类型<select v-model="price.targetType"><option v-for="targetType in pricingTargetTypeOptions" :key="targetType.value" :value="targetType.value">{{ targetType.label }}</option></select></label><label>目标 UUID<input v-model.trim="price.targetId" required></label><label>当前版本<input v-model.number="price.expectedVersion" min="0" type="number"></label><label class="full">确认原因<textarea v-model.trim="price.reason" required maxlength="1000"></textarea></label><button :disabled="busy || !token">确认免费定价</button></form>
      <section v-else-if="active === 'config'" class="stack">
        <div class="panel wide"><h2>系统参数</h2><div class="form-grid"><label>配置键<input v-model.trim="config.key" pattern="[a-z][a-z0-9_.-]{2,127}"></label><div class="actions bottom"><button class="secondary" :disabled="busy || !token" @click="loadConfig">读取</button></div><label class="full">JSON 值<textarea v-model="config.value" rows="6"></textarea></label><label>版本<input v-model.number="config.version" min="0" type="number"></label><label class="full">变更原因<textarea v-model.trim="config.reason" maxlength="1000"></textarea></label><button :disabled="busy || !token || !config.reason" @click="saveConfig">保存配置</button></div><p class="muted">新键使用版本 0；已有键必须先读取。</p></div>
        <div class="panel wide"><h2>功能开关</h2><div class="actions"><button class="secondary" :disabled="busy || !token" @click="loadFlags">刷新列表</button></div><div class="table-wrap"><table v-if="flags.length"><thead><tr><th>标识</th><th>启用</th><th>版本</th><th></th></tr></thead><tbody><tr v-for="item in flags" :key="item.key"><td><code>{{ item.key }}</code></td><td>{{ item.enabled ? '是' : '否' }}</td><td>{{ item.version }}</td><td><button class="secondary" @click="editFlag(item)">编辑</button></td></tr></tbody></table></div><div class="form-grid"><label>功能开关键<input v-model.trim="flag.key" pattern="[a-z][a-z0-9_.-]{2,127}"></label><label class="checkbox"><input v-model="flag.enabled" type="checkbox">启用</label><label class="full">灰度规则 JSON<textarea v-model="flag.rule" rows="4"></textarea></label><label>版本<input v-model.number="flag.version" min="0" type="number"></label><label class="full">变更原因<textarea v-model.trim="flag.reason" maxlength="1000"></textarea></label><button :disabled="busy || !token || !flag.reason" @click="saveFlag">保存功能开关</button></div></div>
        <div class="panel wide"><h2>分类配置</h2><p class="muted">可扩展分类；首页必须设置 2–3 个启用的核心方向，coreRank 为 1–3 且唯一。</p><div class="actions"><button class="secondary" :disabled="busy || !token" @click="loadCategoryConfig">读取</button></div><div class="form-grid"><label class="full">分类 JSON<textarea v-model="categoryConfig.json" rows="10"></textarea></label><label>版本<input v-model.number="categoryConfig.version" type="number" min="0" disabled></label><label class="full">变更原因<textarea v-model.trim="categoryConfig.reason" maxlength="1000"></textarea></label><button :disabled="busy || !token || !categoryConfig.reason" @click="saveCategoryConfig">保存分类配置</button></div></div>
        <div class="panel wide"><h2>运营置顶推荐</h2><p class="muted">运营置顶优先于算法、热度和发布时间；目标与 rank 必须唯一。</p><div class="actions"><button class="secondary" :disabled="busy || !token" @click="loadRecommendationConfig">读取</button></div><div class="form-grid"><label class="full">推荐 JSON<textarea v-model="recommendationConfig.json" rows="8"></textarea></label><label>版本<input v-model.number="recommendationConfig.version" type="number" min="0" disabled></label><label class="full">变更原因<textarea v-model.trim="recommendationConfig.reason" maxlength="1000"></textarea></label><button :disabled="busy || !token || !recommendationConfig.reason" @click="saveRecommendationConfig">保存推荐配置</button></div></div>
      </section>
      <section v-else-if="active === 'ai'" class="stack"><div class="panel wide"><h2>建议任务列表</h2><div class="toolbar"><label>状态<select v-model="aiState"><option value="">全部</option><option v-for="state in aiStateOptions" :key="state.value" :value="state.value">{{ state.label }}</option></select></label><button :disabled="busy || !token" @click="loadSuggestions">查询</button></div><p v-if="!suggestions.length" class="muted">暂无已加载建议。</p><div class="table-wrap"><table v-if="suggestions.length"><thead><tr><th>状态</th><th>类型</th><th>目标</th><th>版本</th><th></th></tr></thead><tbody><tr v-for="item in suggestions" :key="item.id"><td>{{ enumLabel(aiStateLabels, item.state) }}</td><td>{{ enumLabel(aiKindLabels, item.kind) }}</td><td><code>{{ enumLabel(aiTargetTypeLabels, item.targetType) }} / {{ item.targetId }}</code></td><td>{{ item.version }}</td><td><button class="secondary" @click="selectSuggestion(item)">选择</button></td></tr></tbody></table></div></div><form class="panel form-grid" @submit.prevent="createSuggestion"><h2>创建建议任务</h2><label>建议类型<select v-model="suggestion.kind"><option v-for="kind in aiKindOptions" :key="kind.value" :value="kind.value">{{ kind.label }}</option></select></label><label>目标类型<select v-model="suggestion.targetType"><option v-for="targetType in aiTargetTypeOptions" :key="targetType.value" :value="targetType.value">{{ targetType.label }}</option></select></label><label>目标 UUID<input v-model.trim="suggestion.targetId" required></label><label>目标版本 UUID<input v-model.trim="suggestion.targetVersionId" required></label><button :disabled="busy || !token">创建任务</button><pre v-if="createdSuggestion" class="full">{{ createdSuggestion }}</pre></form><form class="panel form-grid" @submit.prevent="decideSuggestion"><h2>人工接受、编辑或拒绝</h2><label>建议 UUID<input v-model.trim="aiDecision.id" required></label><label>当前版本<input v-model.number="aiDecision.version" min="0" type="number"></label><label>决定<select v-model="aiDecision.decision"><option v-for="decision in aiDecisionOptions" :key="decision.value" :value="decision.value">{{ decision.label }}</option></select></label><label v-if="aiDecision.decision === 'EDIT'" class="full">编辑后的 JSON<textarea v-model="aiDecision.editedOutput" rows="6"></textarea></label><button :disabled="busy || !token">保存人工决定</button></form></section>
      <section v-else class="stack">
        <div class="panel wide">
          <h2>标准标签治理</h2>
          <div class="actions"><button class="secondary" :disabled="busy || !token" @click="loadManagedTags">刷新标签</button><button class="secondary" @click="newManagedTag">新建</button></div>
          <div class="table-wrap"><table v-if="managedTags.length"><thead><tr><th>标准名</th><th>别名</th><th>状态</th><th>版本</th><th></th></tr></thead><tbody><tr v-for="item in managedTags" :key="item.id"><td><code>{{ item.name }}</code></td><td>{{ item.aliases.join('、') || '—' }}</td><td>{{ item.mergedIntoId ? `已合并至 ${item.mergedIntoId}` : item.enabled ? '启用' : '停用' }}</td><td>{{ item.version }}</td><td><button class="secondary" :disabled="!!item.mergedIntoId" @click="editManagedTag(item)">编辑</button></td></tr></tbody></table></div>
          <div class="form-grid"><h3>{{ tagForm.id ? '编辑标签' : '新建标签' }}</h3><label>标准名<input v-model.trim="tagForm.name" :disabled="!!tagForm.id" maxlength="48" required></label><label class="checkbox"><input v-model="tagForm.enabled" type="checkbox" :disabled="!tagForm.id">启用</label><label class="full">别名（逗号或换行分隔）<textarea v-model="tagForm.aliases" rows="4"></textarea></label><label>版本<input v-model.number="tagForm.version" type="number" min="0" disabled></label><label class="full">变更原因<textarea v-model.trim="tagForm.reason" maxlength="1000" required></textarea></label><button :disabled="busy || !token || !tagForm.name || !tagForm.reason" @click="saveManagedTag">保存标签</button></div>
          <form class="form-grid" @submit.prevent="mergeManagedTag"><h3>合并标签</h3><label>源标签 UUID<input v-model.trim="tagMerge.sourceId" required></label><label>目标标签 UUID<input v-model.trim="tagMerge.targetId" required></label><label>源版本<input v-model.number="tagMerge.sourceVersion" type="number" min="0"></label><label>目标版本<input v-model.number="tagMerge.targetVersion" type="number" min="0"></label><label class="full">合并原因<textarea v-model.trim="tagMerge.reason" maxlength="1000" required></textarea></label><button :disabled="busy || !token">执行合并</button></form>
        </div>
        <div class="panel wide">
          <h2>特权操作审计</h2>
          <div class="toolbar"><label>动作（可选）<select v-model="operationAction"><option value="">全部</option><option v-for="action in operationActionOptions" :key="action.value" :value="action.value">{{ action.label }}</option></select></label><button :disabled="busy || !token" @click="loadOperationAudits">查询</button></div>
          <p v-if="!operationAudits.length" class="muted">暂无已加载记录。</p>
          <div class="table-wrap"><table v-if="operationAudits.length"><thead><tr><th>时间</th><th>动作</th><th>操作人</th><th>目标</th></tr></thead><tbody><tr v-for="item in operationAudits" :key="item.id"><td>{{ new Date(item.createdAt).toLocaleString() }}</td><td>{{ enumLabel(operationActionLabels, item.action) }}</td><td><code>{{ item.actorId || '系统' }}</code></td><td><pre>{{ item.target }}</pre></td></tr></tbody></table></div>
        </div>
        <div class="panel wide">
          <h2>标签批处理与逐项结果</h2>
          <div class="actions"><button class="secondary" :disabled="busy || !token" @click="loadBatchJobs">刷新批次</button></div>
          <div class="table-wrap"><table v-if="batchJobs.length"><thead><tr><th>时间</th><th>操作</th><th>状态</th><th>成功/失败</th><th></th></tr></thead><tbody><tr v-for="item in batchJobs" :key="item.id"><td>{{ new Date(item.createdAt).toLocaleString() }}</td><td>{{ enumLabel(batchOperationLabels, item.operation) }}</td><td>{{ enumLabel(batchStatusLabels, item.status) }}</td><td>{{ item.successCount }}/{{ item.failureCount }}</td><td><button class="secondary" @click="loadBatchDetail(item.id)">逐项结果</button></td></tr></tbody></table></div>
          <form class="form-grid" @submit.prevent="executeTagBatch"><label class="checkbox"><input v-model="batchForm.enabled" type="checkbox">批量启用（不选为停用）</label><label class="full">项目 JSON<textarea v-model="batchForm.items" rows="6" placeholder="[{&quot;tagId&quot;:&quot;UUID&quot;,&quot;expectedVersion&quot;:0}]"></textarea></label><label class="full">执行原因<textarea v-model.trim="batchForm.reason" maxlength="1000" required></textarea></label><button :disabled="busy || !token">执行标签批处理</button></form>
          <div v-if="selectedBatch" class="table-wrap"><h3>批次 {{ selectedBatch.id }}：{{ enumLabel(batchStatusLabels, selectedBatch.status) }}</h3><table><thead><tr><th>序号</th><th>目标</th><th>结果</th><th>失败原因</th><th>新版本</th></tr></thead><tbody><tr v-for="item in selectedBatch.items" :key="item.sequence"><td>{{ item.sequence }}</td><td><code>{{ item.targetId }}</code></td><td>{{ enumLabel(batchItemStatusLabels, item.status) }}</td><td>{{ enumLabel(errorCodeLabels, item.errorCode) }}</td><td>{{ item.resultVersion ?? '—' }}</td></tr></tbody></table></div>
        </div>
        <div class="panel wide"><h2>实现状态</h2><p>管理端计划内 API 和页面能力已接入真实 Spring Boot 契约；下一步是浏览器自动化验证管理员/审核员隔离、版本冲突、批量部分失败和审计记录。</p><ul><li>浏览器不得直接连接 MySQL 或修改 `tn_*` 表。</li><li>批量任务每项独立提交，失败项不会回滚其他成功项。</li></ul></div>
      </section>
    </main>
  </div>
</template>
