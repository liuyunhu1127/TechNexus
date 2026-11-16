# TNX-GATE-004 candidate-server gate

Run the load generator on a host separate from the candidate application server. A gating run deliberately rejects a loopback target and shortened windows.

Local non-gating rehearsal:

```powershell
./scripts/candidate/Invoke-TechNexusCandidateGate.ps1 `
  -Classification LOCAL_REHEARSAL `
  -BaseUrl http://127.0.0.1:8080 `
  -SustainedSeconds 20 -BurstSeconds 10 -StabilitySeconds 60
```

Candidate run (the defaults are 10 QPS/60s, 20 QPS/30s, and 10 QPS/30min):

```powershell
$env:TECHNEXUS_METRICS_TOKEN = '<short-lived operations/admin access token>'
./scripts/candidate/Invoke-TechNexusCandidateGate.ps1 `
  -Classification CANDIDATE_GATE `
  -BaseUrl https://candidate.example.com
```

On the candidate host, run the host collector for the same stability window and capture the host inventory. Use `-Role DATABASE_HOST` on a separate database host.

```powershell
./scripts/candidate/Get-TechNexusCandidateInventory.ps1 `
  -Classification CANDIDATE_GATE -Role TARGET_HOST `
  -BaseUrl https://candidate.example.com `
  -OutputPath ./target-host-inventory.json

./scripts/candidate/Collect-TechNexusHostMetrics.ps1 `
  -DurationSeconds 1800 -IntervalSeconds 5 `
  -OutputPath ./target-host-metrics.csv
```

The metrics token is read only from the process environment and is never written to an artifact. Run the companion inventory on the application and database hosts as well; the automatic inventory describes the independent load generator, not the target host.

`manifest.json` remains `CANDIDATE_REVIEW_REQUIRED` even when the numeric checks pass. SRE/QA must attach and review the raw samples, JVM/GC/thread/Hikari trends, MySQL before/after counters, `target-host-inventory.json`, `target-host-metrics.csv`, disk/network trends, and queue deltas. A `LOCAL_REHEARSAL` artifact can never close `TNX-GATE-004` or `RTM-GAP-005`.
