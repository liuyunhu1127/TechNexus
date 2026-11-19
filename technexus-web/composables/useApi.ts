type ApiOptions = Parameters<typeof $fetch>[1]

export function useApi() {
  const config = useRuntimeConfig()
  const accessToken = useState<string | null>('access-token', () => null)
  const csrfToken = useState<string | null>('csrf-token', () => null)
  const sessionReady = useState<boolean>('session-ready', () => false)
  const isAuthenticated = computed(() => Boolean(accessToken.value))

  async function request<T>(path: string, options: ApiOptions = {}): Promise<T> {
    const headers = new Headers(options.headers as HeadersInit | undefined)
    if (accessToken.value) headers.set('Authorization', `Bearer ${accessToken.value}`)
    const method = String(options.method ?? 'GET').toUpperCase()
    if (['/auth/refresh', '/auth/logout'].includes(path) && csrfToken.value) {
      headers.set('X-CSRF-Token', csrfToken.value)
    }
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)
      && !['/auth/login', '/auth/refresh'].includes(path)
      && !headers.has('Idempotency-Key')) {
      headers.set('Idempotency-Key', crypto.randomUUID())
    }
    return await $fetch<T>(path, {
      ...options,
      baseURL: config.public.apiBase,
      credentials: 'include',
      headers
    })
  }

  function persistCsrf(value: string | null) {
    csrfToken.value = value
    if (!import.meta.client) return
    if (value) sessionStorage.setItem('technexus-csrf-token', value)
    else sessionStorage.removeItem('technexus-csrf-token')
  }

  async function restoreSession() {
    if (sessionReady.value) return isAuthenticated.value
    if (import.meta.client && !csrfToken.value) {
      csrfToken.value = sessionStorage.getItem('technexus-csrf-token')
    }
    if (!csrfToken.value) {
      sessionReady.value = true
      return false
    }
    try {
      const response = await request<{ accessToken: string, csrfToken: string }>('/auth/refresh', { method: 'POST' })
      accessToken.value = response.accessToken
      persistCsrf(response.csrfToken)
      return true
    } catch {
      accessToken.value = null
      persistCsrf(null)
      return false
    } finally {
      sessionReady.value = true
    }
  }

  async function logout() {
    try {
      if (csrfToken.value) await request('/auth/logout', { method: 'POST' })
    } finally {
      accessToken.value = null
      persistCsrf(null)
      sessionReady.value = true
    }
  }

  return { accessToken, csrfToken, isAuthenticated, logout, persistCsrf, request, restoreSession, sessionReady }
}
