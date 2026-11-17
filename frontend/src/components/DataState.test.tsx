import { MantineProvider } from '@mantine/core'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { useApiData } from '../api/useApiData'
import { theme } from '../theme'
import { DataState } from './DataState'

function Probe({ load }: { load: () => Promise<string> }) {
  const data = useApiData(load, 'probe')
  return (
    <DataState data={data} onRetry={data.reload}>
      {(value) => <p>값: {value}</p>}
    </DataState>
  )
}

function renderProbe(load: () => Promise<string>) {
  render(
    <MantineProvider theme={theme}>
      <MemoryRouter>
        <Probe load={load} />
      </MemoryRouter>
    </MantineProvider>,
  )
}

describe('DataState', () => {
  it('불러온 값을 보여 준다', async () => {
    renderProbe(async () => 'RepoScope')

    expect(screen.getByLabelText('불러오는 중')).toBeTruthy()
    expect(await screen.findByText('값: RepoScope')).toBeTruthy()
  })

  it('403은 권한 없음 화면을 보여 준다', async () => {
    renderProbe(async () => {
      throw new ApiError(403, { error: 'forbidden', message: '권한이 없습니다.' })
    })

    expect(await screen.findByRole('heading', { name: '볼 수 있는 권한이 없습니다' })).toBeTruthy()
  })

  it('서버 오류는 다시 시도할 수 있다', async () => {
    const load = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new ApiError(500, { error: 'internal_error' }))
      .mockResolvedValueOnce('다시 받은 값')
    renderProbe(load)

    await userEvent.click(await screen.findByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('값: 다시 받은 값')).toBeTruthy()
  })
})
