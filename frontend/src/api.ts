const JSON_HEADERS = { 'Content-Type': 'application/json' }

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, { credentials: 'include', ...init, headers: { ...JSON_HEADERS, ...init.headers } })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR')
  }
  if (!response.ok) throw new ApiError(response.status, (await response.text()) || `HTTP ${response.status}`)
  return response.status === 204 ? (undefined as T) : response.json()
}
