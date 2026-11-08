import { config } from '../config'
import type { ApiErrorBody, Me } from './types'

const CSRF_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let csrfToken: string | null = null

/** 쓰기 요청에 보낼 CSRF 토큰을 정한다. 값은 GET /api/me 응답에서 받는다. */
export function setCsrfToken(token: string | null): void {
  csrfToken = token
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fields?: Record<string, string>

  constructor(status: number, body: ApiErrorBody, options?: ErrorOptions) {
    super(body.message ?? body.error, options)
    this.name = 'ApiError'
    this.status = status
    this.code = body.error
    this.fields = body.fields
  }
}

export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  method?: string
  body?: unknown
  query?: Record<string, QueryValue>
  signal?: AbortSignal
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const url = `${config.apiBaseUrl}${path}`
  if (!query) return url

  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== null && value !== undefined) params.append(key, String(value))
  }
  const search = params.toString()
  return search ? `${url}?${search}` : url
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function toErrorBody(data: unknown, status: number): ApiErrorBody {
  if (data && typeof data === 'object' && typeof (data as ApiErrorBody).error === 'string') {
    return data as ApiErrorBody
  }
  return {
    error: status >= 500 ? 'internal_error' : 'unknown_error',
    message: `요청을 처리하지 못했습니다. (HTTP ${status})`,
  }
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

/**
 * API를 호출한다. 쿠키를 함께 보내고, 쓰기 요청에는 CSRF 토큰을 붙인다.
 * 실패 응답과 연결 실패는 ApiError로 던진다. 연결 실패는 status 0, code `network_error`이다.
 * 요청을 중단해 생긴 AbortError는 그대로 던진다.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers({ Accept: 'application/json' })
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (CSRF_METHODS.has(method) && csrfToken) headers.set('X-CSRF-Token', csrfToken)

  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method,
      headers,
      credentials: 'include',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (isAbortError(error)) throw error
    throw new ApiError(
      0,
      { error: 'network_error', message: '서버에 연결하지 못했습니다. 네트워크 상태를 확인하세요.' },
      { cause: error },
    )
  }

  if (response.status === 204) return undefined as T

  const data = await readJson(response)
  if (!response.ok) throw new ApiError(response.status, toErrorBody(data, response.status))
  return data as T
}

export const api = {
  get: <T>(path: string, query?: Record<string, QueryValue>) => request<T>(path, { query }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

/**
 * 현재 사용자를 조회하고, 응답의 CSRF 토큰을 이후 쓰기 요청에 쓴다.
 * 로그인하지 않았으면(401) 가지고 있던 토큰을 지운다.
 */
export async function fetchMe(): Promise<Me> {
  try {
    const me = await request<Me>('/api/me')
    setCsrfToken(me.csrf_token)
    return me
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) setCsrfToken(null)
    throw error
  }
}
