<script setup lang="ts">
const route = useRoute()
</script>

<template>
  <div>
    <header class="site-header">
      <NuxtLink class="brand" to="/">TechNexus</NuxtLink>
      <nav aria-label="主导航">
        <NuxtLink to="/contents" :aria-current="route.path.startsWith('/contents') ? 'page' : undefined">技术内容</NuxtLink>
        <NuxtLink to="/demands" :aria-current="route.path.startsWith('/demands') ? 'page' : undefined">真实需求</NuxtLink>
        <NuxtLink to="/login">登录</NuxtLink>
      </nav>
    </header>
    <main><NuxtPage /></main>
    <footer>TechNexus · Technology Community &amp; Solution Platform</footer>
  </div>
</template>
