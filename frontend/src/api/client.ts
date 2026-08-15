import type { ProblemDetail } from './types'

const REQUEST_ID_HEADER = 'X-Request-Id'
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

type AccessTokenProvider = () => Promise<string | undefined> | string | undefined
let accessTokenProvider: AccessTokenProvider = () => undefined

type ApiRequestOptions = Omit<RequestInit, 'body'> & {
  body?: BodyInit | null
  json?: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail
  readonly traceId?: string

  constructor(status: number, problem: ProblemDetail, traceId?: string) {
    super(problem.detail)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.traceId = traceId ?? problem.traceId
  }
}

export function configureAccessTokenProvider(provider?: AccessTokenProvider) {
  accessTokenProvider = provider ?? (() => undefined)
}

export async function api<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { json, headers, body, ...requestInit } = options
  if (json !== undefined && body !== undefined) {
    throw new Error('API 요청에는 body와 json 중 하나만 지정할 수 있습니다.')
  }

  const requestHeaders = new Headers(headers)
  if (json !== undefined && !requestHeaders.has('Content-Type')) {
    requestHeaders.set('Content-Type', 'application/json')
  }
  if (!requestHeaders.has('Authorization')) {
    const accessToken = await accessTokenProvider()
    if (accessToken) {
      requestHeaders.set('Authorization', `Bearer ${accessToken}`)
    }
  }
  if (!requestHeaders.has(REQUEST_ID_HEADER) && globalThis.crypto?.randomUUID) {
    requestHeaders.set(REQUEST_ID_HEADER, globalThis.crypto.randomUUID())
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...requestInit,
    headers: requestHeaders,
    body: json === undefined ? body : JSON.stringify(json),
  })
  const traceId = response.headers.get(REQUEST_ID_HEADER) ?? undefined

  if (!response.ok) {
    throw new ApiError(response.status, await readProblem(response), traceId)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

async function readProblem(response: Response): Promise<ProblemDetail> {
  const contentType = response.headers.get('Content-Type') ?? ''
  if (contentType.includes('json')) {
    try {
      return await response.json() as ProblemDetail
    } catch {
      // 아래의 안전한 기본 오류를 사용한다.
    }
  }
  return {
    title: 'Request failed',
    status: response.status,
    detail: `요청이 실패했습니다. HTTP ${response.status}`,
    code: 'HTTP_ERROR',
  }
}
