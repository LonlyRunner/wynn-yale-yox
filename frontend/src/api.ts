const JSON_HEADERS = { 'Content-Type': 'application/json' }

export function randomUUID() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // getRandomValues also works when accessing a development server over HTTP.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 15) | 64; bytes[8] = (bytes[8]! & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`
}

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
  const headers = init.body instanceof FormData ? init.headers : { ...JSON_HEADERS, ...init.headers }
  try {
    response = await fetch(`/api${path}`, { credentials: 'include', ...init, headers })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR')
  }
  if (!response.ok) throw new ApiError(response.status, (await response.text()) || `HTTP ${response.status}`)
  return response.status === 204 ? (undefined as T) : response.json()
}
