<script setup lang="ts">
const route = useRoute()
const { isAuthenticated, logout } = useApi()

async function signOut() {
  await logout()
  await navigateTo('/')
}
</script>

<template>
  <div>
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <header class="site-header">
      <NuxtLink class="brand" to="/">TechNexus</NuxtLink>
      <nav aria-label="主导航">
        <NuxtLink to="/contents" :aria-current="route.path.startsWith('/contents') ? 'page' : undefined">技术内容</NuxtLink>
        <NuxtLink to="/demands" :aria-current="route.path.startsWith('/demands') ? 'page' : undefined">真实需求</NuxtLink>
        <template v-if="isAuthenticated">
          <NuxtLink to="/contents/new">发布内容</NuxtLink>
          <NuxtLink to="/demands/new">发布需求</NuxtLink>
          <NuxtLink to="/files/upload">上传文件</NuxtLink>
          <NuxtLink to="/me">个人中心</NuxtLink>
          <button class="nav-button" type="button" @click="signOut">退出</button>
        </template>
        <template v-else>
          <NuxtLink to="/login">登录</NuxtLink>
          <NuxtLink to="/register">注册</NuxtLink>
        </template>
      </nav>
    </header>
    <main id="main-content" tabindex="-1"><NuxtPage /></main>
    <footer>TechNexus · Technology Community &amp; Solution Platform</footer>
  </div>
</template>
