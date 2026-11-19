import assert from 'node:assert/strict'
import { test } from 'node:test'
import { evaluateReport } from './osv-gate.mjs'

const webLockfile = 'technexus-web/package-lock.json'
const adminLockfile = 'technexus-admin/package-lock.json'
const backendSbom = 'target/bom.json'
const beforeExpiry = Date.parse('2026-12-03T23:59:59Z')
const atExpiry = Date.parse('2026-12-04T00:00:00Z')
const routeAdvisory = 'GHSA-f3x9-2chm-73c8'
const islandAdvisory = 'GHSA-hf59-9xwc-2g4p'
const errorAdvisory = 'GHSA-96x6-fvhx-49hg'

function source(path, packages, type = 'lockfile') {
  return { source: { path, type }, packages }
}

function matchedPackage(name, ids, version = '4.6.4', ecosystem = 'npm') {
  return { package: { name, version, ecosystem }, vulnerabilities: ids.map((id) => ({ id })) }
}

const reviewed = source(webLockfile, [
  matchedPackage('nuxt', [routeAdvisory, islandAdvisory, errorAdvisory]),
  matchedPackage('@nuxt/nitro-server', [routeAdvisory, islandAdvisory]),
])

test('allows exactly the five reviewed Nuxt findings before the deadline', () => {
  const result = evaluateReport({ results: [reviewed] }, [webLockfile], beforeExpiry)
  assert.equal(result.exceptions.length, 5)
  assert.deepEqual(result.blocking, [])
})

test('blocks findings at the expiry time', () => {
  const result = evaluateReport({ results: [reviewed] }, [webLockfile], atExpiry)
  assert.equal(result.blocking.length, 5)
  assert.deepEqual(result.exceptions, [])
})

test('blocks other versions, advisories, package combinations, ecosystems and inputs', () => {
  const results = [
    source(webLockfile, [
      matchedPackage('nuxt', [routeAdvisory], '4.6.3'),
      matchedPackage('nuxt', [routeAdvisory], '4.6.5'),
      matchedPackage('nuxt', ['GHSA-zzzz-zzzz-zzzz']),
      matchedPackage('@nuxt/nitro-server', [errorAdvisory]),
      matchedPackage('@nuxt/vite-server', [routeAdvisory]),
      matchedPackage('nuxt', [routeAdvisory], '4.6.4', 'Maven'),
    ]),
    source(adminLockfile, [matchedPackage('nuxt', [routeAdvisory])]),
    source(backendSbom, [matchedPackage('nuxt', [routeAdvisory])], 'sbom'),
  ]
  const result = evaluateReport({ results }, [webLockfile, adminLockfile, backendSbom], beforeExpiry)
  assert.equal(result.blocking.length, 8)
  assert.deepEqual(result.exceptions, [])
})

test('rejects incomplete or invalid scanner output', () => {
  assert.throws(() => evaluateReport({ results: [reviewed] }, [webLockfile, adminLockfile]), /did not cover/)
  assert.throws(() => evaluateReport({ results: [source(webLockfile, [])] }, [webLockfile]), /invalid or empty/)
  assert.throws(() => evaluateReport({ results: null }, [webLockfile]), /Invalid OSV JSON/)
})
