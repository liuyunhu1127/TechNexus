<script setup lang="ts">
const { request } = useApi()
const email = ref('')
const displayName = ref('')
const password = ref('')
const confirmPassword = ref('')
const message = ref('')
const submitting = ref(false)

async function register() {
  if (password.value !== confirmPassword.value) {
    message.value = '两次输入的密码不一致。'
    return
  }
  submitting.value = true
  message.value = ''
  try {
    await request('/auth/register', { method: 'POST', body: { email: email.value, displayName: displayName.value, password: password.value } })
    await navigateTo('/login?registered=1')
  } catch {
    message.value = '注册失败，请检查输入或稍后重试。'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '注册' })
</script>

<template>
  <section class="auth-card">
    <p class="eyebrow">Account</p><h1>创建账号</h1>
    <form @submit.prevent="register">
      <label>显示名称<input v-model.trim="displayName" required maxlength="80" autocomplete="name"></label>
      <label>邮箱<input v-model.trim="email" required type="email" autocomplete="email"></label>
      <label>密码<input v-model="password" required type="password" minlength="12" maxlength="128" autocomplete="new-password"></label>
      <label>确认密码<input v-model="confirmPassword" required type="password" minlength="12" maxlength="128" autocomplete="new-password"></label>
      <button class="button primary" :disabled="submitting">{{ submitting ? '正在注册…' : '注册' }}</button>
      <p aria-live="polite">{{ message }}</p>
    </form>
    <p class="form-footnote">已有账号？<NuxtLink class="text-link" to="/login">直接登录</NuxtLink></p>
  </section>
</template>
