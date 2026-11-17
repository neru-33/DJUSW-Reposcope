import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { ErrorState, PageLoader } from '../components/states'
import { ErrorPage } from '../pages/ErrorPage'
import type { Access } from '../screens'
import { checkAccess } from './access'
import { useAuth } from './context'

/** 화면 접근 규칙(docs/screen-flow.md 1·2장)에 맞지 않으면 다른 화면으로 보낸다. */
export function RequireAccess({ access, children }: { access: Access; children: ReactNode }) {
  const { state, reload } = useAuth()
  const location = useLocation()

  if (state.status === 'loading') return <PageLoader />
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={() => void reload()} />

  const result = checkAccess(access, state.me, location.pathname + location.search)
  if (result.kind === 'redirect') return <Navigate to={result.to} replace />
  if (result.kind === 'forbidden') return <ErrorPage kind="forbidden" />
  return children
}
