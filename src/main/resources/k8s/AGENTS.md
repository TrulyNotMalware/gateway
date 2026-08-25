<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# main/resources/k8s

## Purpose
Raw Kubernetes manifests for the `api-service` namespace. Applied with plain `kubectl apply -f` —
there is no kustomization or Helm chart, so `$IMAGE_NAME` and `REPLACE_ME_*` placeholders are
substituted by the deployment pipeline or by hand.

## Key Files

| File | Description |
|------|-------------|
| `deployment.yaml` | `api-gateway-deploy`, 2 replicas, RollingUpdate `maxUnavailable: 0`, ports 8080 (http) + 8081 (mgmt), `terminationGracePeriodSeconds: 45`, Prometheus scrape annotations |
| `service.yaml` | `api-gateway-svc`, port 80 → targetPort `http` |
| `configmap.yaml` | `api-gateway-cm` — downstream URIs, timeouts, Redis cluster seed, JWT issuer triples, `JAVA_TOOL_OPTIONS` |
| `secret.example.yaml` | Template only. `APP_CONFIG_SECURITY_GATEWAY_SHARED_SECRET` + `APP_CONFIG_REDIS_PASSWORD`. **Never commit a populated copy** |
| `networkpolicy.yaml` | Default-deny ingress and egress, then explicit allows |
| `httproute.yaml` | Gateway API `HTTPRoute` binding the public hostname to `api-gateway-svc` |

## For AI Agents

### Working In This Directory

**The NetworkPolicy ingress rule and `application-prod.yaml`'s `trusted-proxies` regex are one
invariant split across two files.** Ingress on 8080 is restricted to `istio-system`; that is the
only reason trusting all RFC1918 ranges is safe. Widening the ingress rule without narrowing the
regex hands any allowed pod the ability to spoof `X-Forwarded-For`.

**Traffic must not bypass the gateway.** `httproute.yaml` points the API hostname here because
backends verify the `X-Gateway-Auth` hop proof. Routing that hostname straight at a backend, or
adding a second route that skips this service, breaks authentication for the whole platform.

**Adding a JWT issuer is three ConfigMap keys**, no code change:
```
APP_CONFIG_JWT_ISSUERS_<N>_ISSUER
APP_CONFIG_JWT_ISSUERS_<N>_JWKS_URI
APP_CONFIG_JWT_ISSUERS_<N>_AUDIENCE
```
The `<N>` indices must be contiguous from 0 — Spring's relaxed list binding stops at the first gap.
Egress to the new backend's JWKS port must also be allowed in `networkpolicy.yaml`.

**Keys must use underscores, not hyphens.** `envFrom` skips hyphenated ConfigMap keys entirely, so
`PRJ_DOK_ORIGINS` works and `prj-dok-origins` silently does not.

**The shared secret is symmetric**: `APP_CONFIG_SECURITY_GATEWAY_SHARED_SECRET` must equal
`blog_be`'s `GATEWAY_SHARED_SECRET`, and must be at least 32 chars or the pod refuses to start in
prod. Generate with `openssl rand -hex 32`.

**`secretRef` is `optional: false`** in `deployment.yaml` — a missing Secret crashes the pod
rather than letting it boot without a Redis password. Note that `secret.example.yaml`'s header
comment still claims `optional: true`; the comment is stale, the manifest is authoritative.

### Ports
8080 is the app port and 8081 is the management port; they are separated so actuator is never
reachable from public traffic. The container runs non-root, hence 1024+ ports with the Service
mapping 80 → 8080.

### Testing Requirements
No automated coverage. Validate with `kubectl apply --dry-run=server -f <file>` and confirm the
ConfigMap key names against the relaxed-binding names in `AppConfig.kt`.

## Dependencies

### Internal
- `../application-prod.yaml` — consumes every `${...}` placeholder defined in `configmap.yaml`

### External
- Istio (`istio-system`) as the Gateway API data plane
- Redis Cluster at `redis-cluster.infra.svc.cluster.local:6379`
- Backends: `blog-app.blog`, `dok-svc.dok`, `fileserver.fileserver`
- Prometheus in the `monitoring` namespace, scraping `:8081/actuator/prometheus`

<!-- MANUAL: -->
