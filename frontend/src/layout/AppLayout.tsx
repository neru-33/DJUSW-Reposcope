import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { homePath, roleLabels } from '../auth/access'
import { useAuth } from '../auth/context'
import { menusFor } from '../auth/menus'
import { githubLoginUrl } from '../auth/login'

/** 상단바와 본문 틀. 기준: docs/ui-guide.md 5장, docs/screen-flow.md 3장 */
export function AppLayout() {
  const { state, logout } = useAuth()
  const location = useLocation()
  const me = state.me
  const menus = menusFor(me)
  const onAdminPage = location.pathname.startsWith('/admin')

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
              <a className="primary-button" href={githubLoginUrl('/explore')}>
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
