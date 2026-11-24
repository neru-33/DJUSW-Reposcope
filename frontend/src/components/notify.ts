import { notifications } from '@mantine/notifications'

/** 저장 성공처럼 짧은 결과를 화면 아래에 3초 동안 표시한다. */
export function notifySuccess(message: string): void {
  notifications.show({ message, color: 'done', autoClose: 3000 })
}

/** 화면 안에 표시할 곳이 없는 실패를 알린다. */
export function notifyError(message: string): void {
  notifications.show({ message, color: 'danger', autoClose: 5000 })
}
