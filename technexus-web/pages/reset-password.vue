<script setup lang="ts">
import { onMounted } from 'vue'

const { request, accessToken, persistCsrf } = useApi()
const token = ref('')
const password = ref('')
const confirmation = ref('')
const ready = ref(false)
const busy = ref(false)
const error = ref('')

onMounted(() => {
  const received = new window.URLSearchParams(window.location.hash.slice(1)).get('token') ?? ''
  if (/^[0-9a-f-]{36}\.[A-Za-z0-9_-]{24}$/i.test(received)) token.value = received
  if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname)
  ready.value = true
})

async function reset() {
  if (!token.value) return
  if (password.value !== confirmation.value) {
    error.value = '两次输入的密码不一致。'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await request('/auth/reset-password', { method: 'POST', body: { token: token.value, password: password.value } })
    token.value = ''
    password.value = ''
    confirmation.value = ''
    accessToken.value = null
    persistCsrf(null)
    await navigateTo({ path: '/login', query: { passwordReset: '1' } })
  } catch (cause) {
    const response = cause as { statusCode?: number }
    error.value = response.statusCode === 400 ? '重置链接无效或已过期，请重新发送链接。' : '暂时无法重置密码，请稍后重试。'
  } finally {
    busy.value = false
  }
}

useSeoMeta({ title: '设置新密码', referrer: 'no-referrer' })
</script>

<template>
  <section class="auth-card">
    <h1>设置新密码</h1>
    <form v-if="token" @submit.prevent="reset">
      <label>新密码（至少 12 个字符）<input v-model="password" required type="password" minlength="12" maxlength="128" autocomplete="new-password"></label>
      <label>确认新密码<input v-model="confirmation" required type="password" minlength="12" maxlength="128" autocomplete="new-password"></label>
      <button type="submit" class="button primary" :disabled="busy">{{ busy ? '正在保存…' : '保存新密码' }}</button>
    </form>
    <p v-else-if="ready" class="notice error" role="alert">请通过邮件中的一次性链接打开此页面。</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <div class="inline-actions">
      <NuxtLink class="text-link" to="/forgot-password">重新发送链接</NuxtLink>
      <NuxtLink class="text-link" to="/login">返回登录</NuxtLink>
    </div>
  </section>
</template>
