import { readFile } from 'node:fs/promises'
import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript'

const contractUrl = new URL('../openapi/openapi.yaml', import.meta.url)
const generatedTypesUrl = new URL('./src/api/schema.d.ts', import.meta.url)
const expected = COMMENT_HEADER + astToString(await openapiTS(contractUrl))
const actual = await readFile(generatedTypesUrl, 'utf8')

if (actual.replaceAll('\r\n', '\n') !== expected.replaceAll('\r\n', '\n')) {
  console.error('OpenAPI 생성 타입이 계약과 일치하지 않습니다. npm run openapi:generate를 실행하세요.')
  process.exitCode = 1
}
