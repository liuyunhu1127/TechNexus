import { ref, shallowRef } from 'vue'
import { api } from '../api'

export type RunAction = (action: () => Promise<void>, success: string) => Promise<void>

export const userStatuses: Record<string, string> = {
  REGISTERED: '待激活',
  ACTIVE: '正常',
  LOCKED: '锁定',
  DISABLED: '已停用',
  CANCELLED: '已注销',
}

export const postStatuses: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  WAITING_PRICING: '待定价',
  PUBLISHED: '已发布',
  REJECTED: '审核未通过',
  OFFLINE: '已下架',
  DELETED: '已删除',
  BLOCKED: '已封禁',
}

type Page<T> = { items: T[], page: number, hasNext: boolean }

export function useManagementPage<T extends { id: string }>(endpoint: string, stateKey: string, token: () => string) {
  const query = ref('')
  const state = ref('')
  const page = shallowRef<Page<T> | null>(null)
  let appliedQuery = ''
  let appliedState = ''

  async function load(number: number, applyFilters: boolean) {
    const nextQuery = applyFilters ? query.value.trim() : appliedQuery
    const nextState = applyFilters ? state.value : appliedState
    const search = new URLSearchParams({ page: String(number) })
    if (nextQuery) search.set('q', nextQuery)
    if (nextState) search.set(stateKey, nextState)
    page.value = await api<Page<T>>(`${endpoint}?${search}`, token())
    appliedQuery = nextQuery
    appliedState = nextState
  }

  function update(item: T, itemState: string) {
    if (!page.value) return
    const items = appliedState && itemState !== appliedState
      ? page.value.items.filter(row => row.id !== item.id)
      : page.value.items.map(row => row.id === item.id ? item : row)
    page.value = { ...page.value, items }
  }

  return { query, state, page, load, update }
}
