export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly requestId: string | null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function requestJson<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json, application/problem+json')

  const response = await fetch(`/api/v1${path}`, {
    ...options,
    credentials: 'same-origin',
    headers,
  })
  if (response.status === 204) return undefined as T

  const isJson = (response.headers.get('Content-Type') ?? '').includes('json')
  const body = isJson ? await response.json() : undefined

  if (!response.ok || !isJson) {
    const detail = typeof body?.detail === 'string' ? body.detail : `接口返回 HTTP ${response.status}`
    const requestId = response.headers.get('X-Request-Id')
    throw new ApiError(detail, response.status, requestId)
  }

  return body as T
}

export async function postJson<T>(path: string, data?: Record<string, string>): Promise<T> {
  const csrf = await requestJson<{ headerName: string; token: string }>('/auth/csrf')
  const headers = new Headers({ [csrf.headerName]: csrf.token })
  if (data) headers.set('Content-Type', 'application/json')
  return requestJson<T>(path, {
    method: 'POST',
    headers,
    body: data ? JSON.stringify(data) : undefined,
  })
}
