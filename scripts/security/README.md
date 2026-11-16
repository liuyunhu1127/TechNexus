# TNX-GATE-007 exposure rehearsal

`Test-TechNexusExposure.ps1` checks defensive response headers in local mode. Candidate mode additionally requires non-loopback HTTPS URLs, validates the negotiated TLS version and certificate lifetime, checks the HTTP-to-HTTPS redirect, HSTS and a bounded list of commonly exposed ports.

```powershell
pwsh -File scripts/security/Test-TechNexusExposure.ps1 `
  -PublicUrl https://candidate.example.com `
  -ApiUrl https://candidate.example.com `
  -Classification CANDIDATE_GATE `
  -AllowedPublicPorts 80,443 `
  -OutputPath artifacts/security/candidate-exposure.json
```

Run it from an independent network location; a host-local port result does not prove the public firewall boundary. `CANDIDATE_REVIEW_REQUIRED` still requires an authorized OWASP/API penetration test, authenticated/unauthenticated IDOR and upload abuse regression, dependency/secret scan evidence and manual review of the reverse proxy. Never point the script or penetration tools at a system without authorization.
