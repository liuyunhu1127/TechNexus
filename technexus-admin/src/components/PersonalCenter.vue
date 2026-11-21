<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { userStatuses, type RunAction } from './management'

type Profile = { id: string, displayName: string, bio: string, status: string, version: number }

const props = defineProps<{ account: string, token: string, busy: boolean, run: RunAction }>()
const profile = ref<Profile | null>(null)
const form = reactive({ displayName: '', bio: '' })

function edit(value: Profile) {
  profile.value = value
  form.displayName = value.displayName
  form.bio = value.bio
}

function load() {
  return props.run(async () => {
    edit((await api<{ data: Profile }>('/me', props.token)).data)
  }, '')
}

function save() {
  const current = profile.value
  if (!current || !form.displayName.trim()) return
  return props.run(async () => {
    const result = await api<{ data: Profile }>('/me', props.token, {
      method: 'PATCH',
      body: { ...form, expectedVersion: current.version },
    })
    edit(result.data)
  }, '个人资料已保存。')
}

onMounted(load)
</script>

<template>
  <section class="panel">
    <dl v-if="profile" class="management-meta">
      <div><dt>登录账号</dt><dd>{{ account }}</dd></div>
      <div><dt>账号状态</dt><dd>{{ userStatuses[profile.status] ?? profile.status }}</dd></div>
    </dl>
    <p v-if="!profile && busy" class="muted" role="status">正在加载个人资料…</p>
    <form v-if="profile" class="form-grid" @submit.prevent="save">
      <label class="full">昵称<input v-model.trim="form.displayName" required maxlength="80" autocomplete="nickname"></label>
      <label class="full">个人简介<textarea v-model="form.bio" maxlength="1000" rows="5"></textarea></label>
      <div class="actions full">
        <button type="submit" :disabled="busy || !form.displayName">保存资料</button>
        <button type="button" class="secondary" :disabled="busy" @click="load">重新加载</button>
      </div>
    </form>
    <button v-else-if="!busy" type="button" class="secondary" @click="load">重新加载</button>
  </section>
</template>
