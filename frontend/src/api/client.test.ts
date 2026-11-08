import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, fetchMe, request, setCsrfToken } from './client'

const fetchMock = vi.fn<typeof fetch>()

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function lastCall(): { url: string; init: RequestInit; headers: Headers } {
  const [url, init = {}] = fetchMock.mock.calls.at(-1) ?? []
  return { url: String(url), init, headers: new Headers(init.headers) }
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  setCsrfToken(null)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('request', () => {
  it('쿠키를 함께 보내고 GET에는 CSRF 토큰을 붙이지 않는다', async () => {
    setCsrfToken('token-1')
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 'ok' }))

    await expect(api.get('/api/health')).resolves.toEqual({ status: 'ok' })

    const { url, init, headers } = lastCall()
    expect(url).toBe('/api/health')
    expect(init.credentials).toBe('include')
    expect(headers.has('X-CSRF-Token')).toBe(false)
  })

  it('쓰기 요청에 CSRF 토큰과 JSON 본문을 보낸다', async () => {
    setCsrfToken('token-1')
    fetchMock.mockResolvedValueOnce(jsonResponse({ id: 12 }, 201))

    await api.post('/api/projects', { name: 'RepoScope' })

    const { init, headers } = lastCall()
    expect(init.method).toBe('POST')
    expect(headers.get('X-CSRF-Token')).toBe('token-1')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init.body).toBe(JSON.stringify({ name: 'RepoScope' }))
  })

  it('조회 조건에서 비어 있는 값은 빼고 주소를 만든다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ items: [], page: 1, size: 50, total: 0 }))

    await api.get('/api/projects', { page: 2, q: '리포', status: undefined, include_archived: false })

    expect(lastCall().url).toBe('/api/projects?page=2&q=%EB%A6%AC%ED%8F%AC&include_archived=false')
  })

  it('204 응답은 본문 없이 끝난다', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))

    await expect(request('/api/projects/12/archive', { method: 'POST' })).resolves.toBeUndefined()
  })

  it('오류 응답을 ApiError로 바꾼다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(
        { error: 'invalid_request', message: '입력값을 확인하세요.', fields: { name: '1~100자로 입력하세요.' } },
        400,
      ),
    )

    const error = await api.post('/api/projects', {}).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      code: 'invalid_request',
      message: '입력값을 확인하세요.',
      fields: { name: '1~100자로 입력하세요.' },
    })
  })

  it('형식이 다른 오류 응답도 ApiError로 바꾼다', async () => {
    fetchMock.mockResolvedValueOnce(new Response('Bad Gateway', { status: 502 }))

    await expect(api.get('/api/me')).rejects.toMatchObject({ status: 502, code: 'internal_error' })
  })

  it('연결 실패를 network_error로 바꾼다', async () => {
    const cause = new TypeError('Failed to fetch')
    fetchMock.mockRejectedValueOnce(cause)

    const error = await api.get('/api/health').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 0, code: 'network_error', cause })
  })

  it('요청 중단은 ApiError로 바꾸지 않는다', async () => {
    fetchMock.mockRejectedValueOnce(new DOMException('aborted', 'AbortError'))

    const error = await api.get('/api/health').catch((e: unknown) => e)

    expect(error).not.toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ name: 'AbortError' })
  })
})

describe('fetchMe', () => {
  it('응답의 CSRF 토큰을 다음 쓰기 요청에 쓴다', async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse({
          user: { id: 7, github_id: 1234567, login: 'student-a', role: 'STUDENT', name: '홍길동' },
          auth: 'github',
          csrf_token: 'token-from-me',
          consent_required: false,
          profile_required: false,
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))

    await fetchMe()
    await api.post('/auth/logout')

    expect(lastCall().headers.get('X-CSRF-Token')).toBe('token-from-me')
  })

  it('401이면 가지고 있던 CSRF 토큰을 지운다', async () => {
    setCsrfToken('old-token')
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ error: 'unauthorized', message: '로그인이 필요합니다.' }, 401))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))

    await expect(fetchMe()).rejects.toMatchObject({ status: 401, code: 'unauthorized' })
    await api.post('/auth/logout')

    expect(lastCall().headers.has('X-CSRF-Token')).toBe(false)
  })
})
