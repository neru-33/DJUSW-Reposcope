import { MantineProvider } from '@mantine/core'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouterProvider, createMemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Me, OperatorStage } from '../api/types'
import { routes } from '../router'
import { theme } from '../theme'

const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const operator: Me = {
  user: { id: 1, github_id: null, login: 'admin01', role: 'SUPER_ADMIN', name: null },
  auth: 'operator',
  csrf_token: 'session-token',
  // 운영자는 동의하지 않으므로 서버가 true를 줄 수 있다. 가입 화면으로 보내지 않아야 한다.
  consent_required: true,
  profile_required: false,
}

/**
 * 운영자 API 흉내. `stage`가 현재 세션 단계이고, 로그인 전은 null이다.
 * 요청 본문은 `bodies`에 경로별로 남긴다.
 */
function operatorApi(start: {
  login?: OperatorStage
  setup?: OperatorStage | null
  afterPassword?: OperatorStage
  signedIn?: Me
}) {
  let stage: OperatorStage | null = start.setup ?? null
  // 운영자 로그인 요청은 이전 로그인을 끝낸다.
  let previous = start.signedIn ?? null
  const bodies: Record<string, unknown> = {}
  fetchMock.mockImplementation(async (input, init) => {
    const path = new URL(String(input), 'http://localhost').pathname
    if (init?.body) bodies[path] = JSON.parse(String(init.body))
    const code = (bodies[path] as { code?: string } | undefined)?.code

    if (path === '/api/me') {
      if (previous) return json(previous)
      return stage === 'ACTIVE' ? json(operator) : json({ error: 'unauthorized' }, 401)
    }
    if (path === '/auth/admin/login') {
      const { password } = bodies[path] as { password: string }
      if (password !== 'right') {
        return json({ error: 'invalid_credentials', message: 'ID 또는 비밀번호가 올바르지 않습니다.' }, 401)
      }
      previous = null
      stage = start.login ?? 'OTP'
      return json({ stage, csrf_token: 'pending-token' })
    }
    if (path === '/auth/admin/state') {
      return stage && stage !== 'ACTIVE' ? json({ stage, csrf_token: 'pending-token' }) : json({ error: 'unauthorized' }, 401)
    }
    if (path === '/auth/admin/password') {
      stage = start.afterPassword ?? 'OTP_SETUP'
      return json({ stage, csrf_token: 'pending-token' })
    }
    if (path === '/auth/admin/otp/setup' && init?.method === 'GET') {
      return json({ secret: 'JBSWY3DPEHPK3PXP', otpauth_uri: 'otpauth://totp/RepoScope:admin01?secret=JBSWY3DPEHPK3PXP' })
    }
    if (path === '/auth/admin/otp' || path === '/auth/admin/otp/setup') {
      if (code !== '123456') return json({ error: 'invalid_credentials', message: 'OTP 코드가 올바르지 않습니다.' }, 401)
      stage = 'ACTIVE'
      return json({ stage, csrf_token: 'session-token' })
    }
    return new Response(null, { status: 204 })
  })
  return bodies
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

function headersOf(path: string) {
  const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith(path))
  return new Headers(call?.[1]?.headers)
}

async function signIn(password = 'right') {
  await userEvent.type(await screen.findByRole('textbox', { name: /ID/ }), 'admin01')
  await userEvent.type(screen.getByLabelText(/비밀번호/), password)
  await userEvent.click(screen.getByRole('button', { name: '다음' }))
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

describe('V-13 운영자 로그인', () => {
  it('ID와 비밀번호가 틀리면 안내하고 그대로 남는다', async () => {
    operatorApi({})
    renderAt('/admin/login')

    await signIn('wrong')

    expect((await screen.findByRole('alert')).textContent).toBe('ID 또는 비밀번호가 올바르지 않습니다.')
    expect(screen.queryByRole('textbox', { name: /OTP/ })).toBeNull()
  })

  it('비밀번호 다음 OTP를 확인하고, 돌아갈 주소로 이동한다', async () => {
    operatorApi({ login: 'OTP' })
    const router = renderAt('/admin/login?next=%2Fadmin%2Fusers')

    await signIn()
    await userEvent.type(await screen.findByRole('textbox', { name: /OTP/ }), '000000')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))
    expect((await screen.findByRole('alert')).textContent).toBe('OTP 코드가 올바르지 않습니다.')

    await userEvent.type(screen.getByRole('textbox', { name: /OTP/ }), '123456')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/users'))
    expect(headersOf('/auth/admin/otp').get('X-CSRF-Token')).toBe('pending-token')
  })

  it('OTP는 6자리 숫자만 보낸다', async () => {
    operatorApi({ login: 'OTP' })
    renderAt('/admin/login')

    await signIn()
    await userEvent.type(await screen.findByRole('textbox', { name: /OTP/ }), '12345')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(screen.getByText('인증 앱에 표시된 6자리 숫자를 입력하세요.')).toBeTruthy()
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/auth/admin/otp'))).toBe(false)
  })

  it('설정 단계 계정은 V-14로 보낸다', async () => {
    operatorApi({ login: 'PASSWORD_CHANGE' })
    const router = renderAt('/admin/login')

    await signIn()

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/setup'))
    expect(await screen.findByRole('heading', { level: 1, name: '초기 설정' })).toBeTruthy()
  })

  it('다른 사용자로 로그인한 상태에서도 설정 단계 계정은 V-14로 보낸다', async () => {
    const student: Me = {
      user: { id: 2, github_id: 100, login: 'student', role: 'STUDENT', name: '홍길동' },
      auth: 'github',
      csrf_token: 'student-token',
      consent_required: false,
      profile_required: false,
    }
    operatorApi({ login: 'PASSWORD_CHANGE', signedIn: student })
    const router = renderAt('/admin/login')

    await signIn()

    expect(await screen.findByRole('heading', { level: 1, name: '초기 설정' })).toBeTruthy()
    expect(router.state.location.pathname).toBe('/admin/setup')
  })
})

describe('V-14 운영자 초기 설정', () => {
  it('설정 단계 세션이 없으면 V-13으로 보낸다', async () => {
    operatorApi({})
    const router = renderAt('/admin/setup')

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/login'))
  })

  it('ID와 비밀번호를 바꾸고 OTP를 등록하면 V-15로 이동한다', async () => {
    const bodies = operatorApi({ setup: 'PASSWORD_CHANGE' })
    const router = renderAt('/admin/setup')

    await userEvent.type(await screen.findByLabelText(/임시 비밀번호/), 'temp')
    await userEvent.type(screen.getByRole('textbox', { name: /새 ID/ }), ' admin01 ')
    await userEvent.type(screen.getByLabelText(/^새 비밀번호( \*)?$/), 'new-password')
    await userEvent.type(screen.getByLabelText(/새 비밀번호 확인/), 'other')
    await userEvent.click(screen.getByRole('button', { name: 'ID와 비밀번호 변경' }))
    expect(screen.getByText('새 비밀번호가 서로 다릅니다.')).toBeTruthy()
    expect(bodies['/auth/admin/password']).toBeUndefined()

    await userEvent.clear(screen.getByLabelText(/새 비밀번호 확인/))
    await userEvent.type(screen.getByLabelText(/새 비밀번호 확인/), 'new-password')
    await userEvent.click(screen.getByRole('button', { name: 'ID와 비밀번호 변경' }))

    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeTruthy()
    expect(screen.getByTitle('OTP 등록 QR 코드')).toBeTruthy()
    expect(bodies['/auth/admin/password']).toEqual({
      current_password: 'temp',
      new_username: 'admin01',
      new_password: 'new-password',
    })

    await userEvent.type(screen.getByRole('textbox', { name: /OTP/ }), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'OTP 등록' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/projects'))
  })

  it('비밀번호만 바꾸고 OTP가 이미 등록된 계정은 V-13의 OTP 입력으로 보낸다', async () => {
    operatorApi({ setup: 'PASSWORD_CHANGE', afterPassword: 'OTP' })
    const router = renderAt('/admin/setup')

    await userEvent.type(await screen.findByLabelText(/임시 비밀번호/), 'temp')
    await userEvent.type(screen.getByRole('textbox', { name: /새 ID/ }), 'admin01')
    await userEvent.type(screen.getByLabelText(/^새 비밀번호( \*)?$/), 'new-password')
    await userEvent.type(screen.getByLabelText(/새 비밀번호 확인/), 'new-password')
    await userEvent.click(screen.getByRole('button', { name: 'ID와 비밀번호 변경' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/login'))
    expect(await screen.findByRole('textbox', { name: /OTP/ })).toBeTruthy()
  })
})
