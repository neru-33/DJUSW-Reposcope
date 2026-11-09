function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '')
}

export const config = {
  /** API 주소. 비어 있으면 화면과 같은 주소로 요청한다. */
  apiBaseUrl: trimTrailingSlash(import.meta.env.VITE_API_BASE_URL ?? ''),
  /** 라우터 기본 경로. Vite의 base 설정에서 온다. */
  routerBasename: trimTrailingSlash(import.meta.env.BASE_URL) || '/',
}
