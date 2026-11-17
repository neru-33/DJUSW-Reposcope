import { MantineProvider } from '@mantine/core'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouterProvider, createMemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me, Role } from '../api/types'
import { routes } from '../router'
import { theme } from '../theme'

const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function meResponse(role: Role): Me {
  return {
    user: { id: 7, github_id: 1234567, login: 'student-a', role, name: '홍길동' },
    auth: role === 'STUDENT' || role === 'GUEST' ? 'github' : 'operator',
    csrf_token: 'token',
    consent_required: false,
    profile_required: role === 'GUEST',
  }
}

function signedIn(role: Role) {
  fetchMock.mockImplementation(async (input) =>
    String(input).endsWith('/api/me') ? json(meResponse(role)) : new Response(null, { status: 204 }),
  )
}

function signedOut() {
  fetchMock.mockImplementation(async () => json({ error: 'unauthorized', message: '로그인이 필요합니다.' }, 401))
}

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <MantineProvider theme={theme}>
      <RouterProvider router={router} />
    </MantineProvider>,
  )
  return router
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('화면 틀', () => {
  it('로그인하지 않은 사용자가 학생 화면을 열면 시작 화면으로 보낸다', async () => {
    signedOut()
    const router = renderAt('/projects')

    expect(await screen.findByRole('heading', { name: 'V-1 시작' })).toBeTruthy()
    expect(router.state.location.search).toBe('?next=%2Fprojects')
    expect(screen.getByRole('link', { name: 'GitHub 로그인' })).toBeTruthy()
  })

  it('학생은 첫 화면이 둘러보기이고 학생 메뉴가 보인다', async () => {
    signedIn('STUDENT')
    const router = renderAt('/')

    expect(await screen.findByRole('heading', { name: 'V-19 둘러보기' })).toBeTruthy()
    expect(router.state.location.pathname).toBe('/explore')
    const nav = screen.getByRole('navigation', { name: '주 메뉴' })
    expect(Array.from(nav.querySelectorAll('a')).map((a) => a.textContent)).toEqual(['둘러보기', '내 프로젝트'])
    expect(screen.getByText('홍길동 · 학생')).toBeTruthy()
  })

  it('가입 전 사용자는 가입 화면으로 보낸다', async () => {
    signedIn('GUEST')
    renderAt('/projects/1')

    expect(await screen.findByRole('heading', { name: 'V-2 가입' })).toBeTruthy()
  })

  it('직위가 맞지 않으면 권한 없음 화면을 보여 준다', async () => {
    signedIn('PROFESSOR')
    renderAt('/admin/users')

    expect(await screen.findByRole('heading', { name: '볼 수 있는 권한이 없습니다' })).toBeTruthy()
  })

  it('없는 주소는 찾을 수 없는 화면을 보여 준다', async () => {
    signedIn('STUDENT')
    renderAt('/no-such-page')

    expect(await screen.findByRole('heading', { name: '찾을 수 없는 화면입니다' })).toBeTruthy()
  })

  it('서버에 연결하지 못하면 다시 시도할 수 있다', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    fetchMock.mockImplementation(async () => json(meResponse('STUDENT')))
    renderAt('/projects')

    await userEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('heading', { name: 'V-3 내 프로젝트' })).toBeTruthy()
  })

  it('로그아웃하면 세션을 끝내고 시작 화면으로 간다', async () => {
    signedIn('STUDENT')
    const router = renderAt('/projects')

    await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    const logoutCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/auth/logout'))
    expect(logoutCall?.[1]?.method).toBe('POST')
  })

  it('로그인 중 401을 받으면 돌아올 주소와 함께 로그인 화면으로 보낸다', async () => {
    signedIn('PROFESSOR')
    const router = renderAt('/admin/projects')
    await screen.findByRole('heading', { name: 'V-15 전체 프로젝트' })

    fetchMock.mockImplementation(async () => json({ error: 'unauthorized' }, 401))
    const { api } = await import('../api/client')
    await api.get('/api/projects').catch(() => undefined)

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/login'))
    expect(router.state.location.search).toBe('?next=%2Fadmin%2Fprojects')
  })
})
