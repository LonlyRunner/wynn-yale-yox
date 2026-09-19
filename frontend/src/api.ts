const JSON_HEADERS = { 'Content-Type': 'application/json' }

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api${path}`, { credentials: 'include', ...init, headers: { ...JSON_HEADERS, ...init.headers } })
  if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`)
  return response.status === 204 ? (undefined as T) : response.json()
}
