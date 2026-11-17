import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Mantine이 쓰는 브라우저 기능 중 jsdom에 없는 것을 채운다.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverStub

afterEach(() => {
  cleanup()
})
