import { performance } from "node:perf_hooks";

const baseUrl = process.argv[2] ?? "http://127.0.0.1:8080";
const scenarios = [
  { name: "PERF-001-sustained", qps: 10, durationSeconds: 60 },
  { name: "PERF-002-burst", qps: 20, durationSeconds: 30 },
];
const paths = ["/api/v1/contents", "/api/v1/demands", "/api/v1/search?q=phase4"];

function percentile(sorted, value) {
  if (sorted.length === 0) return 0;
  const index = Math.min(sorted.length - 1, Math.ceil((value / 100) * sorted.length) - 1);
  return sorted[index];
}

async function request(path) {
  const started = performance.now();
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(3000),
    });
    await response.arrayBuffer();
    return { latencyMs: performance.now() - started, status: response.status, error: null };
  } catch (error) {
    return { latencyMs: performance.now() - started, status: 0, error: error.name };
  }
}

async function runScenario(scenario) {
  const total = scenario.qps * scenario.durationSeconds;
  const intervalMs = 1000 / scenario.qps;
  const promises = [];
  const started = performance.now();
  for (let index = 0; index < total; index += 1) {
    const dueAt = started + index * intervalMs;
    const waitMs = Math.max(0, dueAt - performance.now());
    if (waitMs > 0) await new Promise((resolve) => setTimeout(resolve, waitMs));
    promises.push(request(paths[index % paths.length]));
  }
  const results = await Promise.all(promises);
  const elapsedSeconds = (performance.now() - started) / 1000;
  const latencies = results.map((item) => item.latencyMs).sort((a, b) => a - b);
  const serverErrors = results.filter((item) => item.status >= 500).length;
  const transportErrors = results.filter((item) => item.status === 0).length;
  const nonSuccess = results.filter((item) => item.status < 200 || item.status >= 300).length;
  return {
    name: scenario.name,
    targetQps: scenario.qps,
    durationSeconds: scenario.durationSeconds,
    requests: total,
    achievedQps: Number((total / elapsedSeconds).toFixed(2)),
    p50Ms: Number(percentile(latencies, 50).toFixed(2)),
    p95Ms: Number(percentile(latencies, 95).toFixed(2)),
    p99Ms: Number(percentile(latencies, 99).toFixed(2)),
    maxMs: Number(latencies.at(-1).toFixed(2)),
    serverErrorRatePercent: Number(((serverErrors / total) * 100).toFixed(3)),
    nonSuccessRatePercent: Number(((nonSuccess / total) * 100).toFixed(3)),
    transportErrors,
  };
}

for (let index = 0; index < 20; index += 1) await request(paths[index % paths.length]);
const results = [];
for (const scenario of scenarios) results.push(await runScenario(scenario));
console.log(JSON.stringify({ baseUrl, executedAt: new Date().toISOString(), results }, null, 2));

const failed = results.some(
  (item) => item.p95Ms > 500 || item.p99Ms > 1000 || item.serverErrorRatePercent >= 0.1 || item.transportErrors > 0,
);
process.exitCode = failed ? 1 : 0;
