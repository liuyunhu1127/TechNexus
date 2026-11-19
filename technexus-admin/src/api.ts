const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

type ApiOptions = Omit<RequestInit, 'body'> & { body?: unknown }

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function api<T>(path: string, accessToken = '', options: ApiOptions = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  const method = String(options.method ?? 'GET').toUpperCase()
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)
    && !['/auth/login', '/auth/refresh'].includes(path)
    && !headers.has('Idempotency-Key')) {
    headers.set('Idempotency-Key', crypto.randomUUID())
  }
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: 'include',
    headers,
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { code?: string, title?: string, message?: string } | null
    throw new ApiError(response.status, problem?.code ?? 'REQUEST_FAILED', problem?.title ?? problem?.message ?? `HTTP ${response.status}`)
  }
  if (response.status === 204) return undefined as T
  return await response.json() as T
}
