<script setup lang="ts">
import { clearError, type NuxtError } from '#app'

const props = defineProps<{ error: NuxtError }>()
const isNotFound = computed(() => props.error.statusCode === 404)

useSeoMeta({
  title: () => isNotFound.value ? '页面不存在' : '页面暂时不可用',
  robots: 'noindex, nofollow'
})

function returnHome() {
  clearError({ redirect: '/' })
}
</script>

<template>
  <div>
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <main id="main-content" tabindex="-1">
      <section class="auth-card" role="alert">
        <p class="eyebrow">{{ error.statusCode }}</p>
        <h1>{{ isNotFound ? '没有找到这个页面' : '页面暂时不可用' }}</h1>
        <p>{{ isNotFound ? '链接可能已经失效，或者地址输入有误。' : '请稍后重试；如果问题持续，请记录发生时间并联系管理员。' }}</p>
        <button class="button primary" type="button" @click="returnHome">返回首页</button>
      </section>
    </main>
  </div>
</template>
