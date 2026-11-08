import { describe, expect, it } from 'vitest'
import { screens } from './screens'

describe('screens', () => {
  it('모든 주소가 /로 시작한다', () => {
    for (const screen of screens) {
      expect(screen.path, screen.code).toMatch(/^\//)
    }
  })
})
