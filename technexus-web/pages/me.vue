<script setup lang="ts">
import { onMounted } from 'vue'

type Profile = { id: string, displayName: string, bio: string, status: string, version: number }
type ProfileFailure = { statusCode?: number, status?: number }
const { isAuthenticated, request } = useApi()
const ready = ref(false)
const busy = ref(false)
const expired = ref(false)
const error = ref('')
const message = ref('')
const profile = ref<Profile | null>(null)
const form = reactive({ displayName: '', bio: '' })
const statuses: Record<string, string> = {
  ACTIVE: '正常', REGISTERED: '待激活', LOCKED: '锁定', DISABLED: '已停用', CANCELLED: '已注销',
}

function setProfile(value: Profile) {
  profile.value = value
  form.displayName = value.displayName
  form.bio = value.bio
}

async function run(action: () => Promise<void>, failure: string) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
  } catch (cause) {
    const response = cause as ProfileFailure | null
    const status = response?.statusCode ?? response?.status
    if (status === 401) {
      expired.value = true
      error.value = '登录已过期，请重新登录。'
    } else {
      error.value = status === 409 ? '资料已在其他位置更新，请重新加载后再保存。' : failure
    }
  } finally {
    busy.value = false
  }
}

function loadProfile() {
  return run(async () => {
    setProfile((await request<{ data: Profile }>('/me')).data)
  }, '暂时无法加载个人资料，请重试。')
}

function saveProfile() {
  const current = profile.value
  if (!current || !form.displayName.trim()) return
  return run(async () => {
    const response = await request<{ data: Profile }>('/me', {
      method: 'PATCH',
      body: { ...form, expectedVersion: current.version },
    })
    setProfile(response.data)
    message.value = '个人资料已保存。'
  }, '保存失败，请检查昵称和简介后重试。')
}

onMounted(async () => {
  ready.value = true
  if (isAuthenticated.value) await loadProfile()
})
useSeoMeta({ title: '个人中心' })
</script>

<template>
  <section class="page-heading"><h1>个人中心</h1></section>
  <p v-if="!ready || (busy && !profile)" class="notice" role="status">正在加载个人资料…</p>
  <section v-else-if="!isAuthenticated || expired" class="notice">
    <p>{{ expired ? error : '请登录后查看和编辑个人资料。' }}</p>
    <NuxtLink class="button primary" :to="{ path: '/login', query: { redirect: '/me' } }">前往登录</NuxtLink>
  </section>
  <section v-else class="editor-card">
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <form v-if="profile" class="form-grid" @submit.prevent="saveProfile">
      <p class="full">账号状态 · {{ statuses[profile.status] ?? profile.status }}</p>
      <label class="full">昵称<input v-model.trim="form.displayName" required maxlength="80" autocomplete="nickname"></label>
      <label class="full">个人简介<textarea v-model="form.bio" maxlength="1000" rows="5"></textarea></label>
      <div class="inline-actions full">
        <button type="submit" class="button primary" :disabled="busy || !form.displayName">{{ busy ? '正在保存…' : '保存资料' }}</button>
        <button type="button" class="button" :disabled="busy" @click="loadProfile">重新加载</button>
      </div>
    </form>
    <button v-else type="button" class="button" :disabled="busy" @click="loadProfile">重新加载</button>
  </section>
</template>
