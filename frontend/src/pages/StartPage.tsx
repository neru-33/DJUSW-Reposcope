import { List, Stack, Text, Title } from '@mantine/core'
import { useSearchParams } from 'react-router'
import { safeNext } from '../auth/access'
import { githubLoginUrl } from '../auth/login'

/** 프로젝트에 연결한 저장소에서 저장하는 것과 저장하지 않는 것. 기준: docs/feature-spec.md 3장, R-7 */
const COLLECTED = [
  '커밋, PR, 리뷰, 이슈의 종류와 제목(커밋 메시지 첫 줄, PR·이슈 제목)',
  '작성자 GitHub 계정, 저장소 이름, 시각, GitHub 링크',
  '대표 저장소의 README',
]

const NOT_STORED = ['코드와 변경 내용(diff)', '커밋 작성자 이메일과 커밋 본문', 'PR·이슈 본문', 'GitHub 토큰']

/** V-1 시작. 기준: docs/screen-flow.md 5장, docs/ui-guide.md 6장 안내 */
export function StartPage() {
  const [searchParams] = useSearchParams()
  // 로그인이 필요한 화면에서 보내졌으면 로그인 뒤 그 화면으로 돌아간다.
  const next = safeNext(searchParams.get('next')) ?? '/explore'

  return (
    <Stack gap="xl" maw={880}>
      <Stack gap="md" align="flex-start">
        <Title order={1}>캡스톤디자인 프로젝트와 GitHub 활동을 한곳에서 봅니다</Title>
        <Text size="lg" c="dimmed">
          프로젝트 기본정보, 팀 구성, 진행 상태, 결과물을 관리하고 연결한 GitHub 저장소의 활동을 함께 보여 줍니다.
          GitHub 계정으로 로그인한 뒤 학번과 이름을 입력하면 시작할 수 있습니다.
        </Text>
        <a className="primary-button" href={githubLoginUrl(next)}>
          GitHub 로그인
        </a>
      </Stack>

      <section className="panel panel-body" aria-labelledby="collect-title">
        <Stack gap="lg">
          <div>
            <Title order={2} id="collect-title">
              수집 범위
            </Title>
            <Text c="dimmed" mt={4}>
              GitHub App을 설치해 프로젝트에 연결한 저장소의 활동만 수집합니다.
            </Text>
          </div>
          <div className="start-columns">
            <div>
              <Title order={3}>저장하는 것</Title>
              <List spacing={6} mt="xs">
                {COLLECTED.map((item) => (
                  <List.Item key={item}>{item}</List.Item>
                ))}
              </List>
            </div>
            <div>
              <Title order={3}>저장하지 않는 것</Title>
              <List spacing={6} mt="xs">
                {NOT_STORED.map((item) => (
                  <List.Item key={item}>{item}</List.Item>
                ))}
              </List>
            </div>
          </div>
        </Stack>
      </section>

      <section className="panel panel-body" aria-labelledby="reference-title">
        <Title order={2} id="reference-title">
          참고자료 안내
        </Title>
        <Text mt={4}>
          수집한 활동 기록은 평가가 아니라 팀의 진행을 돌아보기 위한 참고자료입니다.
        </Text>
      </section>
    </Stack>
  )
}
