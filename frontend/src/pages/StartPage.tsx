import { Text, Title } from '@mantine/core'
import { useSearchParams } from 'react-router'
import { safeNext } from '../auth/access'
import { githubLoginUrl } from '../auth/login'
import { GithubButton } from '../components/GithubButton'

/** 연결한 저장소에서 가져오는 것과 가져오지 않는 것. 기준: docs/feature-spec.md 3장, R-7 */
const COLLECTED = ['커밋·PR·리뷰·이슈의 제목', '작성자, 시각, GitHub 링크', '대표 저장소의 README']
const NOT_COLLECTED = ['코드와 변경 내용(diff)', '커밋 본문, 작성자 이메일', 'PR·이슈 본문', 'GitHub 토큰']

function CheckIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <path d="M3 8.5l3 3 7-7" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function CrossIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

/** V-1 시작. 기준: docs/screen-flow.md 5장, docs/ui-guide.md 6장 안내 */
export function StartPage() {
  const [searchParams] = useSearchParams()
  // 로그인이 필요한 화면에서 보내졌으면 로그인 뒤 그 화면으로 돌아간다.
  const next = safeNext(searchParams.get('next')) ?? '/explore'

  return (
    <div className="start">
      <section className="start-hero">
        <Title order={1}>캡스톤디자인 프로젝트를 한곳에서</Title>
        <Text c="dimmed" size="lg">
          진행 상태와 결과물, 저장소 활동을 팀과 함께 봅니다.
        </Text>
        <GithubButton href={githubLoginUrl(next)} />
      </section>

      <section className="start-scope" aria-labelledby="scope-title">
        <Title order={2} id="scope-title" className="start-scope-title">
          연결한 저장소에서 가져오는 정보
        </Title>
        <div className="start-scope-grid">
          <div className="panel panel-body">
            <Title order={3}>가져오는 것</Title>
            <ul className="scope-list scope-list-in">
              {COLLECTED.map((item) => (
                <li key={item}>
                  <CheckIcon />
                  {item}
                </li>
              ))}
            </ul>
          </div>
          <div className="panel panel-body">
            <Title order={3}>가져오지 않는 것</Title>
            <ul className="scope-list scope-list-out">
              {NOT_COLLECTED.map((item) => (
                <li key={item}>
                  <CrossIcon />
                  {item}
                </li>
              ))}
            </ul>
          </div>
        </div>
        <Text size="sm" c="dimmed" ta="center">
          활동 기록은 평가 자료가 아닌 참고용입니다.
        </Text>
      </section>
    </div>
  )
}
