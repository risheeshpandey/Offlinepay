# M6 application deployment example

These 13 resources are an **application-only example**, schema-validated against Kubernetes 1.35. A Kubernetes cluster, Metrics Server, an image registry and reachable PostgreSQL/Kafka/Redis/Zipkin are prerequisites. No cluster/HPA runtime test has been performed in the authoring environment. Do not enable Kubernetes on the 8 GB laptop while running the current stack. This is a later deployment exercise, not required for local M6 testing.

1. Provision the two databases and least-privilege roles as in M5. Use stable externally stored Ed25519 keys. Configure Kafka's advertised listener addresses so pods can reach the broker. Localhost Windows addresses are not pod addresses.
2. Edit the ConfigMap in `apps.yml` to your actual infrastructure DNS names and database URLs. The default names `postgres`, `redis`, `kafka` and `zipkin` are contracts for services you must provision, not resources created by this file. Configure TLS/authentication for those systems for an externally hosted deployment.
3. Build each Java21 image from project root. The build layer for all JARs is shared; module selection follows it:

```powershell
docker build -f .\deploy\Dockerfile --build-arg MODULE=core -t offlinepay/core:m6 .
docker build -f .\deploy\Dockerfile --build-arg MODULE=notifications -t offlinepay/notifications:m6 .
docker build -f .\deploy\Dockerfile --build-arg MODULE=gateway -t offlinepay/gateway:m6 .
docker build -f .\deploy\Dockerfile --build-arg MODULE=discovery -t offlinepay/discovery:m6 .
```

Push to your own private registry or import images into your local cluster, then adjust image references. These commands do not publish GitHub code. The image runs as an unprivileged user, with writable emptyDir volumes for logs/temp. User secret/key files never enter the Docker build context.

4. Create the namespace first:

```powershell
kubectl create namespace offlinepay
```

Create a private, ignored `.env.kubernetes` in project root with four entries: `DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD`, `JWT_SECRET_BASE64`, `INTERNAL_SERVICE_TOKEN`. Use the actual existing private values; do not commit the file, paste them into chat or use sample secrets. Core receives its database/JWT/service credentials; notifications receives only its own database password and service credential.

```powershell
kubectl -n offlinepay create secret generic offlinepay-secrets --from-env-file=.env.kubernetes
kubectl -n offlinepay create secret generic offlinepay-signing-keys --from-file=private-key.txt=.\local-keys\private-key.txt --from-file=public-key.txt=.\local-keys\public-key.txt
kubectl apply -f .\deploy\kubernetes\apps.yml
kubectl -n offlinepay get pods,hpa,services
```

5. Startup/liveness check the process. Core readiness also requires DB and Redis; notification readiness requires its DB. Dependency outages do not fail liveness, avoiding restart storms. Gateway readiness only describes its own process: backend errors remain visible. Management ports have no public ingress, but are reachable within the cluster; restrict network access/RBAC in a shared cluster. Eureka remains private and unauthenticated in this demonstration.
6. HPA targets 60% of the **200m CPU request**, with min1/max3 and stabilization windows. Metrics Server is required. This configuration does not prove autoscaling or throughput. One hot wallet remains serialized by DB locking even with more replicas. The single Eureka and external infra are not made highly available by HPA.
7. Each pod advertises its own downward-API `SERVICE_IP`. Instance IDs include IP and port so replicas on the same port do not overwrite each other. Client discovery caches and self-preservation still introduce delays.
8. Secure refresh cookies require an HTTPS entry point. Install/configure your cluster's TLS ingress and forward only intended public API routes before browser auth acceptance. There is deliberately no public insecure demo ingress in these manifests. Logs use ephemeral pod volumes in this example; add your cluster log shipper and retention policy before relying on them operationally.

Metrics are collected from port9131(core),9132(notifications),9130(gateway),9762(discovery). Application Service names differ from Eureka application names intentionally: Eureka resolves registered pod addresses. CPU HPA is not a queue-length autoscaler, and it cannot fix a slow database or notification-provider outage.
