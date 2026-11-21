<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

type Request = <T>(path: string, options?: { method: 'POST' | 'PUT', body?: Record<string, unknown> }) => Promise<T>
type Avatar = { url?: string | null, version: number }
type Upload = { uploadId: string, uploadUrl: string, requiredHeaders: Record<string, string> }
type FileStatus = { id: string, state: string }

const props = defineProps<{ request: Request, name: string }>()
const emit = defineEmits<{ changed: [url: string | null] }>()
const avatar = ref<Avatar | null>(null)
const canvas = ref<InstanceType<typeof window.HTMLCanvasElement> | null>(null)
const input = ref<HTMLInputElement | null>(null)
const preview = ref(false)
const zoom = ref(1)
const busy = ref(false)
const message = ref('')
const error = ref('')
let image: Awaited<ReturnType<typeof window.createImageBitmap>> | null = null
let disposed = false
let pendingFileId: string | null = null

onBeforeUnmount(() => {
  disposed = true
  image?.close()
})

async function load() {
  avatar.value = (await props.request<{ data: Avatar }>('/me/avatar')).data
}

onMounted(() => load().catch(() => { error.value = '暂时无法加载头像。' }))

function clearPreview() {
  preview.value = false
  pendingFileId = null
  image?.close()
  image = null
  if (input.value) input.value.value = ''
}

function draw() {
  const context = canvas.value?.getContext('2d')
  if (!image || !context) return
  const size = Math.min(image.width, image.height) / zoom.value
  context.clearRect(0, 0, 512, 512)
  context.drawImage(image, (image.width - size) / 2, (image.height - size) / 2, size, size, 0, 0, 512, 512)
  pendingFileId = null
}

async function select(event: Event) {
  const control = event.target as HTMLInputElement
  const file = control.files?.[0]
  clearPreview()
  error.value = ''
  message.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png'].includes(file.type) || file.size > 5 * 1024 * 1024) {
    error.value = '请选择不超过 5 MiB 的 JPEG 或 PNG 图片。'
    return
  }
  busy.value = true
  try {
    const selected = await window.createImageBitmap(file)
    if (disposed || selected.width > 4096 || selected.height > 4096) {
      selected.close()
      throw new Error('头像图片宽高不能超过 4096 像素。')
    }
    image = selected
    zoom.value = 1
    draw()
    preview.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取此图片。'
  } finally {
    busy.value = false
  }
}

async function upload() {
  const previewCanvas = canvas.value
  if (!previewCanvas) throw new Error('无法生成裁剪图片。')
  const blob = await new Promise<InstanceType<typeof window.Blob> | null>(resolve => previewCanvas.toBlob(resolve, 'image/png'))
  if (!blob) throw new Error('无法生成裁剪图片。')
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  const sha256 = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
  const { data } = await props.request<{ data: Upload }>('/files/uploads', {
    method: 'POST', body: { fileName: 'avatar.png', sizeBytes: blob.size, declaredMime: 'image/png', sha256, purpose: 'IMAGE' },
  })
  const uploaded = await window.fetch(data.uploadUrl, {
    method: 'PUT', body: blob, headers: data.requiredHeaders, credentials: 'omit',
  })
  if (!uploaded.ok) throw new Error('图片上传失败，请重试。')
  const result = await props.request<{ data: FileStatus }>(`/files/uploads/${data.uploadId}/complete`, { method: 'POST' })
  return result.data.id
}

async function waitForScan(fileId: string) {
  for (let attempt = 0; attempt < 40 && !disposed; attempt++) {
    const { data } = await props.request<{ data: FileStatus }>(`/files/${fileId}`)
    if (data.state === 'AVAILABLE') return
    if (['BLOCKED', 'EXPIRED'].includes(data.state)) {
      pendingFileId = null
      throw new Error('图片未通过安全检查，请选择其他图片。')
    }
    await new Promise(resolve => window.setTimeout(resolve, 1500))
  }
  throw new Error('安全扫描仍在进行，请稍后再次保存。')
}

async function save(remove: boolean) {
  if (!avatar.value || (!remove && !preview.value)) return
  busy.value = true
  error.value = ''
  message.value = remove ? '正在移除头像…' : '正在上传并进行安全扫描…'
  try {
    // The profile form and the avatar share the same optimistic version.
    await load()
    if (!remove) {
      pendingFileId ??= await upload()
      await waitForScan(pendingFileId)
    }
    if (disposed) return
    await props.request('/me/avatar', {
      method: 'PUT', body: { fileId: remove ? null : pendingFileId, expectedVersion: avatar.value.version },
    })
    clearPreview()
    await load()
    emit('changed', avatar.value.url ?? null)
    message.value = remove ? '头像已移除。' : '头像已更新。'
  } catch (cause) {
    const detail = cause as { status?: number, statusCode?: number, data?: { detail?: string } }
    error.value = (detail.statusCode ?? detail.status) === 409
      ? '资料已更新，请再次保存。'
      : detail.data?.detail ?? (cause instanceof Error ? cause.message : '头像更新失败，请重试。')
    message.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="avatar-editor" aria-label="头像设置">
    <h2>头像</h2>
    <div class="avatar-photo">
      <img v-if="avatar?.url" :src="avatar.url" alt="当前头像" referrerpolicy="no-referrer">
      <span v-else aria-label="默认头像">{{ name.trim().slice(0, 1) || '人' }}</span>
    </div>
    <label>选择头像（JPEG 或 PNG，最多 5 MiB）
      <input ref="input" type="file" accept="image/jpeg,image/png" :disabled="busy || !avatar" @change="select">
    </label>
    <div v-show="preview" class="crop-preview">
      <canvas ref="canvas" width="512" height="512" aria-label="裁剪预览" />
      <label>裁剪缩放<input v-model.number="zoom" type="range" min="1" max="3" step="0.05" :disabled="busy" @input="draw"></label>
    </div>
    <div class="avatar-actions">
      <button v-if="preview" type="button" class="button primary" :disabled="busy" @click="save(false)">保存头像</button>
      <button v-if="preview" type="button" class="button secondary" :disabled="busy" @click="clearPreview">取消</button>
      <button v-if="avatar?.url" type="button" class="button secondary" :disabled="busy" @click="save(true)">移除头像</button>
      <button v-if="!avatar && error" type="button" class="button secondary" @click="load().then(() => { error = '' }).catch(() => {})">重新加载</button>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.avatar-editor { display: grid; gap: .75rem; justify-items: start; }
.avatar-editor h2, .avatar-editor p { margin: 0; }
.avatar-editor label { display: grid; gap: .35rem; }
.avatar-photo { width: 4.5rem; height: 4.5rem; display: grid; place-items: center; overflow: hidden; border-radius: 50%; background: #e7eeeb; color: #183a2f; font-size: 1.5rem; }
.avatar-photo img { width: 100%; height: 100%; object-fit: cover; }
.crop-preview { display: grid; gap: .5rem; }
.crop-preview canvas { display: block; width: 9rem; height: 9rem; border-radius: 50%; background: #e7eeeb; }
.avatar-actions { display: flex; flex-wrap: wrap; gap: .5rem; }
</style>
