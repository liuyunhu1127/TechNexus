<script setup lang="ts">
import { useState } from '#imports'
import { watch } from 'vue'

const route = useRoute()
const { accessToken, isAuthenticated, logout, request } = useApi()
const avatarUrl = useState<string | null>('account-avatar-url', () => null)

if (import.meta.client) {
  watch(accessToken, async (token, _, onCleanup) => {
    let cancelled = false
    onCleanup(() => { cancelled = true })
    avatarUrl.value = null
    if (!token) return
    try {
      const { data } = await request<{ data: { url?: string } }>('/me/avatar')
      if (!cancelled) avatarUrl.value = data.url ?? null
    } catch {
      // The navigation remains available when the object store is unavailable.
    }
  }, { immediate: true })
}

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
          <NuxtLink to="/me" class="account-link">
            <img v-if="avatarUrl" :src="avatarUrl" alt="" referrerpolicy="no-referrer">
            个人中心
          </NuxtLink>
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

<style scoped>
.account-link { display: inline-flex; align-items: center; gap: .4rem; }
.account-link img { width: 1.5rem; height: 1.5rem; border-radius: 50%; object-fit: cover; }
</style>
