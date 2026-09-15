const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export async function api<T>(path: string, accessToken: string): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, { headers: { Authorization: `Bearer ${accessToken}` }, credentials: 'include' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return await response.json() as T
}
