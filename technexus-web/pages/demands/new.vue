<script setup lang="ts">
const { isAuthenticated, request } = useApi()
const form = reactive({ title: '', description: '', budgetMin: '', budgetMax: '', deadlineAt: '', visibility: 'PUBLIC' })
const message = ref('')
const submitting = ref(false)

function money(value: string) { return value ? { amount: value, currency: 'CNY' } : null }

async function createDemand() {
  if (!isAuthenticated.value) { await navigateTo('/login'); return }
  submitting.value = true
  message.value = ''
  try {
    const body = { ...form, budgetMin: money(form.budgetMin), budgetMax: money(form.budgetMax), deadlineAt: form.deadlineAt ? new Date(form.deadlineAt).toISOString() : null }
    const response = await request<{ data: { id: string } }>('/demands', { method: 'POST', body })
    await navigateTo(`/demands/${response.data.id}`)
  } catch {
    message.value = '保存失败，请确认预算、截止时间和登录状态。'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '发布需求' })
</script>

<template>
  <section class="page-heading"><p class="eyebrow">Create</p><h1>发布真实需求</h1><p>说明目标、约束和预算，先保存草稿再提交审核。</p></section>
  <section v-if="!isAuthenticated" class="notice"><p>发布需求需要登录。</p><NuxtLink class="button primary" to="/login">前往登录</NuxtLink></section>
  <form v-else class="editor-card form-grid" @submit.prevent="createDemand">
    <label class="full">标题<input v-model.trim="form.title" required maxlength="160"></label>
    <label class="full">需求描述<textarea v-model="form.description" required maxlength="50000"></textarea></label>
    <label>最低预算（CNY）<input v-model="form.budgetMin" inputmode="decimal" pattern="\d{1,10}(\.\d{1,2})?"></label>
    <label>最高预算（CNY）<input v-model="form.budgetMax" inputmode="decimal" pattern="\d{1,10}(\.\d{1,2})?"></label>
    <label>截止时间<input v-model="form.deadlineAt" type="datetime-local"></label>
    <label>可见范围<select v-model="form.visibility"><option value="PUBLIC">公开</option><option value="LOGIN_REQUIRED">登录可见</option><option value="OWNER_ONLY">仅自己</option></select></label>
    <div class="full inline-actions"><button class="button primary" :disabled="submitting">{{ submitting ? '正在保存…' : '保存草稿' }}</button><span aria-live="polite">{{ message }}</span></div>
  </form>
</template>
