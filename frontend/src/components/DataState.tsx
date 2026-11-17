import type { ReactNode } from 'react'
import { ApiError } from '../api/client'
import type { ApiData } from '../api/useApiData'
import { ErrorPage } from '../pages/ErrorPage'
import { ErrorState, PageLoader } from './states'

/**
 * `useApiData` 결과에 맞춰 불러오는 중, 오류, 내용을 표시한다.
 * 403은 권한 없음, 404는 없는 화면으로 V-18을 표시한다.
 */
export function DataState<T>({
  data,
  onRetry,
  children,
}: {
  data: ApiData<T>
  onRetry: () => void
  children: (value: T) => ReactNode
}) {
  if (data.status === 'loading') return <PageLoader />
  if (data.status === 'error') {
    if (data.error instanceof ApiError && data.error.status === 403) return <ErrorPage kind="forbidden" />
    if (data.error instanceof ApiError && data.error.status === 404) return <ErrorPage kind="not_found" />
    return <ErrorState error={data.error} onRetry={onRetry} />
  }
  return <>{children(data.data)}</>
}
