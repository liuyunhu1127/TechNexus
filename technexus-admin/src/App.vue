<script setup lang="ts">
import { ref } from 'vue'
import { api } from './api'

type Dashboard = { counts: Record<string, number>; generatedAt: string }
const token = ref(sessionStorage.getItem('tn_admin_token') ?? '')
const dashboard = ref<Dashboard | null>(null)
const error = ref('')

async function loadDashboard() {
  error.value = ''
  sessionStorage.setItem('tn_admin_token', token.value)
  try { dashboard.value = await api<Dashboard>('/admin/dashboard', token.value) }
  catch { error.value = '无法读取运营看板，请确认管理员令牌。' }
}
</script>

<template>
  <div class="shell">
    <aside><strong>TechNexus</strong><span>管理端</span><nav><a class="active" href="#dashboard">运营总览</a><a href="#audit">审核任务</a><a href="#pricing">定价确认</a><a href="#config">系统配置</a></nav></aside>
    <main>
      <header><div><p>Operations</p><h1>运营总览</h1></div><button @click="loadDashboard">刷新数据</button></header>
      <label class="token">管理员 Access Token<input v-model="token" type="password" autocomplete="off" placeholder="仅保存在当前浏览器会话"></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <section class="cards" aria-label="业务对象数量">
        <article v-for="(count, name) in dashboard?.counts" :key="name"><span>{{ name }}</span><strong>{{ count }}</strong></article>
        <article v-if="!dashboard"><span>等待连接</span><strong>—</strong></article>
      </section>
      <section class="panel"><h2>人工决定原则</h2><p>AI 仅生成建议。审核、定价、封禁和权限变更必须由有权人员明确确认，并进入追加审计。</p></section>
    </main>
  </div>
</template>
