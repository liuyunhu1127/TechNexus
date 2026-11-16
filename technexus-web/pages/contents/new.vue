<script setup lang="ts">
const { isAuthenticated, request } = useApi()
const form = reactive({ type: 'DOCUMENT', visibility: 'PUBLIC', title: '', summary: '', body: '' })
const message = ref('')
const submitting = ref(false)
const tagIds = ref<string[]>([])
const fileIds = ref<string[]>([])

async function createContent() {
  if (!isAuthenticated.value) {
    await navigateTo('/login')
    return
  }
  submitting.value = true
  message.value = ''
  try {
    const response = await request<{ data: { id: string } }>('/contents', { method: 'POST', body: { ...form, tagIds: tagIds.value, fileIds: fileIds.value } })
    await navigateTo(`/contents/${response.data.id}`)
  } catch {
    message.value = '保存失败，请确认已登录并检查输入。'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '发布内容' })
</script>

<template>
  <section class="page-heading"><p class="eyebrow">Create</p><h1>发布技术内容</h1><p>先保存草稿，确认内容后再提交审核。</p></section>
  <section v-if="!isAuthenticated" class="notice"><p>发布内容需要登录。</p><NuxtLink class="button primary" to="/login">前往登录</NuxtLink></section>
  <form v-else class="editor-card form-grid" @submit.prevent="createContent">
    <label>类型<select v-model="form.type"><option value="DOCUMENT">文档</option><option value="POST">帖子</option><option value="PROBLEM">问题</option><option value="SOLUTION">解决方案</option></select></label>
    <label>可见范围<select v-model="form.visibility"><option value="PUBLIC">公开</option><option value="LOGIN_REQUIRED">登录可见</option><option value="OWNER_ONLY">仅自己</option></select></label>
    <label class="full">标题<input v-model.trim="form.title" required maxlength="160"></label>
    <label class="full">摘要<textarea v-model="form.summary" maxlength="500"></textarea></label>
    <label class="full">正文<textarea v-model="form.body" required maxlength="200000"></textarea></label>
    <TagPicker v-model="tagIds" />
    <FileAttachmentPicker v-model="fileIds" />
    <div class="full inline-actions"><button class="button primary" :disabled="submitting">{{ submitting ? '正在保存…' : '保存草稿' }}</button><span aria-live="polite">{{ message }}</span></div>
  </form>
</template>
