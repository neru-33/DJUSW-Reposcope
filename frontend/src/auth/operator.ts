import { ApiError, setCsrfToken } from '../api/client'
import type { OperatorStageResponse } from '../api/types'
import { errorMessage } from '../components/errorMessage'

/** 인증 앱의 OTP. 6자리 숫자다. */
export const OTP_CODE = /^[0-9]{6}$/

/** 로그인·설정 응답의 CSRF 토큰을 이후 쓰기 요청에 쓰고, 다음 단계를 돌려준다. */
export function applyStage(response: OperatorStageResponse): OperatorStageResponse['stage'] {
  setCsrfToken(response.csrf_token)
  return response.stage
}

/** 운영자 로그인·설정 실패를 한 문장으로 바꾼다. 다음에 할 일이 있는 오류는 그 내용을 붙인다. */
export function operatorErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'temp_password_expired') {
      return '임시 비밀번호가 만료되었습니다. 최고관리자에게 새 임시 비밀번호를 요청하세요.'
    }
    if (error.code === 'too_many_attempts') return '로그인 시도가 너무 많습니다. 15분 뒤 다시 시도하세요.'
    if (error.code === 'locked') return '계정이 잠겨 있습니다. 잠시 뒤 다시 시도하세요.'
    if (error.code === 'unauthorized') return '로그인한 지 오래되었습니다. 처음부터 다시 로그인하세요.'
  }
  return errorMessage(error)
}
