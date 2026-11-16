# TNX-GATE-006 observability rehearsal

The application exposes public liveness/readiness probes and protects detailed Prometheus metrics with the normal operations/admin authorization boundary. Supply a short-lived token through `TECHNEXUS_METRICS_TOKEN`; never place it in a report or command history.

```powershell
$env:TECHNEXUS_METRICS_TOKEN = '<short-lived token>'
./scripts/observability/Test-TechNexusObservability.ps1 `
  -Classification LOCAL_REHEARSAL `
  -BaseUrl http://127.0.0.1:8080 `
  -OutputPath ./artifacts/observability/check.json
```

Candidate mode rejects loopback. It checks probe reachability, JVM/thread/Hikari/HTTP/database/queue metrics and local disk free space. A successful numeric check still returns `CANDIDATE_REVIEW_REQUIRED`: SRE must demonstrate dashboard ingestion, slow SQL, backup/disk/queue alert firing, notification delivery and recovery on the candidate environment before closing `TNX-GATE-006`.
