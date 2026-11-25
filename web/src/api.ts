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
  const isJson = (response.headers.get('Content-Type') ?? '').includes('json')
  const body = isJson ? await response.json() : undefined

  if (!response.ok || !isJson) {
    const detail = typeof body?.detail === 'string' ? body.detail : `接口返回 HTTP ${response.status}`
    const requestId = response.headers.get('X-Request-Id')
    throw new ApiError(detail, response.status, requestId)
  }

  return body as T
}
