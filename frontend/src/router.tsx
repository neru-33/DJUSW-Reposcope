import type { ComponentType } from 'react'
import { createBrowserRouter, type RouteObject } from 'react-router'
import { RequireAccess } from './auth/RequireAccess'
import { config } from './config'
import { AppLayout } from './layout/AppLayout'
import { Root } from './layout/Root'
import { ErrorPage } from './pages/ErrorPage'
import { ScreenPage } from './pages/ScreenPage'
import { SignupPage } from './pages/SignupPage'
import { StartPage } from './pages/StartPage'
import { screens } from './screens'

/** 만든 화면. 없는 화면은 임시 화면(ScreenPage)을 보여 준다. */
const pages: Partial<Record<string, ComponentType>> = {
  'V-1': StartPage,
  'V-2': SignupPage,
}

const screenRoutes: RouteObject[] = screens.map((screen) => {
  const Page = pages[screen.code]
  const element = (
    <RequireAccess access={screen.access}>{Page ? <Page /> : <ScreenPage screen={screen} />}</RequireAccess>
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
