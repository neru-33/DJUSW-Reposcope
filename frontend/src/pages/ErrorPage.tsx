import { Button, Stack, Text, Title } from '@mantine/core'
import { Link, useNavigate } from 'react-router'
import { homePath } from '../auth/access'
import { useContext } from 'react'
import { AuthContext } from '../auth/context'

const contents = {
  not_found: {
    title: '찾을 수 없는 화면입니다',
    message: '주소가 바뀌었거나 없는 화면입니다. 주소를 확인하세요.',
  },
  forbidden: {
    title: '볼 수 있는 권한이 없습니다',
    message: '이 화면은 권한이 있는 사용자만 볼 수 있습니다.',
  },
  unknown: {
    title: '화면을 표시하지 못했습니다',
    message: '잠시 뒤 다시 시도하세요. 계속되면 운영자에게 알려 주세요.',
  },
}

/**
 * V-18 오류. 권한 없음은 이전 화면으로, 나머지는 첫 화면으로 이동할 수 있다.
 * 화면 틀 밖의 오류에도 쓰므로 로그인 정보가 없어도 동작한다.
 */
export function ErrorPage({ kind }: { kind: keyof typeof contents }) {
  const auth = useContext(AuthContext)
  const navigate = useNavigate()
  const content = contents[kind]
  // 주소를 바로 열어 앱 안에 이전 화면이 없으면 첫 화면으로 보낸다.
  const canGoBack = ((window.history.state as { idx?: number } | null)?.idx ?? 0) > 0

  return (
    <Stack gap="md" align="flex-start" maw={560} py={48}>
      <Title order={1}>{content.title}</Title>
      <Text c="dimmed" size="lg">
        {content.message}
      </Text>
      {kind === 'forbidden' && canGoBack ? (
        <Button variant="default" onClick={() => navigate(-1)}>
          이전 화면으로
        </Button>
      ) : (
        <Button component={Link} to={homePath(auth?.state.me ?? null)}>
          첫 화면으로
        </Button>
      )}
    </Stack>
  )
}
