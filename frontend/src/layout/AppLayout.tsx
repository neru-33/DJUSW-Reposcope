import { Link, NavLink, Outlet, useLocation, useSearchParams } from 'react-router'
import { homePath, roleLabels, safeNext } from '../auth/access'
import { useAuth } from '../auth/context'
import { menusFor } from '../auth/menus'
import { githubLoginUrl } from '../auth/login'

/** 상단바와 본문 틀. 기준: docs/ui-guide.md 5장, docs/screen-flow.md 3장 */
export function AppLayout() {
  const { state, logout } = useAuth()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const me = state.me
  const menus = menusFor(me)
  const onAdminPage = location.pathname.startsWith('/admin')
  // 로그인 화면으로 보내질 때 붙은 돌아갈 주소가 있으면 로그인 뒤 그곳으로 간다.
  const loginNext = safeNext(searchParams.get('next')) ?? '/explore'

  return (
    <div className="app">
      <header className="header">
        <div className="header-inner">
          <Link className="brand" to={homePath(me)}>
            RepoScope
          </Link>

          <nav className="navigation" aria-label="주 메뉴">
            {menus.map((menu) => (
              <NavLink key={menu.path} to={menu.path}>
                {menu.label}
              </NavLink>
            ))}
          </nav>

          <div className="login-area">
            {me && (
              <>
                <span className="role-badge">
                  {me.user.name || me.user.login} · {roleLabels[me.user.role]}
                </span>
                <button type="button" className="secondary-button" onClick={() => void logout()}>
                  로그아웃
                </button>
              </>
            )}
            {state.status === 'ready' && !me && !onAdminPage && (
              <a className="primary-button" href={githubLoginUrl(loginNext)}>
                GitHub 로그인
              </a>
            )}
          </div>
        </div>
      </header>

      <main className="main-content">
        <Outlet />
      </main>
    </div>
  )
}
