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
