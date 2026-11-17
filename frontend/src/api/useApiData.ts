import { useCallback, useEffect, useRef, useState } from 'react'

export type ApiData<T> =
  | { status: 'loading'; data: null; error: null }
  | { status: 'ready'; data: T; error: null }
  | { status: 'error'; data: null; error: unknown }

type Settled<T> = { id: string } & ({ status: 'ready'; data: T } | { status: 'error'; error: unknown })

/**
 * 화면이 열릴 때 API를 불러온다. `key`가 바뀌면 다시 불러오고, 이전 요청은 중단한다.
 * `load`는 받은 `signal`을 `request` 옵션에 넘긴다.
 */
export function useApiData<T>(load: (signal: AbortSignal) => Promise<T>, key: string) {
  const [attempt, setAttempt] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  const loadRef = useRef(load)
  const id = `${key}#${attempt}`

  useEffect(() => {
    loadRef.current = load
  })

  useEffect(() => {
    const controller = new AbortController()
    loadRef.current(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSettled({ id, status: 'ready', data })
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setSettled({ id, status: 'error', error })
      },
    )
    return () => controller.abort()
  }, [id])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])

  // 지금 요청의 결과가 아직 없으면 불러오는 중이다.
  let state: ApiData<T> = { status: 'loading', data: null, error: null }
  if (settled?.id === id) {
    state =
      settled.status === 'ready'
        ? { status: 'ready', data: settled.data, error: null }
        : { status: 'error', data: null, error: settled.error }
  }
  return { ...state, reload }
}
