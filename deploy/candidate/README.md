# Public candidate deployment

This directory deploys the candidate application stack with Docker Compose. Because the former public MinIO container repositories no longer permit anonymous pulls, a fixed official MinIO source release is compiled in a digest-pinned official Go builder and the verified binary runs inside a digest-pinned Debian container. Browser-facing services remain bound to loopback. Tailscale Funnel publishes the user application and the S3-compatible file-transfer endpoint, while Tailscale Serve keeps the administration endpoint and the original MinIO endpoint private to the Tailnet.

Endpoints for the default layout:

- public user application and API: `https://<tailnet-host>`;
- Tailnet-only administration: `https://<tailnet-host>:8443`;
- public S3-compatible file transfer: `https://<tailnet-host>:10000`;
- optional Tailnet-only MinIO API: `https://<tailnet-host>:9000`;
- backend, MySQL, ClamAV, and the MinIO console are not directly exposed.

`prepare-host.sh` creates `/etc/technexus/technexus.env` once with mode `0600`. It generates independent random database, JWT, MinIO root, and application storage credentials and never prints their values. Re-running it preserves the existing file. `build-minio.sh` uses a digest-pinned official Go builder and fixed public MinIO release tags. Place the resulting `minio` binary in the release `bin` directory. The application storage account and bucket-scoped policy must be initialized with the resulting `mc` binary before accepting uploads.

Build the release with the Nuxt proxy targeting the Compose backend:

```powershell
$env:TECHNEXUS_API_PROXY_TARGET='http://technexus-server:8080/api/**'
npm --prefix technexus-web run build
npm --prefix technexus-admin run build
mvn --settings .mvn/settings.xml --no-transfer-progress -pl technexus-server -am -DskipTests package
```

Place the server JAR at `app/technexus-server.jar`, Nuxt `.output` contents at `app/web`, and the administration `dist` contents at `app/admin`. Then start the release on the target host:

```bash
sudo ./prepare-host.sh '<tailnet-host>' '<bootstrap-admin-email>'
HTTP_PROXY='<optional-build-proxy>' HTTPS_PROXY='<optional-build-proxy>' ./build-minio.sh ./bin
sudo docker compose --env-file /etc/technexus/technexus.env up -d --wait minio mysql clamav
sudo ./init-minio.sh "$(pwd)"
sudo docker compose --env-file /etc/technexus/technexus.env up -d --wait
sudo tailscale funnel --bg --https=443 --yes http://127.0.0.1:3000
sudo tailscale serve --bg --https=8443 --yes http://127.0.0.1:5173
sudo tailscale serve --bg --https=9000 --yes http://127.0.0.1:9000
sudo tailscale funnel --bg --https=10000 --yes http://127.0.0.1:9000
```

Do not publish the administration endpoint, database, ClamAV, MinIO console, or raw backend with Funnel. The Compose file pins all runtime images by digest. Before promotion, verify Compose and MinIO health, Flyway migrations, public HTTPS access, Tailnet-only administration access, registration with the configured bootstrap email, and the complete upload/scan/download path with `-ExpectedStoragePort 10000`. Record the SHA-256 hashes of the fixed-version MinIO binaries.

This layout is a candidate environment, not a production approval. It does not close the independent backup, independent load-generator, mobile/Safari, observability-delivery, or penetration-test gates.
