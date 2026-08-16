#!/usr/bin/env node

import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const knownKeys = [
  'POSTGRES_DB',
  'POSTGRES_USER',
  'POSTGRES_PASSWORD',
  'OIDC_ISSUER_URI',
  'OIDC_AUDIENCE',
  'OIDC_ROLES_CLAIM',
  'OIDC_ADMIN_ROLE',
  'OIDC_PRINCIPAL_CLAIM',
  'OIDC_SPA_CLIENT_ID',
  'OIDC_SPA_SCOPE',
  'OIDC_SPA_RESOURCE',
  'OIDC_CSP_CONNECT_SRC',
  'CORS_ALLOWED_ORIGIN',
  'APP_PUBLIC_URL',
  'RESERVATION_ENCRYPTION_KEY',
  'RESERVATION_ENCRYPTION_KEY_ID',
  'RESERVATION_PREVIOUS_ENCRYPTION_KEY',
  'RESERVATION_PREVIOUS_ENCRYPTION_KEY_ID',
  'RESERVATION_HMAC_KEY',
  'RESERVATION_RETENTION_DAYS',
  'RESERVATION_RETENTION_BATCH_SIZE',
  'RESERVATION_RETENTION_CRON',
  'RESERVATION_ENCRYPTION_ROTATION_ENABLED',
  'RESERVATION_ENCRYPTION_ROTATION_BATCH_SIZE',
  'RESERVATION_ENCRYPTION_ROTATION_CRON',
  'PRIVACY_POLICY_VERSION',
  'PUBLIC_CREATE_RATE_LIMIT',
  'PUBLIC_MANAGEMENT_RATE_LIMIT',
  'PUBLIC_RATE_LIMIT_WINDOW_SECONDS',
  'APP_VERSION',
  'APP_PORT',
]

const defaults = {
  POSTGRES_DB: 'app',
  POSTGRES_USER: 'app',
  OIDC_ROLES_CLAIM: 'roles',
  OIDC_ADMIN_ROLE: 'tableflow-admin',
  OIDC_PRINCIPAL_CLAIM: 'sub',
  OIDC_SPA_RESOURCE: '',
  RESERVATION_PREVIOUS_ENCRYPTION_KEY: '',
  RESERVATION_PREVIOUS_ENCRYPTION_KEY_ID: 'previous',
  RESERVATION_RETENTION_DAYS: '365',
  RESERVATION_RETENTION_BATCH_SIZE: '100',
  RESERVATION_RETENTION_CRON: '0 20 3 * * *',
  RESERVATION_ENCRYPTION_ROTATION_ENABLED: 'false',
  RESERVATION_ENCRYPTION_ROTATION_BATCH_SIZE: '100',
  RESERVATION_ENCRYPTION_ROTATION_CRON: '0 40 3 * * *',
  PUBLIC_CREATE_RATE_LIMIT: '10',
  PUBLIC_MANAGEMENT_RATE_LIMIT: '30',
  PUBLIC_RATE_LIMIT_WINDOW_SECONDS: '60',
  APP_PORT: '80',
}

const requiredKeys = [
  'POSTGRES_PASSWORD',
  'OIDC_ISSUER_URI',
  'OIDC_AUDIENCE',
  'OIDC_SPA_CLIENT_ID',
  'OIDC_SPA_SCOPE',
  'OIDC_CSP_CONNECT_SRC',
  'CORS_ALLOWED_ORIGIN',
  'APP_PUBLIC_URL',
  'RESERVATION_ENCRYPTION_KEY',
  'RESERVATION_ENCRYPTION_KEY_ID',
  'RESERVATION_HMAC_KEY',
  'PRIVACY_POLICY_VERSION',
  'APP_VERSION',
]

export async function runDeploymentPreflight({
  envFile = '.env',
  environment = process.env,
  checkOidc = false,
  timeoutMs = 10_000,
  fetchImpl = globalThis.fetch,
} = {}) {
  if (!Number.isInteger(timeoutMs) || timeoutMs < 100 || timeoutMs > 120_000) {
    throw new Error('timeoutMs는 100~120000 사이의 정수여야 합니다.')
  }

  const fileValues = parseEnv(await readFile(envFile, 'utf8'))
  const config = effectiveConfig(fileValues, environment)
  const validation = validateDeploymentConfig(config)
  if (validation.errors.length > 0) {
    throw new PreflightError(validation.errors, validation.warnings)
  }

  let discovery = null
  if (checkOidc) {
    discovery = await checkOidcDiscovery({
      issuer: config.OIDC_ISSUER_URI,
      timeoutMs,
      fetchImpl,
    })
  }

  return {
    checks: [
      '환경 파일 파싱',
      '필수 설정과 placeholder 차단',
      'HTTPS origin과 OIDC/CORS/CSP 일치',
      '예약 암호화 키 분리와 정책 범위',
      ...(checkOidc ? ['OIDC discovery metadata'] : []),
    ],
    warnings: validation.warnings,
    summary: {
      appVersion: config.APP_VERSION,
      publicOrigin: config.APP_PUBLIC_URL,
      issuer: config.OIDC_ISSUER_URI,
      retentionDays: Number(config.RESERVATION_RETENTION_DAYS),
      rotationEnabled: config.RESERVATION_ENCRYPTION_ROTATION_ENABLED === 'true',
      oidcDiscovery: discovery ? 'verified' : 'skipped',
    },
  }
}

export function parseEnv(content) {
  const values = {}
  const lines = content.replace(/^\uFEFF/, '').split(/\r?\n/u)
  for (let index = 0; index < lines.length; index += 1) {
    const original = lines[index]
    const trimmed = original.trim()
    if (trimmed === '' || trimmed.startsWith('#')) continue

    const line = trimmed.startsWith('export ') ? trimmed.slice(7).trimStart() : original.trimStart()
    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/u.exec(line)
    if (!match) throw new Error(`환경 파일 ${index + 1}행 형식이 KEY=VALUE가 아닙니다.`)

    const [, key, rawValue] = match
    if (Object.hasOwn(values, key)) throw new Error(`환경 파일에 ${key}가 중복되었습니다.`)
    values[key] = parseEnvValue(rawValue, index + 1)
  }
  return values
}

export function validateDeploymentConfig(config) {
  const errors = []
  const warnings = []
  const addError = (field, message) => errors.push(`${field}: ${message}`)

  for (const key of requiredKeys) {
    if (!config[key]?.trim()) addError(key, '필수값이 비어 있습니다.')
  }
  if (errors.length > 0) return { errors, warnings }

  for (const key of requiredKeys) {
    if (looksLikePlaceholder(config[key])) addError(key, '예제값 또는 placeholder를 실제 값으로 교체해야 합니다.')
  }

  if (config.POSTGRES_PASSWORD.length < 16) {
    addError('POSTGRES_PASSWORD', '16자 이상의 강한 비밀번호가 필요합니다.')
  }
  if (config.POSTGRES_PASSWORD === config.POSTGRES_USER) {
    addError('POSTGRES_PASSWORD', 'DB 사용자명과 같은 값을 사용할 수 없습니다.')
  }

  const issuer = validateHttpsUrl('OIDC_ISSUER_URI', config.OIDC_ISSUER_URI, { originOnly: false }, addError)
  const publicUrl = validateHttpsUrl('APP_PUBLIC_URL', config.APP_PUBLIC_URL, { originOnly: true }, addError)
  const corsOrigin = validateHttpsUrl('CORS_ALLOWED_ORIGIN', config.CORS_ALLOWED_ORIGIN, { originOnly: true }, addError)
  const cspOrigin = validateHttpsUrl('OIDC_CSP_CONNECT_SRC', config.OIDC_CSP_CONNECT_SRC, { originOnly: true }, addError)

  if (publicUrl && corsOrigin && publicUrl.origin !== corsOrigin.origin) {
    addError('CORS_ALLOWED_ORIGIN', 'APP_PUBLIC_URL과 같은 origin이어야 합니다.')
  }
  if (issuer && cspOrigin && issuer.origin !== cspOrigin.origin) {
    addError('OIDC_CSP_CONNECT_SRC', 'OIDC issuer의 origin과 같아야 합니다.')
  }

  validateIdentifier('OIDC_AUDIENCE', config.OIDC_AUDIENCE, addError)
  validateIdentifier('OIDC_SPA_CLIENT_ID', config.OIDC_SPA_CLIENT_ID, addError)
  validateClaimName('OIDC_ROLES_CLAIM', config.OIDC_ROLES_CLAIM, addError)
  validateClaimName('OIDC_PRINCIPAL_CLAIM', config.OIDC_PRINCIPAL_CLAIM, addError)
  validateIdentifier('OIDC_ADMIN_ROLE', config.OIDC_ADMIN_ROLE, addError)

  const scopes = config.OIDC_SPA_SCOPE.split(/\s+/u).filter(Boolean)
  if (!scopes.includes('openid')) addError('OIDC_SPA_SCOPE', 'openid scope가 필요합니다.')
  if (new Set(scopes).size !== scopes.length) addError('OIDC_SPA_SCOPE', '중복 scope를 제거하세요.')
  if (scopes.includes('offline_access')) {
    warnings.push('OIDC_SPA_SCOPE: offline_access 사용 시 refresh token 보관·회수 정책 승인이 필요합니다.')
  }

  const currentKey = validateBase64Key('RESERVATION_ENCRYPTION_KEY', config.RESERVATION_ENCRYPTION_KEY, addError)
  const hmacKey = validateBase64Key('RESERVATION_HMAC_KEY', config.RESERVATION_HMAC_KEY, addError)
  const previousKey = config.RESERVATION_PREVIOUS_ENCRYPTION_KEY
    ? validateBase64Key('RESERVATION_PREVIOUS_ENCRYPTION_KEY', config.RESERVATION_PREVIOUS_ENCRYPTION_KEY, addError)
    : null

  validateKeyId('RESERVATION_ENCRYPTION_KEY_ID', config.RESERVATION_ENCRYPTION_KEY_ID, addError)
  if (previousKey) {
    validateKeyId('RESERVATION_PREVIOUS_ENCRYPTION_KEY_ID', config.RESERVATION_PREVIOUS_ENCRYPTION_KEY_ID, addError)
    if (config.RESERVATION_ENCRYPTION_KEY_ID === config.RESERVATION_PREVIOUS_ENCRYPTION_KEY_ID) {
      addError('RESERVATION_PREVIOUS_ENCRYPTION_KEY_ID', '현재 키 ID와 달라야 합니다.')
    }
  }
  if (currentKey && hmacKey && currentKey.equals(hmacKey)) {
    addError('RESERVATION_HMAC_KEY', 'AES 암호화 키와 다른 난수 키를 사용해야 합니다.')
  }
  if (currentKey && previousKey && currentKey.equals(previousKey)) {
    addError('RESERVATION_PREVIOUS_ENCRYPTION_KEY', '현재 AES 키와 달라야 합니다.')
  }

  validateInteger('RESERVATION_RETENTION_DAYS', config.RESERVATION_RETENTION_DAYS, 1, 3650, addError)
  validateInteger('RESERVATION_RETENTION_BATCH_SIZE', config.RESERVATION_RETENTION_BATCH_SIZE, 1, 1000, addError)
  validateInteger('RESERVATION_ENCRYPTION_ROTATION_BATCH_SIZE', config.RESERVATION_ENCRYPTION_ROTATION_BATCH_SIZE, 1, 1000, addError)
  validateInteger('PUBLIC_CREATE_RATE_LIMIT', config.PUBLIC_CREATE_RATE_LIMIT, 1, 10_000, addError)
  validateInteger('PUBLIC_MANAGEMENT_RATE_LIMIT', config.PUBLIC_MANAGEMENT_RATE_LIMIT, 1, 10_000, addError)
  validateInteger('PUBLIC_RATE_LIMIT_WINDOW_SECONDS', config.PUBLIC_RATE_LIMIT_WINDOW_SECONDS, 1, 3600, addError)
  validateInteger('APP_PORT', config.APP_PORT, 1, 65_535, addError)
  validateCron('RESERVATION_RETENTION_CRON', config.RESERVATION_RETENTION_CRON, addError)
  validateCron('RESERVATION_ENCRYPTION_ROTATION_CRON', config.RESERVATION_ENCRYPTION_ROTATION_CRON, addError)

  if (!['true', 'false'].includes(config.RESERVATION_ENCRYPTION_ROTATION_ENABLED)) {
    addError('RESERVATION_ENCRYPTION_ROTATION_ENABLED', 'true 또는 false여야 합니다.')
  } else if (config.RESERVATION_ENCRYPTION_ROTATION_ENABLED === 'true' && !previousKey) {
    addError('RESERVATION_PREVIOUS_ENCRYPTION_KEY', '키 교체가 활성화되면 직전 AES 키가 필요합니다.')
  }

  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/u.test(config.PRIVACY_POLICY_VERSION)) {
    addError('PRIVACY_POLICY_VERSION', '1~64자의 영문·숫자·점·밑줄·하이픈만 사용할 수 있습니다.')
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(config.APP_VERSION)
      || ['local', 'latest', 'dev', 'development'].includes(config.APP_VERSION.toLowerCase())) {
    addError('APP_VERSION', 'local/latest가 아닌 추적 가능한 release 또는 commit 버전이 필요합니다.')
  }

  if (config.RESERVATION_RETENTION_DAYS === '365') {
    warnings.push('RESERVATION_RETENTION_DAYS: 기본 365일이 실제 개인정보 보존 정책으로 승인되었는지 확인하세요.')
  }
  if (previousKey && config.RESERVATION_ENCRYPTION_ROTATION_ENABLED === 'false') {
    warnings.push('RESERVATION_PREVIOUS_ENCRYPTION_KEY: 교체 완료 후 제거 시점과 복구 가능성을 확인하세요.')
  }

  return { errors, warnings }
}

export async function checkOidcDiscovery({ issuer, fetchImpl = globalThis.fetch, timeoutMs = 10_000 }) {
  if (typeof fetchImpl !== 'function') throw new Error('Fetch API를 사용할 수 있는 Node.js 24 이상이 필요합니다.')
  const discoveryUrl = new URL(`${issuer.replace(/\/+$/u, '')}/.well-known/openid-configuration`)
  let response
  try {
    response = await fetchImpl(discoveryUrl, {
      headers: { Accept: 'application/json' },
      redirect: 'error',
      signal: AbortSignal.timeout(timeoutMs),
    })
  } catch (error) {
    throw new Error(`OIDC discovery 요청에 실패했습니다: ${error instanceof Error ? error.message : String(error)}`)
  }
  if (response.status !== 200) throw new Error(`OIDC discovery가 HTTP ${response.status}를 반환했습니다.`)
  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.toLowerCase().includes('application/json')) {
    throw new Error('OIDC discovery 응답이 application/json이 아닙니다.')
  }

  const metadata = await response.json()
  if (metadata?.issuer !== issuer) throw new Error('OIDC discovery issuer가 OIDC_ISSUER_URI와 정확히 일치하지 않습니다.')
  for (const field of ['authorization_endpoint', 'token_endpoint', 'jwks_uri']) {
    const value = metadata?.[field]
    let url
    try {
      url = new URL(value)
    } catch {
      throw new Error(`OIDC discovery ${field}가 올바른 URL이 아닙니다.`)
    }
    if (url.protocol !== 'https:') throw new Error(`OIDC discovery ${field}가 HTTPS가 아닙니다.`)
  }
  const methods = metadata?.code_challenge_methods_supported
  if (!Array.isArray(methods) || !methods.includes('S256')) {
    throw new Error('OIDC 공급자가 PKCE S256 지원을 광고하지 않습니다.')
  }
  return { issuer: metadata.issuer }
}

export function parseOptions(args) {
  const options = { envFile: '.env', checkOidc: false, timeoutMs: 10_000 }
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]
    if (argument === '--env-file') options.envFile = requiredValue(args, ++index, argument)
    else if (argument === '--check-oidc') options.checkOidc = true
    else if (argument === '--timeout-ms') options.timeoutMs = Number(requiredValue(args, ++index, argument))
    else if (argument === '--help' || argument === '-h') options.help = true
    else throw new Error(`알 수 없는 옵션입니다: ${argument}`)
  }
  return options
}

export class PreflightError extends Error {
  constructor(errors, warnings = []) {
    super(`배포 사전검증에서 ${errors.length}개 오류가 발견되었습니다.\n${errors.map((error) => `- ${error}`).join('\n')}`)
    this.name = 'PreflightError'
    this.errors = errors
    this.warnings = warnings
  }
}

function effectiveConfig(fileValues, environment) {
  const config = { ...defaults }
  for (const key of knownKeys) {
    if (Object.hasOwn(fileValues, key)) config[key] = fileValues[key]
    if (environment[key] !== undefined) config[key] = environment[key]
  }
  return config
}

function parseEnvValue(rawValue, lineNumber) {
  if (!rawValue.startsWith('"') && !rawValue.startsWith("'")) return rawValue.trim()
  const quote = rawValue[0]
  if (!rawValue.endsWith(quote) || rawValue.length === 1) {
    throw new Error(`환경 파일 ${lineNumber}행의 따옴표가 닫히지 않았습니다.`)
  }
  const inner = rawValue.slice(1, -1)
  if (quote === "'") return inner
  return inner.replace(/\\([nrt\\"])/gu, (_, escaped) => ({ n: '\n', r: '\r', t: '\t', '\\': '\\', '"': '"' })[escaped])
}

function looksLikePlaceholder(value) {
  const normalized = value.toLowerCase()
  return normalized.includes('replace-with')
    || normalized.includes('change-me')
    || normalized.includes('changeme')
    || normalized.includes('example.com')
    || normalized.includes('example.test')
    || normalized.includes('.invalid')
    || /[<>]/u.test(value)
}

function validateHttpsUrl(field, value, { originOnly }, addError) {
  let url
  try {
    url = new URL(value)
  } catch {
    addError(field, '올바른 URL이어야 합니다.')
    return null
  }
  if (url.protocol !== 'https:') addError(field, '운영 환경에서는 HTTPS가 필요합니다.')
  if (url.username || url.password || url.search || url.hash) {
    addError(field, '자격정보, query, fragment를 포함할 수 없습니다.')
  }
  if (originOnly && value !== url.origin) addError(field, '경로나 마지막 슬래시가 없는 origin이어야 합니다.')
  return url
}

function validateIdentifier(field, value, addError) {
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/u.test(value)) {
    addError(field, '1~128자의 영문·숫자·점·밑줄·콜론·슬래시·하이픈만 사용할 수 있습니다.')
  }
}

function validateClaimName(field, value, addError) {
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/u.test(value)) {
    addError(field, '지원되는 claim 경로 형식이 아닙니다.')
  }
}

function validateBase64Key(field, value, addError) {
  if (!/^(?:[A-Za-z0-9+/]{4}){10}[A-Za-z0-9+/]{3}=$/u.test(value)) {
    addError(field, '표준 Base64로 인코딩한 32바이트 키여야 합니다.')
    return null
  }
  const decoded = Buffer.from(value, 'base64')
  if (decoded.length !== 32 || decoded.toString('base64') !== value) {
    addError(field, '표준 Base64로 인코딩한 32바이트 키여야 합니다.')
    return null
  }
  if (new Set(decoded).size < 16) {
    addError(field, '반복 바이트가 많은 값 대신 암호학적 난수 32바이트를 사용해야 합니다.')
    return null
  }
  return decoded
}

function validateKeyId(field, value, addError) {
  if (!/^[A-Za-z0-9_-]{1,32}$/u.test(value)) {
    addError(field, '1~32자의 영문·숫자·밑줄·하이픈만 사용할 수 있습니다.')
  }
}

function validateInteger(field, value, minimum, maximum, addError) {
  const parsed = Number(value)
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum || String(parsed) !== value) {
    addError(field, `${minimum}~${maximum} 사이의 정수여야 합니다.`)
  }
}

function validateCron(field, value, addError) {
  if (value.trim().split(/\s+/u).length !== 6) addError(field, 'Spring 형식의 6개 필드 cron이어야 합니다.')
}

function requiredValue(args, index, option) {
  const value = args[index]
  if (!value || value.startsWith('--')) throw new Error(`${option} 값이 필요합니다.`)
  return value
}

function printHelp() {
  console.log(`TableFlow 배포 사전검증

사용법:
  node scripts/deployment-preflight.mjs --env-file .env [--check-oidc]

옵션:
  --env-file <path>  검증할 환경 파일, 기본 .env
  --check-oidc       issuer discovery와 PKCE S256 지원을 온라인 확인
  --timeout-ms <ms>  OIDC 요청 제한 시간, 기본 10000
  -h, --help         도움말

셸 환경 변수는 환경 파일보다 우선합니다. 검사 결과에 비밀번호와 키는 출력하지 않습니다.`)
}

async function main() {
  try {
    const options = parseOptions(process.argv.slice(2))
    if (options.help) return printHelp()
    const result = await runDeploymentPreflight(options)
    for (const check of result.checks) console.log(`PASS  ${check}`)
    for (const warning of result.warnings) console.warn(`WARN  ${warning}`)
    console.log(`PASS  ${result.summary.appVersion} 배포 설정 사전검증 완료 (OIDC discovery: ${result.summary.oidcDiscovery})`)
  } catch (error) {
    console.error(`FAIL  ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}

const entryPoint = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : ''
if (import.meta.url === entryPoint) await main()
