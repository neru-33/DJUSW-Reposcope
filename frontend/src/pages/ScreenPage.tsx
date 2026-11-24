import { Anchor, List, Stack, Text, Title } from '@mantine/core'
import { Link, generatePath, useParams } from 'react-router'
import { screens, type Screen } from '../screens'

const SAMPLE_PROJECT_ID = '1'

function samplePath(path: string): string {
  return path.includes(':id') ? generatePath(path, { id: SAMPLE_PROJECT_ID }) : path
}

/** 화면을 구현하기 전까지 주소 이동을 확인하는 임시 화면 */
export function ScreenPage({ screen }: { screen: Screen }) {
  const params = useParams()

  return (
    <Stack gap="lg">
      <div>
        <Title order={1}>
          {screen.code} {screen.title}
        </Title>
        <Text c="dimmed" mt={6}>
          아직 만들지 않은 화면입니다. 주소 <code>{screen.path}</code>
          {params.id ? `, 프로젝트 ${params.id}` : null}
        </Text>
      </div>
      {import.meta.env.DEV && (
        <nav aria-label="화면 목록" className="panel panel-body">
          <List spacing={4} size="sm">
            {screens.map((s) => (
              <List.Item key={s.code}>
                <Anchor component={Link} to={samplePath(s.path)}>
                  {s.code} {s.title}
                </Anchor>
              </List.Item>
            ))}
          </List>
        </nav>
      )}
    </Stack>
  )
}
