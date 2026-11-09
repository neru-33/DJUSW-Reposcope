/** 화면 흐름 문서(docs/screen-flow.md)의 화면 목록. 주소는 기본 경로를 뺀 값이다. */
export interface Screen {
  code: string
  title: string
  path: string
}

export const screens: Screen[] = [
  { code: 'V-1', title: '시작', path: '/' },
  { code: 'V-2', title: '가입', path: '/signup' },
  { code: 'V-3', title: '내 프로젝트', path: '/projects' },
  { code: 'V-4', title: '프로젝트 등록', path: '/projects/new' },
  { code: 'V-5', title: '초대 코드 입력', path: '/join' },
  { code: 'V-6', title: '프로젝트 개요', path: '/projects/:id' },
  { code: 'V-7', title: '활동', path: '/projects/:id/activity' },
  { code: 'V-8', title: '통계', path: '/projects/:id/stats' },
  { code: 'V-9', title: '팀원', path: '/projects/:id/members' },
  { code: 'V-10', title: '결과물', path: '/projects/:id/deliverables' },
  { code: 'V-11', title: '저장소', path: '/projects/:id/repos' },
  { code: 'V-12', title: '프로젝트 설정', path: '/projects/:id/settings' },
  { code: 'V-13', title: '운영자 로그인', path: '/admin/login' },
  { code: 'V-14', title: '운영자 초기 설정', path: '/admin/setup' },
  { code: 'V-15', title: '전체 프로젝트', path: '/admin/projects' },
  { code: 'V-16', title: '사용자 관리', path: '/admin/users' },
  { code: 'V-17', title: '운영자 계정', path: '/admin/operators' },
]
