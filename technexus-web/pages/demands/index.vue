<script setup lang="ts">
const { request } = useApi()
const { data, status, error, refresh } = await useAsyncData('demands', () => request<{ items: Record<string, unknown>[] }>('/demands'))
useSeoMeta({ title: '真实需求' })
</script>

<template>
  <section class="page-heading"><p class="eyebrow">Demand</p><h1>真实需求</h1><p>从明确的问题出发，寻找可以交付的技术方案。</p></section>
  <p v-if="status === 'pending'" role="status">正在加载需求…</p>
  <section v-else-if="error" class="notice error"><p>需求暂时无法加载。</p><button @click="refresh()">重试</button></section>
  <section v-else-if="!data?.items.length" class="notice"><h2>暂时没有公开需求</h2><p>通过审核与定价的需求会显示在这里。</p></section>
  <section v-else class="list">
    <article v-for="item in data?.items" :key="String(item.id)"><h2>{{ item.title }}</h2><p>状态：{{ item.state }}</p></article>
  </section>
</template>
