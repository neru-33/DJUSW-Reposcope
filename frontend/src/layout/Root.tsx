import { Outlet } from 'react-router'
import { AuthProvider } from '../auth/AuthProvider'

/** 모든 화면의 바깥. 로그인 정보는 라우터 안에서 불러와야 화면 이동을 할 수 있다. */
export function Root() {
  return (
    <AuthProvider>
      <Outlet />
    </AuthProvider>
  )
}
