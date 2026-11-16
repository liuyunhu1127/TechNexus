const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

type ApiOptions = Omit<RequestInit, 'body'> & { body?: unknown }

export async function api<T>(path: string, accessToken: string, options: ApiOptions = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Authorization', `Bearer ${accessToken}`)
  const method = String(options.method ?? 'GET').toUpperCase()
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) headers.set('Idempotency-Key', crypto.randomUUID())
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: 'include',
    headers,
  })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return await response.json() as T
}
