# TechNexus Prometheus rules

Load `technexus-alerts.yml` from the Prometheus `rule_files` section and keep the scrape job name `technexus-server`, or update the bounded `job` matchers before deployment. Validate with the same `promtool` version as the target Prometheus:

```text
promtool check rules ops/prometheus/technexus-alerts.yml
```

The repository does not bundle Prometheus, Alertmanager, node-exporter or MySQL-exporter. Application rules therefore cover only metrics emitted by Spring Boot/Micrometer. Host disk, native MySQL slow-query/deadlock and backup-age alerts must be wired from their respective exporters/scripts as described in the runbook; their notification delivery is a candidate-environment gate.
