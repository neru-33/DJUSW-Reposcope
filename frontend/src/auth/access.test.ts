import { describe, expect, it } from 'vitest'
import type { Me, Role } from '../api/types'
import { checkAccess, homePath, loginPath, safeNext } from './access'
import { menusFor } from './menus'

function me(role: Role, overrides: Partial<Me> = {}): Me {
  return {
    user: { id: 1, github_id: role === 'STUDENT' || role === 'GUEST' ? 100 : null, login: 'user', role, name: '홍길동' },
    auth: role === 'STUDENT' || role === 'GUEST' ? 'github' : 'operator',
    csrf_token: 'token',
    consent_required: false,
    profile_required: false,
    ...overrides,
  }
}

describe('homePath', () => {
  it('화면 흐름 2장의 첫 화면으로 보낸다', () => {
    expect(homePath(null)).toBe('/')
    expect(homePath(me('GUEST'))).toBe('/signup')
    expect(homePath(me('STUDENT', { profile_required: true }))).toBe('/signup')
    expect(homePath(me('STUDENT'))).toBe('/explore')
    expect(homePath(me('PROFESSOR'))).toBe('/admin/projects')
    expect(homePath(me('SUPER_ADMIN'))).toBe('/admin/projects')
  })

  it('운영자는 개인정보 동의 값과 관계없이 가입 화면으로 보내지 않는다', () => {
    expect(homePath(me('SUPER_ADMIN', { consent_required: true }))).toBe('/admin/projects')
  })
})

describe('checkAccess', () => {
  it('로그인하지 않은 사용자는 로그인 화면으로 보내고 돌아올 주소를 붙인다', () => {
    expect(checkAccess('student', null, '/projects?page=2')).toEqual({
      kind: 'redirect',
      to: '/?next=%2Fprojects%3Fpage%3D2',
    })
    expect(checkAccess(['SUPER_ADMIN'], null, '/admin/operators')).toEqual({
      kind: 'redirect',
      to: '/admin/login?next=%2Fadmin%2Foperators',
    })
  })

  it('가입을 끝내지 않은 사용자는 가입 화면으로 보낸다', () => {
    expect(checkAccess('project', me('GUEST'), '/projects/1')).toEqual({ kind: 'redirect', to: '/signup' })
    expect(checkAccess('guest', me('GUEST'), '/signup')).toEqual({ kind: 'allow' })
    expect(checkAccess('guest', me('STUDENT'), '/signup')).toEqual({ kind: 'redirect', to: '/explore' })
  })

  it('로그인한 사용자가 시작 화면을 열면 첫 화면으로 보낸다', () => {
    expect(checkAccess('anonymous', me('STUDENT'), '/')).toEqual({ kind: 'redirect', to: '/explore' })
    expect(checkAccess('anonymous', null, '/')).toEqual({ kind: 'allow' })
  })

  it('시작 화면에 돌아갈 주소가 있으면 로그인한 사용자를 그곳으로 보낸다', () => {
    expect(checkAccess('anonymous', me('STUDENT'), '/?next=%2Fprojects%2F3')).toEqual({
      kind: 'redirect',
      to: '/projects/3',
    })
    expect(checkAccess('anonymous', me('GUEST'), '/?next=%2Fprojects')).toEqual({ kind: 'redirect', to: '/signup' })
    expect(checkAccess('anonymous', me('STUDENT'), '/?next=%2F%2Fevil.example')).toEqual({
      kind: 'redirect',
      to: '/explore',
    })
  })

  it('직위가 맞지 않으면 권한 없음이다', () => {
    expect(checkAccess('student', me('PROFESSOR'), '/projects')).toEqual({ kind: 'forbidden' })
    expect(checkAccess(['SUPER_ADMIN', 'ADMIN'], me('PROFESSOR'), '/admin/users')).toEqual({ kind: 'forbidden' })
    expect(checkAccess(['SUPER_ADMIN', 'ADMIN'], me('STUDENT'), '/admin/users')).toEqual({ kind: 'forbidden' })
    expect(checkAccess('project', me('PROFESSOR'), '/projects/1')).toEqual({ kind: 'allow' })
  })
})

describe('loginPath, safeNext', () => {
  it('운영자는 운영자 로그인 화면으로 보낸다', () => {
    expect(loginPath('/projects/1', true)).toBe('/admin/login?next=%2Fprojects%2F1')
  })

  it('다른 사이트로 가는 주소는 받지 않는다', () => {
    expect(safeNext('/projects')).toBe('/projects')
    expect(safeNext('//evil.example')).toBeNull()
    expect(safeNext('/\\evil.example')).toBeNull()
    expect(safeNext('https://evil.example')).toBeNull()
    expect(safeNext(null)).toBeNull()
  })
})

describe('menusFor', () => {
  const labels = (role: Role) => menusFor(me(role)).map((m) => m.label)

  it('화면 흐름 3장의 직위별 메뉴를 준다', () => {
    expect(labels('STUDENT')).toEqual(['둘러보기', '내 프로젝트'])
    expect(labels('PROFESSOR')).toEqual(['전체 프로젝트'])
    expect(labels('ADMIN')).toEqual(['전체 프로젝트', '사용자 관리'])
    expect(labels('SUPER_ADMIN')).toEqual(['전체 프로젝트', '사용자 관리'])
  })

  it('로그인하지 않았거나 가입 전이면 메뉴가 없다', () => {
    expect(menusFor(null)).toEqual([])
    expect(labels('GUEST')).toEqual([])
  })
})
