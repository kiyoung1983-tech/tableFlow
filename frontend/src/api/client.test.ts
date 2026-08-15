import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, configureAccessTokenProvider } from './client'

afterEach(() => {
  configureAccessTokenProvider()
  vi.unstubAllGlobals()
})

describe('api client', () => {
  it('returns JSON and sends the configured bearer token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetchMock)
    configureAccessTokenProvider(() => 'access-token')

    await expect(api<{ ok: boolean }>('/api/test')).resolves.toEqual({ ok: true })
    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers)
    expect(headers.get('Authorization')).toBe('Bearer access-token')
    expect(headers.get('X-Request-Id')).toBeTruthy()
  })

  it('converts problem responses to ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      title: 'Not Found', status: 404, detail: '없음', code: 'NOT_FOUND', traceId: 'trace-1',
    }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } })))

    await expect(api('/api/missing')).rejects.toMatchObject({
      status: 404,
      traceId: 'trace-1',
    } satisfies Partial<ApiError>)
  })
})
