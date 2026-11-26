export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'PROFESSOR' | 'STUDENT' | 'GUEST'

export interface MeUser {
  id: number
  github_id: number | null
  login: string
  role: Role
  name: string | null
}

/** GET /api/me 응답 */
export interface Me {
  user: MeUser
  auth: 'github' | 'operator'
  csrf_token: string
  consent_required: boolean
  profile_required: boolean
}

/** 목록 API 공통 응답 */
export interface ListResponse<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** 오류 응답 */
export interface ApiErrorBody {
  error: string
  message?: string
  fields?: Record<string, string>
}

/** 운영자 계정 단계. 기준: docs/feature-spec.md R-2 */
export type OperatorStage = 'PASSWORD_CHANGE' | 'OTP_SETUP' | 'OTP' | 'ACTIVE'

/** 운영자 로그인·설정 요청의 응답. 다음 단계와 그 세션의 CSRF 토큰이다. */
export interface OperatorStageResponse {
  stage: OperatorStage
  csrf_token: string
}

/** GET /auth/admin/otp/setup 응답 */
export interface OtpSetup {
  secret: string
  otpauth_uri: string
}
