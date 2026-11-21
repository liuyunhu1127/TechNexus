<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { postStatuses, useManagementPage, type RunAction } from './management'

type Post = { id: string, ownerId: string, title: string, type: string, state: string, version: number }
type PostDetail = Post & { ownerName: string, visibility: string, body: string, publishedTitle: string | null, publishedBody: string | null }
const props = defineProps<{ token: string, busy: boolean, run: RunAction }>()
const { query, state, page, load, update } = useManagementPage<Post>('/admin/posts', 'state', () => props.token)
const selected = ref<PostDetail | null>(null)
const reason = ref('')
const postTypes: Record<string, string> = { POST: '帖子', PROBLEM: '问题', SOLUTION: '解决方案' }
const visibilities: Record<string, string> = {
  PUBLIC: '公开', LOGIN_REQUIRED: '登录可见', PARTICIPANTS_ONLY: '仅参与人可见',
  OWNER_ONLY: '仅作者可见', ADMIN_ONLY: '仅管理员可见',
}

function search(number = 1, applyFilters = true) {
  return props.run(async () => {
    await load(number, applyFilters)
    selected.value = null
  }, '')
}

function view(id: string) {
  return props.run(async () => {
    selected.value = (await api<{ data: PostDetail }>(`/admin/posts/${id}`, props.token)).data
    reason.value = ''
  }, '')
}

function changePublication() {
  const post = selected.value
  if (!post || !reason.value.trim()) return
  const enabled = post.state === 'OFFLINE'
  return props.run(async () => {
    const result = await api<{ data: PostDetail }>(`/admin/posts/${post.id}/publication`, props.token, {
      method: 'PUT',
      body: { enabled, expectedVersion: post.version, reason: reason.value },
    })
    update(result.data, result.data.state)
    selected.value = result.data
    reason.value = ''
  }, enabled ? '帖子已恢复发布。' : '帖子已下架。')
}

onMounted(() => search())
</script>

<template>
  <section class="panel wide">
    <form class="toolbar management-toolbar" @submit.prevent="search()">
      <label>帖子标题<input v-model="query" type="search" maxlength="160"></label>
      <label>发布状态
        <select v-model="state">
          <option value="">全部</option>
          <option v-for="(label, value) in postStatuses" :key="value" :value="value">{{ label }}</option>
        </select>
      </label>
      <button type="submit" :disabled="busy">查询</button>
    </form>
    <p v-if="busy && !page" class="muted" role="status">正在加载帖子…</p>
    <p v-else-if="page && !page.items.length" class="muted">没有匹配的帖子。</p>
    <div v-if="page?.items.length" class="table-wrap">
      <table aria-label="帖子列表">
        <thead><tr><th scope="col">标题</th><th scope="col">类型</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="post in page.items" :key="post.id">
            <td>{{ post.title }}</td><td>{{ postTypes[post.type] ?? post.type }}</td><td>{{ postStatuses[post.state] ?? post.state }}</td>
            <td><button type="button" class="secondary" :disabled="busy" @click="view(post.id)">查看</button></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="page && (page.page > 1 || page.hasNext)" class="actions">
      <button type="button" class="secondary" :disabled="busy || page.page <= 1" @click="search(page.page - 1, false)">上一页</button>
      <span>第 {{ page.page }} 页</span>
      <button type="button" class="secondary" :disabled="busy || !page.hasNext" @click="search(page.page + 1, false)">下一页</button>
    </div>
    <section v-if="selected" class="management-detail" aria-labelledby="post-detail-title">
      <h2 id="post-detail-title">{{ selected.title }}</h2>
      <dl class="management-meta">
        <div><dt>作者</dt><dd>{{ selected.ownerName || selected.ownerId }}</dd></div>
        <div><dt>状态</dt><dd>{{ postStatuses[selected.state] ?? selected.state }}</dd></div>
        <div><dt>可见范围</dt><dd>{{ visibilities[selected.visibility] ?? selected.visibility }}</dd></div>
      </dl>
      <pre class="post-body">{{ selected.body }}</pre>
      <details v-if="selected.publishedBody !== null && (selected.publishedTitle !== selected.title || selected.publishedBody !== selected.body)">
        <summary>查看已审核的发布版本</summary>
        <h3>{{ selected.publishedTitle }}</h3>
        <pre class="post-body">{{ selected.publishedBody }}</pre>
      </details>
      <form v-if="selected.state === 'PUBLISHED' || (selected.state === 'OFFLINE' && selected.publishedTitle)" class="form-grid" @submit.prevent="changePublication">
        <p class="notice">{{ selected.state === 'PUBLISHED' ? '下架后，其他用户无法访问该帖子。' : '恢复后会展示原先已审核的发布版本。' }}</p>
        <label class="full">操作原因<textarea v-model.trim="reason" required maxlength="1000" rows="3"></textarea></label>
        <div class="actions full">
          <button type="submit" :disabled="busy || !reason">{{ selected.state === 'PUBLISHED' ? '确认下架' : '确认恢复发布' }}</button>
          <button type="button" class="secondary" :disabled="busy" @click="selected = null">关闭详情</button>
        </div>
      </form>
      <button v-else type="button" class="secondary" :disabled="busy" @click="selected = null">关闭详情</button>
    </section>
  </section>
</template>
