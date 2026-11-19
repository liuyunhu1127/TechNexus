<script setup lang="ts">
import { onMounted, ref } from 'vue'

type TagOption = { id: string, name: string }

const model = defineModel<string[]>({ required: true })
const { request } = useApi()
const tags = ref<TagOption[]>([])
const loading = ref(true)
const message = ref('')

onMounted(async () => {
  try {
    const response = await request<{ items: TagOption[] }>('/tags')
    tags.value = response.items
  } catch {
    message.value = '标签暂时无法加载，仍可保存其他内容。'
  } finally {
    loading.value = false
  }
})

function toggle(id: string, checked: boolean) {
  if (checked && !model.value.includes(id)) model.value = [...model.value, id]
  if (!checked) model.value = model.value.filter(value => value !== id)
}
</script>

<template>
  <fieldset class="full association-fieldset">
    <legend>标签（最多 10 个）</legend>
    <p v-if="loading" role="status">正在加载标签…</p>
    <p v-else-if="message" class="form-footnote">{{ message }}</p>
    <p v-else-if="!tags.length" class="form-footnote">暂无可选标签，请联系运营人员先创建标签。</p>
    <div v-else class="choice-grid">
      <label v-for="tag in tags" :key="tag.id" class="choice-item">
        <input
          type="checkbox"
          :checked="model.includes(tag.id)"
          :disabled="!model.includes(tag.id) && model.length >= 10"
          @change="toggle(tag.id, ($event.target as HTMLInputElement).checked)"
        >
        {{ tag.name }}
      </label>
    </div>
  </fieldset>
</template>
