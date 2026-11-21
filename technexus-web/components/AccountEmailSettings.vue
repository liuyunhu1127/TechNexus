<script setup lang="ts">
import { onMounted } from 'vue'

type Settings = { email: string, verified: boolean, bootstrapAdmin: boolean, mailEnabled: boolean }
type EmailAction = 'verify' | 'change'
const emit = defineEmits<{ verified: [] }>()
const { request, accessToken, persistCsrf } = useApi()
const settings = ref<Settings | null>(null)
const action = ref<EmailAction | null>(null)
const requestId = ref('')
const password = ref('')
const email = ref('')
const code = ref('')
const newCode = ref('')
const busy = ref(false)
const message = ref('')
const error = ref('')
const codePattern = '[A-Za-z0-9_\\-]{24}'
const actionPath = computed(() => action.value === 'verify' ? '/me/email-verification' : '/me/email-change')

async function run(work: () => Promise<void>, failure: string) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await work()
  } catch (cause) {
    const response = cause as { statusCode?: number, data?: { detail?: string } }
    error.value = response.statusCode === 401 ? '登录已过期，请重新登录。' : response.data?.detail ?? failure
  } finally {
    busy.value = false
  }
}

function load() {
  return run(async () => {
    settings.value = (await request<{ data: Settings }>('/me/security')).data
  }, '暂时无法加载邮箱设置。')
}

function start(next: EmailAction | null) {
  action.value = next
  requestId.value = ''
  code.value = ''
  newCode.value = ''
  password.value = ''
  if (!next) email.value = ''
  message.value = ''
  error.value = ''
}

function sendCode() {
  return run(async () => {
    const response = await request<{ data: { requestId: string } }>(actionPath.value, {
      method: 'POST',
      body: action.value === 'verify' ? { password: password.value } : { password: password.value, email: email.value },
    })
    requestId.value = response.data.requestId
    message.value = action.value === 'verify' ? '验证码已发送到当前邮箱。' : '验证码已分别发送到原邮箱和新邮箱。'
    password.value = ''
  }, '验证码发送失败，请重试。')
}

function confirm() {
  const verifying = action.value === 'verify'
  return run(async () => {
    await request(`${actionPath.value}/confirm`, {
      method: 'POST',
      body: verifying ? { requestId: requestId.value, code: code.value }
        : { requestId: requestId.value, oldCode: code.value, newCode: newCode.value },
    })
    start(null)
    if (verifying) {
      if (settings.value) settings.value.verified = true
      message.value = '邮箱已验证。'
      emit('verified')
      return
    }
    accessToken.value = null
    persistCsrf(null)
    await navigateTo({ path: '/login', query: { emailChanged: '1' } })
  }, '验证失败，请检查验证码后重试。')
}

onMounted(load)
</script>

<template>
  <section class="editor-card email-settings" aria-labelledby="email-settings-heading">
    <h2 id="email-settings-heading">邮箱与账号安全</h2>
    <template v-if="settings">
      <p>当前邮箱 · {{ settings.email }}</p>
      <p v-if="settings.bootstrapAdmin">管理员账号的邮箱和密码请联系管理员走人工恢复流程。</p>
      <template v-else>
        <p>{{ settings.verified ? '邮箱已验证' : '请先验证当前邮箱。验证后可换绑邮箱并自助重置密码。' }}</p>
        <p v-if="!settings.mailEnabled" class="notice">邮件服务暂未开启。</p>
        <button v-else-if="!action" type="button" class="button" :disabled="busy" @click="start(settings.verified ? 'change' : 'verify')">
          {{ settings.verified ? '换绑邮箱' : '验证邮箱' }}
        </button>
        <form v-else-if="!requestId" class="form-grid" @submit.prevent="sendCode">
          <label v-if="action === 'change'" class="full">新邮箱<input v-model.trim="email" required type="email" maxlength="254" autocomplete="email"></label>
          <label class="full">当前密码<input v-model="password" required type="password" maxlength="128" autocomplete="current-password"></label>
          <div class="inline-actions full">
            <button type="submit" class="button primary" :disabled="busy">发送验证码</button>
            <button type="button" class="button" :disabled="busy" @click="start(null)">取消</button>
          </div>
        </form>
        <form v-else class="form-grid" @submit.prevent="confirm">
          <label class="full">{{ action === 'verify' ? '邮箱验证码' : '原邮箱验证码' }}
            <input v-model.trim="code" required maxlength="24" :pattern="codePattern" autocomplete="one-time-code" spellcheck="false">
          </label>
          <label v-if="action === 'change'" class="full">发往 {{ email }} 的验证码
            <input v-model.trim="newCode" required maxlength="24" :pattern="codePattern" autocomplete="off" spellcheck="false">
          </label>
          <div class="inline-actions full">
            <button type="submit" class="button primary" :disabled="busy">{{ action === 'verify' ? '确认验证' : '确认换绑' }}</button>
            <button type="button" class="button" :disabled="busy" @click="start(action)">重新发送</button>
            <button type="button" class="button" :disabled="busy" @click="start(null)">取消</button>
          </div>
        </form>
      </template>
    </template>
    <button v-else-if="!busy" type="button" class="button" @click="load">重新加载</button>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.email-settings { margin-top: 1rem; }
.email-settings h2 { margin-top: 0; }
</style>
