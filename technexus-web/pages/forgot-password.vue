<script setup lang="ts">
const { request } = useApi()
const email = ref('')
const busy = ref(false)
const sent = ref(false)
const error = ref('')

async function send() {
  busy.value = true
  error.value = ''
  try {
    await request('/auth/forgot-password', { method: 'POST', body: { email: email.value } })
    sent.value = true
  } catch {
    error.value = '暂时无法提交，请稍后重试。'
  } finally {
    busy.value = false
  }
}

useSeoMeta({ title: '找回密码' })
</script>

<template>
  <section class="auth-card">
    <h1>找回密码</h1>
    <p v-if="sent" class="notice success" role="status">如果该邮箱符合重置条件，我们会发送邮件。请检查收件箱和垃圾邮件。</p>
    <form v-else @submit.prevent="send">
      <label>已验证的邮箱<input v-model.trim="email" required type="email" maxlength="254" autocomplete="email"></label>
      <button type="submit" class="button primary" :disabled="busy">{{ busy ? '正在提交…' : '发送重置链接' }}</button>
    </form>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p class="form-footnote">无法使用已验证邮箱，或需要恢复初始管理员账号时，请联系人工支持。</p>
    <NuxtLink class="text-link" to="/login">返回登录</NuxtLink>
  </section>
</template>
