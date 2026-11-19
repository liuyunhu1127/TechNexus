import { spawnSync } from 'node:child_process'
import { resolve } from 'node:path'

const webLockfile = 'technexus-web/package-lock.json'
const scanInputs = ['target/bom.json', webLockfile, 'technexus-admin/package-lock.json']

// Nuxt's v4.6.4 release and the GitHub advisories mark these npm versions fixed.
// OSV currently also applies an unbounded Nuxt 3 range to the installed Nuxt 4.
// https://github.com/nuxt/nuxt/releases/tag/v4.6.4
const nuxtExceptionExpiresAt = Date.parse('2026-12-04T00:00:00Z')
const reviewedNuxtFindings = new Map([
  ['nuxt', new Set(['GHSA-f3x9-2chm-73c8', 'GHSA-hf59-9xwc-2g4p', 'GHSA-96x6-fvhx-49hg'])],
  ['@nuxt/nitro-server', new Set(['GHSA-f3x9-2chm-73c8', 'GHSA-hf59-9xwc-2g4p'])],
])

export function evaluateReport(report, inputs, now = Date.now()) {
  if (!Array.isArray(report?.results)) throw new Error('Invalid OSV JSON report')
  const expected = new Set(inputs.map((input) => resolve(input)))
  const seen = new Set()
  const exceptions = []
  const blocking = []

  for (const { source, packages } of report.results) {
    if (typeof source?.path !== 'string' || !Array.isArray(packages) || packages.length === 0) {
      throw new Error('OSV report contains an invalid or empty source')
    }
    const path = resolve(source.path)
    if (!expected.has(path)) throw new Error(`Unexpected OSV source: ${source.path}`)
    seen.add(path)

    for (const { package: pkg, vulnerabilities = [] } of packages) {
      if (!pkg?.name || !pkg.version || !pkg.ecosystem || !Array.isArray(vulnerabilities)) {
        throw new Error(`OSV report contains invalid package metadata in ${source.path}`)
      }
      for (const { id } of vulnerabilities) {
        if (typeof id !== 'string' || !id) throw new Error('OSV finding is missing its advisory ID')
        const finding = `${pkg.ecosystem} ${pkg.name}@${pkg.version} ${id} (${source.path})`
        const reviewed = now < nuxtExceptionExpiresAt &&
          path === resolve(webLockfile) && source.type === 'lockfile' &&
          pkg.ecosystem === 'npm' && pkg.version === '4.6.4' &&
          reviewedNuxtFindings.get(pkg.name)?.has(id)
        if (reviewed) exceptions.push(finding)
        else blocking.push(finding)
      }
    }
  }

  for (const path of expected) {
    if (!seen.has(path)) throw new Error(`OSV report did not cover ${path}`)
  }
  return { exceptions, blocking }
}

function runGate(scanner, inputs) {
  const args = ['scan', 'source', '--config=osv-scanner.toml', '--format=json', '--all-packages', '--verbosity=error']
  for (const input of inputs) args.push('-L', input)

  const scan = spawnSync(scanner, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (scan.error) throw scan.error
  if (scan.stderr.trim() || (scan.status !== 0 && scan.status !== 1)) {
    throw new Error(`OSV scan did not complete successfully (exit ${scan.status}): ${scan.stderr.trim()}`)
  }

  const { exceptions, blocking } = evaluateReport(JSON.parse(scan.stdout), inputs)
  const foundVulnerabilities = exceptions.length + blocking.length > 0
  if ((scan.status === 1) !== foundVulnerabilities) {
    throw new Error('OSV exit status does not match the findings in the report')
  }

  for (const finding of exceptions) console.log(`Time-limited OSV exception (expires 2026-12-04 UTC): ${finding}`)
  for (const finding of blocking) console.error(`Blocking OSV finding: ${finding}`)
  if (blocking.length > 0) process.exitCode = 1
  else console.log(`OSV gate passed for ${inputs.length} inputs with ${exceptions.length} reviewed exceptions.`)
}

if (import.meta.main) {
  const [, , scanner, ...inputs] = process.argv
  if (!scanner) throw new Error('Usage: node scripts/security/osv-gate.mjs <osv-scanner> [input files]')
  runGate(scanner, inputs.length ? inputs : scanInputs)
}
