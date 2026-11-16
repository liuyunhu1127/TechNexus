<script setup lang="ts">
const route = useRoute()
const { accessToken, persistCsrf, request } = useApi()
const email = ref('')
const password = ref('')
const message = ref('')
const submitting = ref(false)

async function login() {
  submitting.value = true
  message.value = ''
  try {
    const response = await request<{ accessToken: string, csrfToken: string }>('/auth/login', { method: 'POST', body: { account: email.value, password: password.value } })
    accessToken.value = response.accessToken
    persistCsrf(response.csrfToken)
    message.value = '登录成功。'
    await navigateTo('/contents')
  } catch {
    message.value = '登录失败，请检查邮箱和密码。'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '登录' })
</script>

<template>
  <section class="auth-card">
    <p class="eyebrow">Account</p><h1>登录 TechNexus</h1>
    <p v-if="route.query.registered === '1'" class="notice success">注册成功，请登录。</p>
    <form @submit.prevent="login">
      <label>邮箱<input v-model="email" required type="email" autocomplete="email"></label>
      <label>密码<input v-model="password" required type="password" autocomplete="current-password" minlength="12"></label>
      <button class="button primary" :disabled="submitting">{{ submitting ? '正在登录…' : '登录' }}</button>
      <p aria-live="polite">{{ message }}</p>
    </form>
    <p class="form-footnote">还没有账号？<NuxtLink class="text-link" to="/register">创建账号</NuxtLink></p>
  </section>
</template>
