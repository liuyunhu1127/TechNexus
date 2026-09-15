<script setup lang="ts">
const { request } = useApi()
const { data, status, error, refresh } = await useAsyncData('contents', () => request<{ items: Record<string, unknown>[] }>('/contents'))
useSeoMeta({ title: '技术内容' })
</script>

<template>
  <section class="page-heading"><p class="eyebrow">Knowledge</p><h1>技术内容</h1><p>文章、问题、帖子与经过审核的解决方案。</p></section>
  <p v-if="status === 'pending'" role="status">正在加载内容…</p>
  <section v-else-if="error" class="notice error"><p>内容暂时无法加载。</p><button @click="refresh()">重试</button></section>
  <section v-else-if="!data?.items.length" class="notice"><h2>还没有公开内容</h2><p>第一篇经过审核的内容将在这里出现。</p></section>
  <section v-else class="list">
    <article v-for="item in data?.items" :key="String(item.id)"><h2>{{ item.title ?? item.type }}</h2><p>{{ item.summary ?? item.state }}</p></article>
  </section>
</template>
