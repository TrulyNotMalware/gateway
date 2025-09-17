# API Gateway Service

## Features

- **Blacklist Filtering**: Block requests from specific IPs, user IDs, or API keys
- **Redis Integration**: Support for both Redis cluster and standalone configurations
- **Reactive Programming**: Built with Spring WebFlux and Kotlin Coroutines
- **Kubernetes Ready**: Includes deployment configurations for Kubernetes

## Requirements

- JDK 21
- Kotlin 2.2.10
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

---

# API 게이트웨이 서비스

## 기능

- **블랙리스트 필터링**: 특정 IP, 사용자 ID 또는 API 키의 요청 차단
- **Redis 통합**: Redis 클러스터 및 독립 실행형 구성 모두 지원
- **리액티브 프로그래밍**: Spring WebFlux 및 Kotlin 코루틴으로 구축
- **Kubernetes 지원**: Kubernetes 배포 구성 포함

## 요구 사항

- JDK 21
- Kotlin 2.2.10
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
