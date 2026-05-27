# API Gateway Service

## Features

- **Blacklist Filtering**: Block requests from specific IPs, user IDs, or API keys
- **Redis Integration**: Support for both Redis cluster and standalone configurations
- **Reactive Programming**: Built with Spring WebFlux and Kotlin Coroutines
- **Kubernetes Ready**: Includes deployment configurations for Kubernetes

## Requirements

- JDK 25
- Kotlin 2.3.0
- Redis (optional, can run in-memory mode)

## Configuration

The gateway supports different Redis configurations:

### Redis Standalone

```yaml
app:
  config:
    redis:
      mode: standalone
      password: your-password-here
      host: 127.0.0.1
      port: 6379
```

### Redis Cluster
```yaml
app:
  config:
    redis:
      mode: cluster
      cluster:
        nodes:
          - redis://redis-node-0:6379
          - redis://redis-node-1:6379
          - redis://redis-node-2:6379
      password: your-password-here
```

### In-Memory Mode (No Redis)
```yaml
app:
  config:
    redis:
      mode: none
```

## Building and Running

### Build with Gradle

```bash
./gradlew build
```

### Run Locally

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

### Docker

```bash
# Build Docker image
docker build -t gateway:latest .

# Run Docker container
docker run -p 8080:8080 gateway:latest
```

### Kubernetes

Deployment files are available in `src/main/resources/k8s/`.

```bash
kubectl apply -f src/main/resources/k8s/deployment.yaml
kubectl apply -f src/main/resources/k8s/service.yaml
```

## Security Policy

Defaults live in `AppConfig.Security` (Kotlin) and can be overridden via env
(`APP_CONFIG_SECURITY_*`) — typically through `k8s/dok/configmap.yaml`.

### RateLimit thresholds

| Key | Default | Notes |
|---|---|---|
| `ipMaxRequests` | 1000 / 60s | per source IP |
| `userMaxRequests` | 500 / 60s | per authenticated user |
| `apiKeyMaxRequests` | 1000 / 60s | per API key |
| `endpointMaxRequests` | 100 / 60s | per (endpoint, identifier) |
| `windowSeconds` | 60 | sliding window for all counters |

`RateLimitService.checkMultipleRateLimits` runs IP/user/api-key/endpoint checks
in parallel and applies the **tightest** remaining quota. The endpoint key's
identifier falls back `userId → apiKey → ip → "anonymous"`, so anonymous
traffic is still partitioned by IP (one bot cannot exhaust the quota for other
visitors).

Tune from real traffic by uncommenting the keys in
`k8s/dok/configmap.yaml`; no code change required.

### Redis failure policy (`failOpenOnRedisFailure`)

When the Redis `INCRBY` call fails, `ReactiveRedissonClientModule.increment`
falls back to a sentinel value:

| Toggle | Sentinel | Effect |
|---|---|---|
| `true` (default) | `0L` | RateLimit treats request as allowed → **fail-open** |
| `false` | `Long.MAX_VALUE` | RateLimit treats request as exceeded → **fail-closed** (429) |

**Default rationale (fail-open):** the gateway is a read-heavy blog edge and
RateLimit is an *additional* protection layer; the Blacklist module runs
independently, so known-bad IPs stay blocked even when Redis is down. Letting
Redis outages take down routing/CB/auth would cost more than a brief RateLimit
gap.

Switch stance with `APP_CONFIG_SECURITY_FAIL_OPEN_ON_REDIS_FAILURE: "false"`
in the ConfigMap (no code change). A hybrid per-pod in-memory fallback is
tracked in `PLAN.md` §5 (P3).

---

# API 게이트웨이 서비스

## 기능

- **블랙리스트 필터링**: 특정 IP, 사용자 ID 또는 API 키의 요청 차단
- **Redis 통합**: Redis 클러스터 및 독립 실행형 구성 모두 지원
- **리액티브 프로그래밍**: Spring WebFlux 및 Kotlin 코루틴으로 구축
- **Kubernetes 지원**: Kubernetes 배포 구성 포함

## 요구 사항

- JDK 25
- Kotlin 2.3.0
- Redis (선택 사항, 인메모리 모드로 실행 가능)

## 구성

게이트웨이는 다양한 Redis 구성을 지원합니다:

### Redis 독립 실행형

```yaml
app:
  config:
    redis:
      mode: standalone
      password: your-password-here
      host: 127.0.0.1
      port: 6379
```

### Redis 클러스터

```yaml
app:
  config:
    redis:
      cluster:
        mode: cluster
        nodes:
          - redis://redis-node-0:6379
          - redis://redis-node-1:6379
          - redis://redis-node-2:6379
      password: your-password-here
```

### 인메모리 모드 (Redis 없음)

```yaml
app:
  config:
    redis:
      mode: none
```

## 빌드 및 실행

### Gradle로 빌드

```bash
./gradlew build
```

### 로컬에서 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

### Docker

```bash
# Docker 이미지 빌드
docker build -t gateway:latest .

# Docker 컨테이너 실행
docker run -p 8080:8080 gateway:latest
```

### Kubernetes

배포 파일은 `src/main/resources/k8s/`에서 사용할 수 있습니다.

```bash
kubectl apply -f src/main/resources/k8s/deployment.yaml
kubectl apply -f src/main/resources/k8s/service.yaml
```

## 보안 정책

기본값은 `AppConfig.Security` (Kotlin) 에 정의되어 있고 env (`APP_CONFIG_SECURITY_*`)
로 override 가능 — 보통 `k8s/dok/configmap.yaml` 를 통해 주입.

### RateLimit 임계값

| 키 | 기본값 | 비고 |
|---|---|---|
| `ipMaxRequests` | 1000 / 60s | 출발 IP 별 |
| `userMaxRequests` | 500 / 60s | 인증된 사용자 별 |
| `apiKeyMaxRequests` | 1000 / 60s | API key 별 |
| `endpointMaxRequests` | 100 / 60s | (endpoint, identifier) 별 |
| `windowSeconds` | 60 | 모든 카운터의 슬라이딩 윈도우 |

`RateLimitService.checkMultipleRateLimits` 는 IP/user/api-key/endpoint 체크를
병렬로 수행하고 **가장 빠듯한** 남은 quota 를 적용. endpoint 키의 identifier 는
`userId → apiKey → ip → "anonymous"` fallback 이라 anonymous 트래픽도 IP 단위로
분리됨 (봇 1대가 다른 방문자 quota 를 고갈시킬 수 없음).

실측 트래픽으로 튜닝하려면 `k8s/dok/configmap.yaml` 의 키 주석을 해제. 코드 변경 불필요.

### Redis 장애 시 정책 (`failOpenOnRedisFailure`)

Redis `INCRBY` 실패 시 `ReactiveRedissonClientModule.increment` 가 sentinel 값으로 fallback:

| 토글 | Sentinel | 효과 |
|---|---|---|
| `true` (기본) | `0L` | RateLimit 이 통과로 해석 → **fail-open** |
| `false` | `Long.MAX_VALUE` | RateLimit 이 초과로 해석 → **fail-closed** (429) |

**기본값 (fail-open) 근거**: 게이트웨이는 read-heavy 블로그 edge 이고 RateLimit 은
*추가* 보호층. Blacklist 모듈이 독립적으로 동작하므로 알려진 악성 IP 는 Redis 다운에도
계속 차단됨. Redis 장애로 라우팅/CB/auth 까지 죽이는 손해가 일시적 RateLimit 갭보다 큼.

stance 전환은 ConfigMap 에서 `APP_CONFIG_SECURITY_FAIL_OPEN_ON_REDIS_FAILURE: "false"`
로 변경 (코드 변경 X). 하이브리드 per-pod in-memory fallback 은 `PLAN.md` §5 (P3) 로 분리.
