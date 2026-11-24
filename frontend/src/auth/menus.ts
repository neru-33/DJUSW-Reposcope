import type { Me } from '../api/types'
import { needsSignup } from './access'

export interface MenuItem {
  label: string
  path: string
}

/** 화면 흐름 3장의 직위별 상단 메뉴. 로그아웃은 상단바 오른쪽에 따로 둔다. */
export function menusFor(me: Me | null): MenuItem[] {
  if (!me || needsSignup(me)) return []
  switch (me.user.role) {
    case 'STUDENT':
      return [
        { label: '둘러보기', path: '/explore' },
        { label: '내 프로젝트', path: '/projects' },
      ]
    case 'PROFESSOR':
      return [{ label: '전체 프로젝트', path: '/admin/projects' }]
    case 'ADMIN':
      return [
        { label: '전체 프로젝트', path: '/admin/projects' },
        { label: '사용자 관리', path: '/admin/users' },
      ]
    case 'SUPER_ADMIN':
      return [
        { label: '전체 프로젝트', path: '/admin/projects' },
        { label: '사용자 관리', path: '/admin/users' },
      ]
    default:
      return []
  }
}
