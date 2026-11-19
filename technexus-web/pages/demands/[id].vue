<script setup lang="ts">
type Money = { amount: string, currency: string } | null
type DemandView = { id: string, state: string, title: string, description: string, budgetMin: Money, budgetMax: Money, deadlineAt: string | null, visibility: string, version: number }
const route = useRoute()
const { isAuthenticated, request } = useApi()
const message = ref('')
const editing = ref(false)
const saving = ref(false)
const { data, status, error, refresh } = await useAsyncData(`demand-${route.params.id}`, () => request<{ data: DemandView }>(`/demands/${route.params.id}`), { server: false })
const form = reactive({ title: '', description: '', budgetMin: '', budgetMax: '', deadlineAt: '', visibility: 'PUBLIC' })
const proposal = reactive({ plan: '', techStack: '', estimatedDays: 1, suggestedQuote: '' })

function beginEdit() {
  if (!data.value) return
  const value = data.value.data
  Object.assign(form, { title: value.title, description: value.description, budgetMin: value.budgetMin?.amount ?? '', budgetMax: value.budgetMax?.amount ?? '', deadlineAt: value.deadlineAt ? value.deadlineAt.slice(0, 16) : '', visibility: value.visibility })
  editing.value = true
}
function money(value: string) { return value ? { amount: value, currency: 'CNY' } : null }
async function save() {
  if (!data.value) return
  saving.value = true
  try {
    await request(`/demands/${route.params.id}`, { method: 'PATCH', body: { ...form, budgetMin: money(form.budgetMin), budgetMax: money(form.budgetMax), deadlineAt: form.deadlineAt ? new Date(form.deadlineAt).toISOString() : null, expectedVersion: data.value.data.version } })
    editing.value = false
    await refresh()
    message.value = '需求草稿已更新。'
  } catch { message.value = '更新失败，需求可能已变化，请刷新后重试。' } finally { saving.value = false }
}
async function submitForReview() {
  if (!data.value || !window.confirm('确认提交审核？')) return
  saving.value = true
  try { await request(`/demands/${route.params.id}/submit`, { method: 'POST', body: { expectedVersion: data.value.data.version } }); await refresh(); message.value = '已提交审核。' } catch { message.value = '提交失败，请刷新后重试。' } finally { saving.value = false }
}
async function createProposal() {
  saving.value = true
  try {
    await request(`/demands/${route.params.id}/proposals`, { method: 'POST', body: { plan: proposal.plan, techStack: proposal.techStack.split(',').map(value => value.trim()).filter(Boolean), estimatedDays: proposal.estimatedDays, suggestedQuote: { amount: proposal.suggestedQuote, currency: 'CNY' } } })
    Object.assign(proposal, { plan: '', techStack: '', estimatedDays: 1, suggestedQuote: '' })
    message.value = '方案已提交。'
  } catch { message.value = '方案提交失败，请检查输入和需求状态。' } finally { saving.value = false }
}
useSeoMeta({ title: () => data.value?.data.title ?? '需求详情' })
</script>

<template>
  <p v-if="status === 'pending'" role="status">正在加载需求…</p>
  <section v-else-if="error" class="notice error"><p>需求不存在、无权查看或暂时无法加载。</p><button @click="refresh()">重试</button></section>
  <template v-else-if="data">
    <section class="page-heading"><p class="eyebrow">{{ data.data.state }}</p><h1>{{ data.data.title }}</h1><p>{{ data.data.description }}</p><div class="meta"><span>版本 {{ data.data.version }}</span><span>预算 {{ data.data.budgetMin?.amount ?? '未设' }}—{{ data.data.budgetMax?.amount ?? '未设' }} CNY</span></div></section>
    <form v-if="editing" class="editor-card form-grid" @submit.prevent="save">
      <label class="full">标题<input v-model.trim="form.title" required maxlength="160"></label><label class="full">描述<textarea v-model="form.description" required maxlength="50000"></textarea></label>
      <label>最低预算<input v-model="form.budgetMin" inputmode="decimal"></label><label>最高预算<input v-model="form.budgetMax" inputmode="decimal"></label>
      <label>截止时间<input v-model="form.deadlineAt" type="datetime-local"></label><label>可见范围<select v-model="form.visibility"><option value="PUBLIC">公开</option><option value="LOGIN_REQUIRED">登录可见</option><option value="OWNER_ONLY">仅自己</option></select></label>
      <div class="full inline-actions"><button class="button primary" :disabled="saving">保存</button><button type="button" @click="editing = false">取消</button></div>
    </form>
    <section v-else class="inline-actions"><button v-if="isAuthenticated && data.data.state === 'DRAFT'" @click="beginEdit">编辑草稿</button><button v-if="isAuthenticated && data.data.state === 'DRAFT'" class="button primary" :disabled="saving" @click="submitForReview">提交审核</button></section>
    <form v-if="isAuthenticated && data.data.state === 'PUBLISHED'" class="editor-card" @submit.prevent="createProposal"><h2>提交方案</h2><label>方案说明<textarea v-model="proposal.plan" required maxlength="50000"></textarea></label><label>技术栈（逗号分隔）<input v-model="proposal.techStack" required></label><div class="form-grid"><label>预计天数<input v-model.number="proposal.estimatedDays" required type="number" min="1" max="3650"></label><label>建议报价（CNY）<input v-model="proposal.suggestedQuote" required inputmode="decimal"></label></div><button class="button primary" :disabled="saving">提交方案</button></form>
    <p aria-live="polite">{{ message }}</p>
  </template>
</template>
