import { ApiError } from '../api/client'

/** 오류를 사용자에게 보여 줄 한 문장으로 바꾼다. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'network_error') return '서버에 연결하지 못했습니다. 네트워크 상태를 확인하고 다시 시도하세요.'
    if (error.status >= 500) return '서버에서 요청을 처리하지 못했습니다. 잠시 뒤 다시 시도하세요.'
    return error.message
  }
  return '화면을 표시하지 못했습니다. 다시 시도하세요.'
}
