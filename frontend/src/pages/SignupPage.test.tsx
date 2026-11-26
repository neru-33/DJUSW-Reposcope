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

function me(role: Role): Me {
  return {
    user: { id: 7, github_id: 1234567, login: 'student-a', role, name: role === 'GUEST' ? null : '홍길동' },
    auth: 'github',
    csrf_token: 'token',
    consent_required: role === 'GUEST',
    profile_required: role === 'GUEST',
  }
}

/** 가입 전 사용자로 시작하고, 가입 요청에는 `profile` 응답을 준다. 가입이 성공하면 학생이 된다. */
function guestWith(profile: () => Response) {
  let role: Role = 'GUEST'
  fetchMock.mockImplementation(async (input, init) => {
    const url = String(input)
    if (url.endsWith('/api/me')) return json(me(role))
    if (url.endsWith('/api/me/profile') && init?.method === 'POST') {
      const response = profile()
      if (response.status === 204) role = 'STUDENT'
      return response
    }
    return new Response(null, { status: 204 })
  })
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

async function fill({ studentId = '20261234', name = '홍길동', department = '', consent = true } = {}) {
  await screen.findByRole('heading', { level: 1, name: '가입' })
  if (studentId) await userEvent.type(screen.getByRole('textbox', { name: /학번/ }), studentId)
  if (name) await userEvent.type(screen.getByRole('textbox', { name: /이름/ }), name)
  if (department) await userEvent.type(screen.getByRole('textbox', { name: /학과/ }), department)
  if (consent) await userEvent.click(screen.getByRole('checkbox'))
  await userEvent.click(screen.getByRole('button', { name: '가입' }))
}

function profileCalls() {
  return fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/me/profile'))
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('V-1 시작', () => {
  it('수집 범위와 참고자료 안내를 보여 주고, 로그인 뒤 둘러보기로 돌아온다', async () => {
    fetchMock.mockImplementation(async () => json({ error: 'unauthorized' }, 401))
    renderAt('/')

    expect(await screen.findByRole('heading', { name: '연결한 저장소에서 가져오는 정보' })).toBeTruthy()
    expect(screen.getByText('활동 기록은 평가 자료가 아닌 참고용입니다.')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'GitHub 로그인' })).toBeNull()
    const logins = screen.getAllByRole('link', { name: 'GitHub으로 계속하기' })
    expect(logins).toHaveLength(1)
    expect(logins[0].getAttribute('href')).toBe('/auth/github/login?next=%2Fexplore')
  })
})

describe('V-2 가입', () => {
  it('공개 시 다른 학생에게 보이는 정보를 안내한다', async () => {
    guestWith(() => new Response(null, { status: 204 }))
    renderAt('/signup')

    expect(
      await screen.findByText(
        '프로젝트를 학생 모두에게 공개하면 이름, GitHub 로그인 이름, 프로필 사진, 활동 기록이 다른 팀 학생에게도 보입니다.',
      ),
    ).toBeTruthy()
    expect(screen.getByText('서비스 운영 종료일(2026년 12월 31일)까지. 종료 후 지체 없이 파기합니다.')).toBeTruthy()
  })

  it('학번은 8자리 숫자만 받는다', async () => {
    guestWith(() => new Response(null, { status: 204 }))
    renderAt('/signup')

    await fill({ studentId: '2026abcd' })

    expect(screen.getByText('학번 8자리를 숫자로 입력하세요.')).toBeTruthy()
    expect(profileCalls()).toHaveLength(0)
  })

  it('입력이 잘못되면 보내지 않고 입력란 아래에 고칠 내용을 표시한다', async () => {
    guestWith(() => new Response(null, { status: 204 }))
    renderAt('/signup')

    await fill({ studentId: '2026123', name: '', consent: false })

    expect(screen.getByText('학번 8자리를 숫자로 입력하세요.')).toBeTruthy()
    expect(screen.getByText('이름을 입력하세요.')).toBeTruthy()
    expect(screen.getByText('동의해야 가입할 수 있습니다.')).toBeTruthy()
    expect(profileCalls()).toHaveLength(0)
  })

  it('가입하면 정보를 보내고 둘러보기로 이동한다', async () => {
    guestWith(() => new Response(null, { status: 204 }))
    const router = renderAt('/signup')

    await fill({ studentId: '20261234', name: ' 홍길동 ', department: '컴퓨터공학과' })

    await waitFor(() => expect(router.state.location.pathname).toBe('/explore'))
    const [, init] = profileCalls()[0]
    expect(JSON.parse(String(init?.body))).toEqual({
      name: '홍길동',
      student_id: '20261234',
      department: '컴퓨터공학과',
      consent: true,
    })
    expect(new Headers(init?.headers).get('X-CSRF-Token')).toBe('token')
  })

  it('학과를 비우면 null로 보낸다', async () => {
    guestWith(() => new Response(null, { status: 204 }))
    const router = renderAt('/signup')

    await fill()

    await waitFor(() => expect(router.state.location.pathname).toBe('/explore'))
    expect(JSON.parse(String(profileCalls()[0][1]?.body)).department).toBeNull()
  })

  it('이미 등록된 학번이면 학번 입력란 아래에 안내한다', async () => {
    guestWith(() => json({ error: 'duplicate_student_id', message: '이미 등록된 학번입니다.' }, 409))
    const router = renderAt('/signup')

    await fill()

    expect(await screen.findByText('이미 가입된 학번입니다. 학번을 다시 확인하세요.')).toBeTruthy()
    expect(screen.getByRole('textbox', { name: /학번/ }).getAttribute('aria-invalid')).toBe('true')
    expect(router.state.location.pathname).toBe('/signup')
  })

  it('서버가 동의 누락으로 응답하면 동의 아래에 안내한다', async () => {
    guestWith(() => json({ error: 'consent_required', message: '개인정보 수집·이용에 동의해 주세요.' }, 400))
    renderAt('/signup')

    await fill()

    expect(await screen.findByText('동의해야 가입할 수 있습니다.')).toBeTruthy()
  })

  it('가입할 수 없는 상태로 거부되면 사유를 폼 안에 보여 준다', async () => {
    guestWith(() => json({ error: 'forbidden', message: '비활성화된 계정입니다.' }, 403))
    const router = renderAt('/signup')

    await fill()

    expect((await screen.findByRole('alert')).textContent).toBe('비활성화된 계정입니다.')
    expect(router.state.location.pathname).toBe('/signup')
    expect(screen.getByRole('button', { name: '가입' })).toBeTruthy()
  })

  it('다른 곳에서 이미 가입했으면 거부되어도 둘러보기로 보낸다', async () => {
    let role: Role = 'GUEST'
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input)
      if (url.endsWith('/api/me')) return json(me(role))
      if (url.endsWith('/api/me/profile') && init?.method === 'POST') {
        role = 'STUDENT'
        return json({ error: 'forbidden', message: '가입 정보를 저장할 수 없습니다.' }, 403)
      }
      return new Response(null, { status: 204 })
    })
    const router = renderAt('/signup')

    await fill()

    await waitFor(() => expect(router.state.location.pathname).toBe('/explore'))
  })
})
