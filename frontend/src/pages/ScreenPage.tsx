import { Link, generatePath, useParams } from 'react-router'
import { screens, type Screen } from '../screens'

const SAMPLE_PROJECT_ID = '1'

function samplePath(path: string): string {
  return path.includes(':id') ? generatePath(path, { id: SAMPLE_PROJECT_ID }) : path
}

function ScreenIndex() {
  return (
    <nav aria-label="화면 목록">
      <ul>
        {screens.map((screen) => (
          <li key={screen.code}>
            <Link to={samplePath(screen.path)}>
              {screen.code} {screen.title}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}

/** 화면을 구현하기 전까지 주소 이동을 확인하는 임시 화면 */
export function ScreenPage({ screen }: { screen: Screen }) {
  const params = useParams()

  return (
    <main>
      <h1>
        {screen.code} {screen.title}
      </h1>
      <p>
        주소: <code>{screen.path}</code>
        {params.id ? ` (프로젝트 ${params.id})` : null}
      </p>
      <ScreenIndex />
    </main>
  )
}

/** V-18 오류 화면 중 없는 주소 */
export function NotFoundPage() {
  return (
    <main>
      <h1>V-18 찾을 수 없는 화면</h1>
      <p>주소를 확인하세요.</p>
      <Link to="/">첫 화면으로</Link>
    </main>
  )
}
