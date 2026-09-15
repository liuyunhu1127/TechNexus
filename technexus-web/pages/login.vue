<script setup lang="ts">
const { accessToken, request } = useApi()
const email = ref('')
const password = ref('')
const message = ref('')
const submitting = ref(false)

async function login() {
  submitting.value = true
  message.value = ''
  try {
    const response = await request<{ accessToken: string }>('/auth/login', { method: 'POST', body: { account: email.value, password: password.value } })
    accessToken.value = response.accessToken
    message.value = '登录成功。'
    await navigateTo('/contents')
  } catch {
    message.value = '登录失败，请检查邮箱和密码。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="auth-card">
    <p class="eyebrow">Account</p><h1>登录 TechNexus</h1>
    <form @submit.prevent="login">
      <label>邮箱<input v-model="email" required type="email" autocomplete="email"></label>
      <label>密码<input v-model="password" required type="password" autocomplete="current-password" minlength="12"></label>
      <button class="button primary" :disabled="submitting">{{ submitting ? '正在登录…' : '登录' }}</button>
      <p aria-live="polite">{{ message }}</p>
    </form>
  </section>
</template>
