import { config } from '../config'

/**
 * GitHub 로그인 시작 주소. API 도메인으로 이동하고, 로그인이 끝나면 `next` 화면으로 돌아온다.
 * `next`는 같은 사이트의 상대 경로여야 한다.
 */
export function githubLoginUrl(next: string): string {
  return `${config.apiBaseUrl}/auth/github/login?next=${encodeURIComponent(next)}`
}
