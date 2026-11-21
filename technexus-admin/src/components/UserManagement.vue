<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { useManagementPage, userStatuses, type RunAction } from './management'

type ManagedUser = { id: string, email: string, displayName: string, status: string, version: number }
const props = defineProps<{ account: string, token: string, busy: boolean, run: RunAction }>()
const { query, state, page, load, update } = useManagementPage<ManagedUser>('/admin/users', 'status', () => props.token)
const selected = ref<ManagedUser | null>(null)
const reason = ref('')

function search(number = 1, applyFilters = true) {
  return props.run(async () => {
    await load(number, applyFilters)
    selected.value = null
  }, '')
}

function choose(user: ManagedUser) {
  selected.value = user
  reason.value = ''
}

function changeStatus() {
  const user = selected.value
  if (!user || !reason.value.trim()) return
  const enabled = user.status === 'DISABLED'
  return props.run(async () => {
    const result = await api<{ data: ManagedUser }>(`/admin/users/${user.id}/status`, props.token, {
      method: 'PUT',
      body: { enabled, expectedVersion: user.version, reason: reason.value },
    })
    update(result.data, result.data.status)
    selected.value = null
  }, enabled ? '账号已恢复。' : '账号已停用，原有会话已撤销。')
}

onMounted(() => search())
</script>

<template>
  <section class="panel wide">
    <form class="toolbar management-toolbar" @submit.prevent="search()">
      <label>邮箱或昵称<input v-model="query" type="search" maxlength="254"></label>
      <label>账号状态
        <select v-model="state">
          <option value="">全部</option>
          <option v-for="(label, value) in userStatuses" :key="value" :value="value">{{ label }}</option>
        </select>
      </label>
      <button type="submit" :disabled="busy">查询</button>
    </form>
    <p v-if="busy && !page" class="muted" role="status">正在加载用户…</p>
    <p v-else-if="page && !page.items.length" class="muted">没有匹配的用户。</p>
    <div v-if="page?.items.length" class="table-wrap">
      <table aria-label="用户列表">
        <thead><tr><th scope="col">昵称</th><th scope="col">邮箱</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="user in page.items" :key="user.id">
            <td>{{ user.displayName }}</td>
            <td>{{ user.email }}</td>
            <td>{{ userStatuses[user.status] ?? user.status }}</td>
            <td>
              <span v-if="user.email.toLowerCase() === account.trim().toLowerCase()" class="muted">当前账号</span>
              <button v-else-if="user.status === 'ACTIVE' || user.status === 'DISABLED'" type="button" class="secondary" :disabled="busy" @click="choose(user)">
                {{ user.status === 'ACTIVE' ? '停用' : '恢复' }}
              </button>
              <span v-else class="muted">—</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="page && (page.page > 1 || page.hasNext)" class="actions">
      <button type="button" class="secondary" :disabled="busy || page.page <= 1" @click="search(page.page - 1, false)">上一页</button>
      <span>第 {{ page.page }} 页</span>
      <button type="button" class="secondary" :disabled="busy || !page.hasNext" @click="search(page.page + 1, false)">下一页</button>
    </div>
    <form v-if="selected" class="form-grid management-detail" @submit.prevent="changeStatus">
      <h2>{{ selected.status === 'ACTIVE' ? '停用账号' : '恢复账号' }} · {{ selected.displayName }}</h2>
      <p v-if="selected.status === 'ACTIVE'" class="notice">停用会撤销该用户的现有会话。</p>
      <label class="full">操作原因<textarea v-model.trim="reason" required maxlength="1000" rows="3"></textarea></label>
      <div class="actions full">
        <button type="submit" :disabled="busy || !reason">确认{{ selected.status === 'ACTIVE' ? '停用' : '恢复' }}</button>
        <button type="button" class="secondary" :disabled="busy" @click="selected = null">取消</button>
      </div>
    </form>
  </section>
</template>
