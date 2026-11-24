import type { Role } from './api/types'

/**
 * 화면에 들어갈 수 있는 사용자.
 * - `anonymous`: 로그인하지 않은 사용자만. 로그인한 사용자는 첫 화면으로 보낸다.
 * - `public`: 누구나
 * - `guest`: 가입을 끝내지 않은 사용자
 * - `student`: 가입을 끝낸 학생
 * - `project`: 학생과 운영자. 프로젝트별 권한은 API가 검사한다.
 * - 직위 목록: 그 직위의 운영자
 */
export type Access = 'anonymous' | 'public' | 'guest' | 'student' | 'project' | Role[]

/** 화면 흐름 문서(docs/screen-flow.md)의 화면 목록. 주소는 기본 경로를 뺀 값이다. */
export interface Screen {
  code: string
  title: string
  path: string
  access: Access
}

const OPERATORS: Role[] = ['SUPER_ADMIN', 'ADMIN', 'PROFESSOR']

export const screens: Screen[] = [
  { code: 'V-1', title: '시작', path: '/', access: 'anonymous' },
  { code: 'V-2', title: '가입', path: '/signup', access: 'guest' },
  { code: 'V-3', title: '내 프로젝트', path: '/projects', access: 'student' },
  { code: 'V-4', title: '프로젝트 등록', path: '/projects/new', access: 'student' },
  { code: 'V-5', title: '초대 코드 입력', path: '/join', access: 'student' },
  { code: 'V-6', title: '프로젝트 개요', path: '/projects/:id', access: 'project' },
  { code: 'V-7', title: '활동', path: '/projects/:id/activity', access: 'project' },
  { code: 'V-8', title: '전체 활동', path: '/projects/:id/activity/all', access: 'project' },
  { code: 'V-9', title: '팀원', path: '/projects/:id/members', access: 'project' },
  { code: 'V-10', title: '결과물', path: '/projects/:id/deliverables', access: 'project' },
  { code: 'V-11', title: '저장소', path: '/projects/:id/repos', access: 'project' },
  { code: 'V-12', title: '프로젝트 설정', path: '/projects/:id/settings', access: 'project' },
  { code: 'V-13', title: '운영자 로그인', path: '/admin/login', access: 'public' },
  { code: 'V-14', title: '운영자 초기 설정', path: '/admin/setup', access: 'public' },
  { code: 'V-15', title: '전체 프로젝트', path: '/admin/projects', access: OPERATORS },
  { code: 'V-16', title: '사용자 관리', path: '/admin/users', access: ['SUPER_ADMIN', 'ADMIN'] },
  { code: 'V-17', title: '운영자 계정', path: '/admin/operators', access: ['SUPER_ADMIN'] },
  { code: 'V-19', title: '둘러보기', path: '/explore', access: 'student' },
]

export function screenByCode(code: string): Screen {
  const screen = screens.find((s) => s.code === code)
  if (!screen) throw new Error(`없는 화면 번호: ${code}`)
  return screen
}
