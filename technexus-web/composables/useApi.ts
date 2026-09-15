type ApiOptions = Parameters<typeof $fetch>[1]

export function useApi() {
  const config = useRuntimeConfig()
  const accessToken = useState<string | null>('access-token', () => null)

  async function request<T>(path: string, options: ApiOptions = {}): Promise<T> {
    const headers = new Headers(options.headers as HeadersInit | undefined)
    if (accessToken.value) headers.set('Authorization', `Bearer ${accessToken.value}`)
    const method = String(options.method ?? 'GET').toUpperCase()
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

  return { accessToken, request }
}
