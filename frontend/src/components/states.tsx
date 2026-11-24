import { Button, Group, Modal, Skeleton, Stack, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'
import { errorMessage } from './errorMessage'

/** 불러오는 중: 화면 틀을 유지하고 내용 자리에 표시한다. */
export function PageLoader({ lines = 3 }: { lines?: number }) {
  return (
    <Stack gap="md" aria-busy="true" aria-label="불러오는 중">
      <Skeleton height={28} width="40%" />
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} height={72} />
      ))}
    </Stack>
  )
}

/** 빈 상태: 한 문장 안내와 다음 행동 버튼 */
export function EmptyState({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="panel panel-body">
      <Stack gap="md" align="flex-start">
        <Text c="dimmed">{message}</Text>
        {action}
      </Stack>
    </div>
  )
}

/** 서버 오류: 원인 한 문장과 다시 시도 버튼 */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="panel panel-body" role="alert">
      <Stack gap="md" align="flex-start">
        <Text>{errorMessage(error)}</Text>
        {onRetry && (
          <Button variant="default" onClick={onRetry}>
            다시 시도
          </Button>
        )}
      </Stack>
    </div>
  )
}

export interface ConfirmDialogProps {
  opened: boolean
  title: string
  /** 동작을 실행하면 어떻게 되는지 설명한다. */
  children: ReactNode
  /** 실행 버튼 이름. 동작 이름을 쓴다. 예: `팀원 제외` */
  confirmLabel: string
  loading?: boolean
  onConfirm: () => void
  onClose: () => void
}

/** 위험한 동작 전에 띄우는 확인 대화상자 */
export function ConfirmDialog({ opened, title, children, confirmLabel, loading, onConfirm, onClose }: ConfirmDialogProps) {
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={<Title order={3}>{title}</Title>}
      centered
      radius="lg"
      shadow="md"
    >
      <Stack gap="lg">
        <div>{children}</div>
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onClose} disabled={loading}>
            취소
          </Button>
          <Button color="danger" onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  )
}
