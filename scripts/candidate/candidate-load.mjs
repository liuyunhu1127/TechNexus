import { mkdir, writeFile } from "node:fs/promises";
import { performance } from "node:perf_hooks";
import process from "node:process";

function readOptions(argv) {
  const values = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value === undefined) throw new Error(`Invalid argument near ${key ?? "<end>"}`);
    values.set(key.slice(2), value);
  }
  return {
    baseUrl: values.get("base-url") ?? "http://127.0.0.1:8080",
    outputDirectory: values.get("output-directory") ?? "artifacts/candidate-gate/latest",
    classification: values.get("classification") ?? "local-rehearsal",
    sustainedSeconds: Number(values.get("sustained-seconds") ?? 60),
    burstSeconds: Number(values.get("burst-seconds") ?? 30),
    stabilitySeconds: Number(values.get("stability-seconds") ?? 1800),
    metricsIntervalSeconds: Number(values.get("metrics-interval-seconds") ?? 5),
  };
}

function assertOptions(options) {
  if (!["local-rehearsal", "candidate-gate"].includes(options.classification)) {
    throw new Error("classification must be local-rehearsal or candidate-gate");
  }
  const url = new URL(options.baseUrl);
  const loopback = ["localhost", "127.0.0.1", "::1", "[::1]"].includes(url.hostname);
  if (options.classification === "candidate-gate" && loopback) {
    throw new Error("candidate-gate refuses a loopback BaseUrl; use a remote candidate URL from an independent load generator");
  }
  for (const [name, value] of Object.entries({
    sustainedSeconds: options.sustainedSeconds,
    burstSeconds: options.burstSeconds,
    stabilitySeconds: options.stabilitySeconds,
    metricsIntervalSeconds: options.metricsIntervalSeconds,
  })) {
    if (!Number.isFinite(value) || value <= 0) throw new Error(`${name} must be a positive number`);
  }
  if (
    options.classification === "candidate-gate" &&
    (options.sustainedSeconds < 60 || options.burstSeconds < 30 || options.stabilitySeconds < 1800)
  ) {
    throw new Error("candidate-gate requires at least 60s sustained, 30s burst, and 1800s stability windows");
  }
}

const options = readOptions(process.argv.slice(2));
assertOptions(options);
await mkdir(options.outputDirectory, { recursive: true });

const paths = ["/api/v1/contents", "/api/v1/demands", "/api/v1/search?q=phase4"];
const token = process.env.TECHNEXUS_METRICS_TOKEN?.trim();
const requestSamples = [];
const metricSamples = [];
let stopMetrics = false;

function percentile(sorted, value) {
  if (sorted.length === 0) return 0;
  const index = Math.min(sorted.length - 1, Math.ceil((value / 100) * sorted.length) - 1);
  return sorted[index];
}

function csv(value) {
  const text = value === null || value === undefined ? "" : String(value);
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

async function request(scenario, path) {
  const startedAt = new Date().toISOString();
  const started = performance.now();
  let status = 0;
  let error = "";
  try {
    const response = await fetch(`${options.baseUrl}${path}`, {
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(5000),
    });
    status = response.status;
    await response.arrayBuffer();
  } catch (caught) {
    error = caught instanceof Error ? caught.name : "UnknownError";
  }
  const sample = {
    scenario,
    startedAt,
    path,
    status,
    latencyMs: Number((performance.now() - started).toFixed(3)),
    error,
  };
  requestSamples.push(sample);
  return sample;
}

function parsePrometheus(text) {
  const wanted = /^(jvm_memory_used_bytes|jvm_gc_pause_seconds_(count|sum|max)|jvm_threads_live_threads|hikaricp_connections_(active|idle|max|pending)|process_cpu_usage|system_cpu_usage|process_uptime_seconds|http_server_requests_seconds_(count|sum|max))(\{|\s)/;
  const values = {};
  for (const line of text.split(/\r?\n/)) {
    if (line.startsWith("#") || !wanted.test(line)) continue;
    const split = line.lastIndexOf(" ");
    if (split < 1) continue;
    const key = line.slice(0, split);
    const value = Number(line.slice(split + 1));
    if (Number.isFinite(value)) values[key] = value;
  }
  return values;
}

async function collectMetrics() {
  const headers = { Accept: "text/plain" };
  if (token) headers.Authorization = `Bearer ${token}`;
  while (!stopMetrics) {
    const sample = { collectedAt: new Date().toISOString(), status: 0, error: "", values: {} };
    try {
      const response = await fetch(`${options.baseUrl}/actuator/prometheus`, {
        headers,
        signal: AbortSignal.timeout(5000),
      });
      sample.status = response.status;
      const body = await response.text();
      if (response.ok) sample.values = parsePrometheus(body);
      else sample.error = `HTTP_${response.status}`;
    } catch (caught) {
      sample.error = caught instanceof Error ? caught.name : "UnknownError";
    }
    metricSamples.push(sample);
    await new Promise((resolve) => setTimeout(resolve, options.metricsIntervalSeconds * 1000));
  }
}

async function runScenario(name, qps, durationSeconds) {
  const total = Math.floor(qps * durationSeconds);
  const intervalMs = 1000 / qps;
  const pending = [];
  const started = performance.now();
  for (let index = 0; index < total; index += 1) {
    const waitMs = Math.max(0, started + index * intervalMs - performance.now());
    if (waitMs > 0) await new Promise((resolve) => setTimeout(resolve, waitMs));
    pending.push(request(name, paths[index % paths.length]));
  }
  const results = await Promise.all(pending);
  const elapsedSeconds = (performance.now() - started) / 1000;
  const latencies = results.map((item) => item.latencyMs).sort((a, b) => a - b);
  const serverErrors = results.filter((item) => item.status >= 500).length;
  const transportErrors = results.filter((item) => item.status === 0).length;
  const nonSuccess = results.filter((item) => item.status < 200 || item.status >= 300).length;
  const summary = {
    name,
    targetQps: qps,
    durationSeconds,
    requests: total,
    achievedQps: Number((total / elapsedSeconds).toFixed(3)),
    p50Ms: Number(percentile(latencies, 50).toFixed(3)),
    p95Ms: Number(percentile(latencies, 95).toFixed(3)),
    p99Ms: Number(percentile(latencies, 99).toFixed(3)),
    maxMs: Number((latencies.at(-1) ?? 0).toFixed(3)),
    serverErrorRatePercent: Number(((serverErrors / total) * 100).toFixed(4)),
    nonSuccessRatePercent: Number(((nonSuccess / total) * 100).toFixed(4)),
    transportErrors,
  };
  summary.passed =
    summary.achievedQps >= qps * 0.95 &&
    summary.p95Ms <= 500 &&
    summary.p99Ms <= 1000 &&
    summary.serverErrorRatePercent < 0.1 &&
    summary.transportErrors === 0;
  return summary;
}

async function warmup() {
  for (let index = 0; index < 20; index += 1) await request("warmup", paths[index % paths.length]);
}

const metricsTask = collectMetrics();
await warmup();
const scenarios = [];
scenarios.push(await runScenario("PERF-001-sustained", 10, options.sustainedSeconds));
scenarios.push(await runScenario("PERF-002-burst", 20, options.burstSeconds));
scenarios.push(await runScenario("PERF-003-stability", 10, options.stabilitySeconds));
stopMetrics = true;
await metricsTask;

const metricsAccessible = metricSamples.some((item) => item.status === 200 && Object.keys(item.values).length > 0);
const summary = {
  schemaVersion: 1,
  classification: options.classification,
  gatingEvidence: options.classification === "candidate-gate",
  baseUrl: options.baseUrl,
  executedAt: new Date().toISOString(),
  thresholds: { p95Ms: 500, p99Ms: 1000, serverErrorRatePercentExclusive: 0.1, minimumThroughputRatio: 0.95 },
  telemetry: {
    metricsAccessible,
    samples: metricSamples.length,
    note: metricsAccessible
      ? "Actuator Prometheus samples captured; review JVM/GC/thread/Hikari trends before approval."
      : "Actuator Prometheus telemetry was unavailable; candidate evidence is incomplete.",
  },
  scenarios,
};
summary.automatedChecksPassed = scenarios.every((item) => item.passed);
summary.candidateReviewReady = summary.gatingEvidence && summary.automatedChecksPassed && metricsAccessible;
summary.result = summary.gatingEvidence
  ? summary.candidateReviewReady
    ? "CANDIDATE_REVIEW_REQUIRED"
    : "CANDIDATE_FAILED_OR_INCOMPLETE"
  : "NON_GATING_LOCAL_REHEARSAL";

const requestHeader = ["scenario", "startedAt", "path", "status", "latencyMs", "error"];
const requestCsv = [
  requestHeader.join(","),
  ...requestSamples.map((item) => requestHeader.map((key) => csv(item[key])).join(",")),
].join("\n");
const metricCsv = [
  "collectedAt,status,error,metric,value",
  ...metricSamples.flatMap((sample) => {
    const entries = Object.entries(sample.values);
    if (entries.length === 0) return [[sample.collectedAt, sample.status, sample.error, "", ""].map(csv).join(",")];
    return entries.map(([metric, value]) => [sample.collectedAt, sample.status, sample.error, metric, value].map(csv).join(","));
  }),
].join("\n");

await Promise.all([
  writeFile(`${options.outputDirectory}/load-summary.json`, `${JSON.stringify(summary, null, 2)}\n`, "utf8"),
  writeFile(`${options.outputDirectory}/request-samples.csv`, `${requestCsv}\n`, "utf8"),
  writeFile(`${options.outputDirectory}/runtime-metrics.csv`, `${metricCsv}\n`, "utf8"),
]);
console.log(JSON.stringify(summary, null, 2));
process.exitCode = summary.automatedChecksPassed ? 0 : 1;
