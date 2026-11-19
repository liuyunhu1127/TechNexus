<script setup lang="ts">
import { renderSafeMarkdown } from '~/composables/useSafeMarkdown'
type ContentView = { id: string, type: string, state: string, visibility: string, title: string, summary: string, body: string, version: number, auditTaskId?: string, tagIds: string[], fileIds: string[] }
const route = useRoute()
const { isAuthenticated, request } = useApi()
const message = ref('')
const editing = ref(false)
const saving = ref(false)
const commentBody = ref('')
const { data, status, error, refresh } = await useAsyncData(`content-${route.params.id}`, () => request<{ data: ContentView }>(`/contents/${route.params.id}`), { server: false })
const renderedBody = computed(() => renderSafeMarkdown(data.value?.data.body ?? ''))
const form = reactive({ visibility: 'PUBLIC', title: '', summary: '', body: '', tagIds: [] as string[], fileIds: [] as string[] })

function beginEdit() {
  if (!data.value) return
  Object.assign(form, { visibility: data.value.data.visibility, title: data.value.data.title, summary: data.value.data.summary, body: data.value.data.body, tagIds: [...data.value.data.tagIds], fileIds: [...data.value.data.fileIds] })
  editing.value = true
}

async function save() {
  if (!data.value) return
  saving.value = true
  message.value = ''
  try {
    await request(`/contents/${route.params.id}`, { method: 'PATCH', body: { ...form, expectedVersion: data.value.data.version } })
    editing.value = false
    await refresh()
    message.value = '草稿已更新。'
  } catch {
    message.value = '更新失败，内容可能已被修改，请刷新后重试。'
  } finally {
    saving.value = false
  }
}

async function submitForReview() {
  if (!data.value || !window.confirm('提交审核后将暂时不能继续编辑，是否继续？')) return
  saving.value = true
  try {
    await request(`/contents/${route.params.id}/submit`, { method: 'POST', body: { expectedVersion: data.value.data.version } })
    await refresh()
    message.value = '已提交审核。'
  } catch {
    message.value = '提交失败，请刷新后重试。'
  } finally {
    saving.value = false
  }
}

async function createComment() {
  if (!commentBody.value.trim()) return
  saving.value = true
  try {
    await request(`/contents/${route.params.id}/comments`, { method: 'POST', body: { body: commentBody.value.trim(), parentId: null } })
    commentBody.value = ''
    message.value = '评论已提交。'
  } catch {
    message.value = '评论提交失败，请确认登录状态后重试。'
  } finally {
    saving.value = false
  }
}

async function download(fileId: string) {
  try {
    const response = await request<{ data: { url: string } }>(`/files/${fileId}/download-url`, { method: 'POST' })
    window.open(response.data.url, '_blank', 'noopener,noreferrer')
  } catch {
    message.value = '下载失败，文件可能不可用或当前账号无权访问。'
  }
}

useSeoMeta({ title: () => data.value?.data.title ?? '内容详情' })
</script>

<template>
  <p v-if="status === 'pending'" role="status">正在加载内容…</p>
  <section v-else-if="error" class="notice error"><p>内容不存在、无权查看或暂时无法加载。</p><button @click="refresh()">重试</button></section>
  <template v-else-if="data">
    <section class="page-heading"><p class="eyebrow">{{ data.data.type }} · {{ data.data.state }}</p><h1>{{ data.data.title }}</h1><p>{{ data.data.summary }}</p><div class="meta"><span>版本 {{ data.data.version }}</span><span>{{ data.data.visibility }}</span></div></section>
    <form v-if="editing" class="editor-card form-grid" @submit.prevent="save">
      <label>可见范围<select v-model="form.visibility"><option value="PUBLIC">公开</option><option value="LOGIN_REQUIRED">登录可见</option><option value="OWNER_ONLY">仅自己</option></select></label>
      <label class="full">标题<input v-model.trim="form.title" required maxlength="160"></label>
      <label class="full">摘要<textarea v-model="form.summary" maxlength="500"></textarea></label>
      <label class="full">正文<textarea v-model="form.body" required maxlength="200000"></textarea></label>
      <TagPicker v-model="form.tagIds" />
      <FileAttachmentPicker v-model="form.fileIds" />
      <div class="full inline-actions"><button class="button primary" :disabled="saving">保存</button><button type="button" @click="editing = false">取消</button></div>
    </form>
    <article v-else class="editor-card">
      <!-- eslint-disable-next-line vue/no-v-html -- HTML is generated only by the escaping allowlist renderer. -->
      <div class="content-body safe-markdown" v-html="renderedBody"></div>
      <p v-if="data.data.tagIds.length" class="form-footnote">已关联 {{ data.data.tagIds.length }} 个标签</p>
      <div v-if="isAuthenticated && data.data.fileIds.length" class="attachment-list">
        <button v-for="fileId in data.data.fileIds" :key="fileId" type="button" @click="download(fileId)">下载附件 {{ fileId.slice(0, 8) }}</button>
      </div>
      <div v-if="isAuthenticated && data.data.state === 'DRAFT'" class="inline-actions"><button @click="beginEdit">编辑草稿</button><button class="button primary" :disabled="saving" @click="submitForReview">提交审核</button></div>
      <p aria-live="polite">{{ message }}</p>
    </article>
    <form v-if="isAuthenticated && data.data.state === 'PUBLISHED'" class="editor-card" @submit.prevent="createComment"><h2>发表评论</h2><label>评论内容<textarea v-model="commentBody" required maxlength="5000"></textarea></label><button class="button primary" :disabled="saving">提交评论</button></form>
  </template>
</template>
