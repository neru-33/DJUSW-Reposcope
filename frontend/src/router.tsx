import { createBrowserRouter, type RouteObject } from 'react-router'
import { RequireAccess } from './auth/RequireAccess'
import { config } from './config'
import { AppLayout } from './layout/AppLayout'
import { Root } from './layout/Root'
import { ErrorPage } from './pages/ErrorPage'
import { ScreenPage } from './pages/ScreenPage'
import { screens } from './screens'

const screenRoutes: RouteObject[] = screens.map((screen) => {
  const element = (
    <RequireAccess access={screen.access}>
      <ScreenPage screen={screen} />
    </RequireAccess>
  )
  return screen.path === '/' ? { index: true, element } : { path: screen.path.slice(1), element }
})

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <Root />,
    errorElement: <ErrorPage kind="unknown" />,
    children: [
      {
        element: <AppLayout />,
        children: [
          {
            errorElement: <ErrorPage kind="unknown" />,
            children: [...screenRoutes, { path: '*', element: <ErrorPage kind="not_found" /> }],
          },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(routes, { basename: config.routerBasename })
