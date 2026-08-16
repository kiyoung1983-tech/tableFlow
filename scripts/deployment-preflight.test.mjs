import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, test } from 'node:test'

import {
  checkOidcDiscovery,
  parseEnv,
  PreflightError,
  runDeploymentPreflight,
  validateDeploymentConfig,
} from './deployment-preflight.mjs'

const temporaryDirectories = []

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })))
})

describe('deployment preflight', () => {
  test('accepts a production-shaped environment without exposing secrets', async () => {
    const environment = validEnvironment()
    const envFile = await writeEnvironment(environment)

    const result = await runDeploymentPreflight({ envFile, environment: {} })

    assert.equal(result.summary.publicOrigin, 'https://staging.tableflow.co.kr')
    assert.equal(result.summary.oidcDiscovery, 'skipped')
    assert.equal(JSON.stringify(result).includes(environment.POSTGRES_PASSWORD), false)
    assert.equal(JSON.stringify(result).includes(environment.RESERVATION_ENCRYPTION_KEY), false)
  })

  test('rejects example placeholders before deployment', async () => {
    const envFile = join(process.cwd(), '.env.example')

    await assert.rejects(
      runDeploymentPreflight({ envFile, environment: {} }),
      (error) => error instanceof PreflightError
        && error.errors.some((message) => message.startsWith('OIDC_ISSUER_URI:'))
        && error.errors.some((message) => message.startsWith('RESERVATION_ENCRYPTION_KEY:')),
    )
  })

  test('detects origin mismatch, reused keys, and unsafe rotation settings', () => {
    const environment = validEnvironment()
    environment.CORS_ALLOWED_ORIGIN = 'https://other.tableflow.co.kr'
    environment.RESERVATION_HMAC_KEY = environment.RESERVATION_ENCRYPTION_KEY
    environment.RESERVATION_ENCRYPTION_ROTATION_ENABLED = 'true'

    const result = validateDeploymentConfig(environment)

    assert.ok(result.errors.some((message) => message.startsWith('CORS_ALLOWED_ORIGIN:')))
    assert.ok(result.errors.some((message) => message.startsWith('RESERVATION_HMAC_KEY:')))
    assert.ok(result.errors.some((message) => message.startsWith('RESERVATION_PREVIOUS_ENCRYPTION_KEY:')))
  })

  test('matches backend key identifier and batch size limits', () => {
    const environment = validEnvironment()
    environment.RESERVATION_ENCRYPTION_KEY_ID = 'a'.repeat(33)
    environment.RESERVATION_RETENTION_BATCH_SIZE = '1001'
    environment.RESERVATION_ENCRYPTION_ROTATION_BATCH_SIZE = '1001'

    const result = validateDeploymentConfig(environment)

    assert.ok(result.errors.some((message) => message.startsWith('RESERVATION_ENCRYPTION_KEY_ID:')))
    assert.ok(result.errors.some((message) => message.startsWith('RESERVATION_RETENTION_BATCH_SIZE:')))
    assert.ok(result.errors.some((message) => message.startsWith('RESERVATION_ENCRYPTION_ROTATION_BATCH_SIZE:')))
  })

  test('parses quoted values and rejects duplicate keys', () => {
    assert.deepEqual(parseEnv('PLAIN=value\nQUOTED="hello\\nworld"\nSINGLE=\'literal\''), {
      PLAIN: 'value',
      QUOTED: 'hello\nworld',
      SINGLE: 'literal',
    })
    assert.throws(() => parseEnv('KEY=first\nKEY=second'), /중복/u)
  })

  test('checks matching OIDC metadata and PKCE S256', async () => {
    const issuer = 'https://login.tableflow.co.kr/realms/tableflow'
    let requestedUrl
    const result = await checkOidcDiscovery({
      issuer,
      fetchImpl: async (url) => {
        requestedUrl = url.toString()
        return Response.json({
          issuer,
          authorization_endpoint: `${issuer}/authorize`,
          token_endpoint: `${issuer}/token`,
          jwks_uri: `${issuer}/jwks`,
          code_challenge_methods_supported: ['S256'],
        })
      },
    })

    assert.equal(requestedUrl, `${issuer}/.well-known/openid-configuration`)
    assert.equal(result.issuer, issuer)
  })

  test('rejects discovery metadata for another issuer', async () => {
    const issuer = 'https://login.tableflow.co.kr/realms/tableflow'
    await assert.rejects(
      checkOidcDiscovery({
        issuer,
        fetchImpl: async () => Response.json({
          issuer: 'https://attacker.example.net',
          authorization_endpoint: `${issuer}/authorize`,
          token_endpoint: `${issuer}/token`,
          jwks_uri: `${issuer}/jwks`,
          code_challenge_methods_supported: ['S256'],
        }),
      }),
      /정확히 일치하지 않습니다/u,
    )
  })
})

function validEnvironment() {
  return {
    POSTGRES_DB: 'tableflow',
    POSTGRES_USER: 'tableflow_app',
    POSTGRES_PASSWORD: 'a-strong-database-password-2026',
    OIDC_ISSUER_URI: 'https://login.tableflow.co.kr/realms/tableflow',
    OIDC_AUDIENCE: 'tableflow-api',
    OIDC_ROLES_CLAIM: 'roles',
    OIDC_ADMIN_ROLE: 'tableflow-admin',
    OIDC_PRINCIPAL_CLAIM: 'sub',
    OIDC_SPA_CLIENT_ID: 'tableflow-spa',
    OIDC_SPA_SCOPE: 'openid profile tableflow-api',
    OIDC_SPA_RESOURCE: '',
    OIDC_CSP_CONNECT_SRC: 'https://login.tableflow.co.kr',
    CORS_ALLOWED_ORIGIN: 'https://staging.tableflow.co.kr',
    APP_PUBLIC_URL: 'https://staging.tableflow.co.kr',
    RESERVATION_ENCRYPTION_KEY: testKey('encryption'),
    RESERVATION_ENCRYPTION_KEY_ID: '2026-08-primary',
    RESERVATION_PREVIOUS_ENCRYPTION_KEY: '',
    RESERVATION_PREVIOUS_ENCRYPTION_KEY_ID: 'previous',
    RESERVATION_HMAC_KEY: testKey('hmac'),
    RESERVATION_RETENTION_DAYS: '365',
    RESERVATION_RETENTION_BATCH_SIZE: '100',
    RESERVATION_RETENTION_CRON: '0 20 3 * * *',
    RESERVATION_ENCRYPTION_ROTATION_ENABLED: 'false',
    RESERVATION_ENCRYPTION_ROTATION_BATCH_SIZE: '100',
    RESERVATION_ENCRYPTION_ROTATION_CRON: '0 40 3 * * *',
    PRIVACY_POLICY_VERSION: '2026-08-16',
    PUBLIC_CREATE_RATE_LIMIT: '10',
    PUBLIC_MANAGEMENT_RATE_LIMIT: '30',
    PUBLIC_RATE_LIMIT_WINDOW_SECONDS: '60',
    APP_VERSION: '2026.08.16-a1b2c3d',
    APP_PORT: '443',
  }
}

function testKey(label) {
  return createHash('sha256').update(`tableflow-test-only-${label}`).digest('base64')
}

async function writeEnvironment(environment) {
  const directory = await mkdtemp(join(tmpdir(), 'tableflow-preflight-'))
  temporaryDirectories.push(directory)
  const envFile = join(directory, '.env')
  await writeFile(envFile, Object.entries(environment).map(([key, value]) => `${key}=${value}`).join('\n'))
  return envFile
}
