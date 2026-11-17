import type { Me, Role } from '../api/types'
import type { Access } from '../screens'

const OPERATOR_ROLES: Role[] = ['SUPER_ADMIN', 'ADMIN', 'PROFESSOR']

export const roleLabels: Record<Role, string> = {
  SUPER_ADMIN: '최고관리자',
  ADMIN: '관리자',
  PROFESSOR: '교수',
  STUDENT: '학생',
  GUEST: '가입 전',
}

/** 가입을 끝내지 않은 사용자인지 */
export function needsSignup(me: Me): boolean {
  return me.user.role === 'GUEST' || me.profile_required || me.consent_required
}

export function isOperator(me: Me): boolean {
  return OPERATOR_ROLES.includes(me.user.role)
}

/** 화면 흐름 2장: 로그인 상태별 첫 화면 */
export function homePath(me: Me | null): string {
  if (!me) return '/'
  if (needsSignup(me)) return '/signup'
  if (me.user.role === 'STUDENT') return '/explore'
  return '/admin/projects'
}

/**
 * 로그인이 필요할 때 보낼 로그인 화면. 운영자는 V-13, 학생은 V-1이다.
 * 운영자인지 모르면 운영자 화면 주소인지로 판단한다.
 */
export function loginPath(target: string, operator = target.startsWith('/admin')): string {
  return `${operator ? '/admin/login' : '/'}?next=${encodeURIComponent(target)}`
}

/** 로그인 뒤 돌아갈 주소. 같은 사이트의 상대 경로만 허용한다. */
export function safeNext(next: string | null): string | null {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return null
  return next
}

export type AccessResult =
  | { kind: 'allow' }
  | { kind: 'redirect'; to: string }
  | { kind: 'forbidden' }

/** 화면 접근 규칙을 판정한다. `target`은 현재 주소(경로와 조회 조건)다. */
export function checkAccess(access: Access, me: Me | null, target: string): AccessResult {
  if (access === 'public') return { kind: 'allow' }

  if (access === 'anonymous') {
    if (!me) return { kind: 'allow' }
    // 로그인 화면에 돌아갈 주소가 있으면 그곳으로 보낸다. 가입 전이면 가입부터 한다.
    const next = needsSignup(me) ? null : safeNext(new URLSearchParams(target.split('?')[1] ?? '').get('next'))
    return { kind: 'redirect', to: next ?? homePath(me) }
  }

  if (!me) return { kind: 'redirect', to: loginPath(target) }

  if (access === 'guest') {
    return needsSignup(me) ? { kind: 'allow' } : { kind: 'redirect', to: homePath(me) }
  }

  if (needsSignup(me)) return { kind: 'redirect', to: '/signup' }

  if (access === 'student') {
    return me.user.role === 'STUDENT' ? { kind: 'allow' } : { kind: 'forbidden' }
  }
  if (access === 'project') {
    return me.user.role === 'STUDENT' || isOperator(me) ? { kind: 'allow' } : { kind: 'forbidden' }
  }
  return access.includes(me.user.role) ? { kind: 'allow' } : { kind: 'forbidden' }
}
