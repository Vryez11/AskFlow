import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import ConnectionStatus from './ConnectionStatus.tsx'
import type { ConnectionStatusResponse } from './types.ts'

const connected: ConnectionStatusResponse = {
  state: 'CONNECTED',
  tableCount: 9,
  termCount: 0,
  writePrivileges: [],
  warnings: [],
  failure: null,
  checkedAt: '2026-10-28T09:00:00Z',
}

function stubFetch(response: () => Promise<Response>) {
  const fetchMock = vi.fn(response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function respondWith(body: ConnectionStatusResponse) {
  return stubFetch(() => Promise.resolve(Response.json(body)))
}

// 글이 여러 요소로 나뉘어도 보이는 글 전체에서 찾는다
async function expectVisibleText(container: HTMLElement, pattern: RegExp) {
  await waitFor(() => expect(container.textContent).toMatch(pattern))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('홈의 연결 상태 표시 (F-003)', () => {
  it('F-003 연결됨: 연결됨, 테이블 9개, 용어 0개가 보인다', async () => {
    const fetchMock = respondWith(connected)

    const { container } = render(<ConnectionStatus />)

    await expectVisibleText(container, /연결됨/)
    expect(container.textContent).toMatch(/테이블\s*9\s*개/)
    expect(container.textContent).toMatch(/용어\s*0\s*개/)
    expect(container.textContent).not.toMatch(/연결 실패/)
    expect(fetchMock).toHaveBeenCalledWith('/api/connection/status', expect.anything())
  })

  it('F-003 쓰기 권한 경고: 쓰기 권한 경고와 INSERT, UPDATE가 보인다', async () => {
    respondWith({ ...connected, writePrivileges: ['INSERT', 'UPDATE'] })

    const { container } = render(<ConnectionStatus />)

    await expectVisibleText(container, /쓰기 권한/)
    expect(container.textContent).toMatch(/INSERT/)
    expect(container.textContent).toMatch(/UPDATE/)
    expect(container.textContent).toMatch(/연결됨/)
  })

  it('F-003 쓰기 권한 경고: 쓰기 권한이 없으면 경고가 없다', async () => {
    respondWith(connected)

    const { container } = render(<ConnectionStatus />)

    await expectVisibleText(container, /연결됨/)
    expect(container.textContent).not.toMatch(/쓰기 권한/)
  })

  it('F-003 연결 실패: 연결 실패, 원인, 확인할 설정 키가 보인다', async () => {
    respondWith({
      ...connected,
      state: 'FAILED',
      tableCount: 0,
      failure: {
        reason: 'AUTHENTICATION',
        message: '계정 이름이나 비밀번호가 맞지 않습니다',
        checkKeys: ['askflow.target-db.username', 'ASKFLOW_READER_PASSWORD'],
      },
    })

    const { container } = render(<ConnectionStatus />)

    await expectVisibleText(container, /연결 실패/)
    expect(container.textContent).toMatch(/계정 이름이나 비밀번호가 맞지 않습니다/)
    expect(container.textContent).toMatch(/askflow\.target-db\.username/)
    expect(container.textContent).toMatch(/ASKFLOW_READER_PASSWORD/)
    expect(container.textContent).not.toMatch(/연결됨/)
  })

  it('F-003 상태 API에 닿지 않음: 네트워크 오류면 불러오지 못했다는 안내가 보이고 홈은 그대로다', async () => {
    stubFetch(() => Promise.reject(new TypeError('Failed to fetch')))

    const { container } = render(<App />)

    await expectVisibleText(container, /연결 상태를 불러오지 못했/)
    expect(screen.getByRole('heading', { name: 'AskFlow' })).toBeTruthy()
    expect(container.textContent).not.toMatch(/연결됨/)
  })

  it('F-003 상태 API에 닿지 않음: 오류 응답이면 불러오지 못했다는 안내가 보이고 홈은 그대로다', async () => {
    stubFetch(() => Promise.resolve(new Response('Bad Gateway', { status: 502 })))

    const { container } = render(<App />)

    await expectVisibleText(container, /연결 상태를 불러오지 못했/)
    expect(screen.getByRole('heading', { name: 'AskFlow' })).toBeTruthy()
    expect(container.textContent).not.toMatch(/연결됨/)
  })
})
