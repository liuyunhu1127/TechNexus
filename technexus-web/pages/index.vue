<script setup lang="ts">
useSeoMeta({ title: '首页', ogTitle: 'TechNexus 技术协作平台' })
</script>

<template>
  <section class="hero">
    <p class="eyebrow">Problem → Demand → Solution → Knowledge</p>
    <h1>让真实技术问题，走到可交付的解决方案</h1>
    <p>沉淀问题、结构化需求、协作交付，并把结果转化为可复用知识。</p>
    <div class="actions">
      <NuxtLink class="button primary" to="/demands">浏览需求</NuxtLink>
      <NuxtLink class="button" to="/contents">探索知识</NuxtLink>
    </div>
  </section>
  <section class="grid" aria-label="平台能力">
    <article><h2>发现问题</h2><p>从文章、帖子和真实场景中识别值得解决的技术问题。</p></article>
    <article><h2>形成需求</h2><p>明确边界、预算、截止时间与可见范围。</p></article>
    <article><h2>沉淀知识</h2><p>审核版本、方案和结果，保留完整来源链。</p></article>
  </section>
</template>
