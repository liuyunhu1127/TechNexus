<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'

type UploadSession = { uploadId: string, fileId: string, state: string, uploadUrl: string, requiredHeaders: Record<string, string>, expiresAt: string }
type FileStatus = { id: string, state: string, detectedMime?: string }

const props = withDefaults(defineProps<{ purpose?: 'ATTACHMENT' | 'IMAGE' }>(), { purpose: 'ATTACHMENT' })
const model = defineModel<string[]>({ required: true })
const { request } = useApi()
const selectedFile = ref<File | null>(null)
const busy = ref(false)
const state = ref('等待选择文件')
const message = ref('')
let stopped = false

onBeforeUnmount(() => { stopped = true })

function chooseFile(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  state.value = selectedFile.value ? '文件已选择' : '等待选择文件'
  message.value = ''
}

async function sha256Hex(file: File) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

async function waitUntilAvailable(fileId: string, initialState: string) {
  let current = initialState
  for (let attempt = 0; attempt < 40 && !stopped; attempt++) {
    if (current === 'AVAILABLE') return current
    if (['BLOCKED', 'EXPIRED'].includes(current)) throw new Error(`文件扫描结果：${current}`)
    await new Promise(resolve => window.setTimeout(resolve, 1500))
    const response = await request<{ data: FileStatus }>(`/files/${fileId}`)
    current = response.data.state
    state.value = current === 'SCANNING' ? '安全扫描中…' : current
  }
  throw new Error('扫描仍在进行，请稍后重试或在文件页查看。')
}

async function upload() {
  const file = selectedFile.value
  if (!file || model.value.length >= 20) return
  busy.value = true
  message.value = ''
  try {
    state.value = '正在计算 SHA-256…'
    const sha256 = await sha256Hex(file)
    state.value = '正在创建上传会话…'
    const response = await request<{ data: UploadSession }>('/files/uploads', {
      method: 'POST',
      body: { fileName: file.name, sizeBytes: file.size, declaredMime: file.type || 'application/octet-stream', sha256, purpose: props.purpose }
    })
    state.value = '正在上传到对象存储…'
    const uploaded = await window.fetch(response.data.uploadUrl, { method: 'PUT', body: file, headers: response.data.requiredHeaders })
    if (!uploaded.ok) throw new Error(`对象存储返回 ${uploaded.status}`)
    state.value = '正在校验并进入扫描队列…'
    const completed = await request<{ data: FileStatus }>(`/files/uploads/${response.data.uploadId}/complete`, { method: 'POST' })
    await waitUntilAvailable(completed.data.id, completed.data.state)
    if (!model.value.includes(completed.data.id)) model.value = [...model.value, completed.data.id]
    selectedFile.value = null
    state.value = 'AVAILABLE'
    message.value = '文件已通过安全扫描并加入附件列表。保存内容后完成绑定。'
  } catch (error) {
    state.value = '处理失败'
    message.value = error instanceof Error ? error.message : '文件处理失败。'
  } finally {
    busy.value = false
  }
}

async function download(fileId: string) {
  message.value = ''
  try {
    const response = await request<{ data: { url: string } }>(`/files/${fileId}/download-url`, { method: 'POST' })
    window.open(response.data.url, '_blank', 'noopener,noreferrer')
  } catch {
    message.value = '下载链接创建失败，文件可能尚未通过扫描或已无权访问。'
  }
}
</script>

<template>
  <fieldset class="full association-fieldset">
    <legend>附件（最多 20 个）</legend>
    <div class="file-upload-row">
      <input type="file" :disabled="busy || model.length >= 20" @change="chooseFile">
      <button type="button" :disabled="busy || !selectedFile || model.length >= 20" @click="upload">
        {{ busy ? '处理中…' : '上传并扫描' }}
      </button>
      <strong role="status">{{ state }}</strong>
    </div>
    <p v-if="selectedFile" class="form-footnote">{{ selectedFile.name }} · {{ selectedFile.size.toLocaleString() }} bytes · {{ selectedFile.type || 'application/octet-stream' }}</p>
    <ul v-if="model.length" class="attachment-list">
      <li v-for="fileId in model" :key="fileId">
        <code>{{ fileId }}</code>
        <button type="button" @click="download(fileId)">下载验证</button>
        <button type="button" @click="model = model.filter(value => value !== fileId)">移除</button>
      </li>
    </ul>
    <p aria-live="polite" class="form-footnote">{{ message }}</p>
  </fieldset>
</template>
