import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError, api, fetchMe, setCsrfToken, setUnauthorizedHandler } from '../api/client'
import type { Me } from '../api/types'
import { notifyError } from '../components/notify'
import { loginPath } from './access'
import { AuthContext, type AuthState } from './context'

/** `GET /api/me` 결과를 로그인 상태로 바꾼다. 401은 로그인하지 않은 상태다. */
async function loadMe(): Promise<AuthState> {
  try {
    return { status: 'ready', me: await fetchMe(), error: null }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return { status: 'ready', me: null, error: null }
    return { status: 'error', me: null, error }
  }
}

/** 현재 사용자를 한 곳에서 불러오고, 세션 만료와 로그아웃을 처리한다. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading', me: null, error: null })
  const navigate = useNavigate()
  const location = useLocation()
  const meRef = useRef<Me | null>(null)
  const locationRef = useRef(location)

  useEffect(() => {
    locationRef.current = location
  }, [location])

  const apply = useCallback((next: AuthState) => {
    meRef.current = next.me
    setState(next)
  }, [])

  const reload = useCallback(async () => {
    apply({ status: 'loading', me: null, error: null })
    apply(await loadMe())
  }, [apply])

  useEffect(() => {
    let active = true
    void loadMe().then((next) => {
      if (active) apply(next)
    })
    return () => {
      active = false
    }
  }, [apply])

  // 로그인 중 401을 받으면 세션이 끝난 것이다. 학생은 V-1, 운영자는 V-13으로 보낸다.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      const previous = meRef.current
      if (!previous) return
      meRef.current = null
      setCsrfToken(null)
      setState({ status: 'ready', me: null, error: null })
      const { pathname, search } = locationRef.current
      navigate(loginPath(pathname + search, previous.auth === 'operator'), { replace: true })
    })
    return () => setUnauthorizedHandler(null)
  }, [navigate])

  const logout = useCallback(async () => {
    const previous = meRef.current
    try {
      await api.post<void>('/auth/logout')
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) {
        notifyError('로그아웃하지 못했습니다. 다시 시도하세요.')
        return
      }
    }
    meRef.current = null
    setCsrfToken(null)
    setState({ status: 'ready', me: null, error: null })
    navigate(previous?.auth === 'operator' ? '/admin/login' : '/', { replace: true })
  }, [navigate])

  const value = useMemo(() => ({ state, reload, logout }), [state, reload, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
