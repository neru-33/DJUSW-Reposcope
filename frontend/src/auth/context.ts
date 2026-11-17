import { createContext, useContext } from 'react'
import type { Me } from '../api/types'

export type AuthState =
  | { status: 'loading'; me: null; error: null }
  | { status: 'ready'; me: Me | null; error: null }
  | { status: 'error'; me: null; error: unknown }

export interface AuthContextValue {
  state: AuthState
  /** `GET /api/me`를 다시 불러온다. 로그인·가입 직후에 쓴다. */
  reload: () => Promise<void>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('AuthProvider 안에서만 쓸 수 있습니다.')
  return value
}
