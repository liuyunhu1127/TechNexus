<script setup lang="ts">
const { isAuthenticated } = useApi()
const purpose = ref<'ATTACHMENT' | 'IMAGE'>('ATTACHMENT')
const fileIds = ref<string[]>([])
useSeoMeta({ title: '上传文件' })
</script>

<template>
  <section class="page-heading"><p class="eyebrow">Files</p><h1>上传文件</h1><p>文件先直传对象存储，再由服务端校验大小、MIME 与摘要并进入病毒扫描。</p></section>
  <section v-if="!isAuthenticated" class="notice"><p>上传文件需要登录。</p><NuxtLink class="button primary" to="/login">前往登录</NuxtLink></section>
  <section v-else class="editor-card">
    <label>文件用途<select v-model="purpose"><option value="ATTACHMENT">附件</option><option value="IMAGE">图片</option></select></label>
    <FileAttachmentPicker v-model="fileIds" :purpose="purpose" />
    <p class="form-footnote">已通过扫描的文件可在创建或编辑内容时绑定。</p>
  </section>
</template>
