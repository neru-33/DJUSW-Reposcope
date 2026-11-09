import { createBrowserRouter, type RouteObject } from 'react-router'
import { config } from './config'
import { NotFoundPage, ScreenPage } from './pages/ScreenPage'
import { screens } from './screens'

const screenRoutes: RouteObject[] = screens.map((screen) =>
  screen.path === '/'
    ? { index: true, element: <ScreenPage screen={screen} /> }
    : { path: screen.path.slice(1), element: <ScreenPage screen={screen} /> },
)

export const router = createBrowserRouter(
  [
    {
      path: '/',
      children: [...screenRoutes, { path: '*', element: <NotFoundPage /> }],
    },
  ],
  { basename: config.routerBasename },
)
