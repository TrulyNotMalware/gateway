# OS-Specific Gradle Configuration

This directory contains OS-optimized Gradle configuration files and automated setup scripts for Java 25 + Kotlin 2.3.0 + Spring Boot 4 projects.

## Quick Start

### Automatic Setup
```bash
./apply.sh

# For cross-platform compatibility
./apply.sh common
```

### Manual Setup
Copy the appropriate configuration file to your project root as `gradle.properties`:
- `gradle-macos.properties` → macOS (Apple Silicon & Intel)
- `gradle-linux.properties` → Linux distributions
- `gradle-common.properties` → Universal compatibility

## Configuration Files

### gradle-macos.properties
**Key Features:**
- ZGC without Linux-specific options
- Apple Silicon optimizations
- File system watching enabled
- 6GB memory allocation

**Recommended System:** 16GB+ RAM

### gradle-linux.properties
**Key Features:**
- Maximum performance settings
- Transparent Huge Pages support
- Server-grade memory allocation (8GB)
- Enhanced parallel processing

**Recommended System:** 16GB+ RAM, server environment

### gradle-common.properties
**Optimized for:** Cross-platform compatibility
**Key Features:**
- Safe defaults for all operating systems
- 4GB memory allocation
- Basic optimizations only

**Recommended System:** 8GB+ RAM

## Advanced Usage

### Custom Memory Settings
Adjust memory allocation based on your system:

```properties
# For 8GB systems
org.gradle.jvmargs=-Xmx2g ...

# For 32GB+ systems
org.gradle.jvmargs=-Xmx8g ...
```

### Kotlin Daemon Optimization
Fine-tune Kotlin compilation performance:

```properties
-Dkotlin.daemon.jvm.options="-Xmx4g,-XX:+UseZGC,-Dkotlin.daemon.verbose=true"
```

## Troubleshooting

### Common Issues

**"UseTransparentHugePages" error on macOS:**
- Use `gradle-macos.properties`
- This option is Linux-only

**OutOfMemoryError:**
- Reduce `-Xmx` value in `org.gradle.jvmargs`
- Check available system memory

**Slow builds despite configuration:**
- Verify Configuration Cache is enabled
- Check if incremental compilation is working
- Run `./gradlew --stop` to restart daemon

### Verification Commands
```bash
# Check current configuration
./gradlew properties | grep org.gradle

# Verify Java toolchain
./gradlew javaToolchains

# Performance analysis
./gradlew build --profile
```

## Compatibility

### Supported Versions
- **Gradle:** 9.1.0+
- **Java:** 21 LTS+ (Eclipse Temurin recommended)
- **Kotlin:** 2.2.20+
- **Spring Boot:** 3.5.6+

### OS Support Matrix
| OS                        | File | Status | Notes |
|---------------------------|---|---|---|
| macOS | gradle-macos.properties | Full Support | Optimized for M1/M2/M3 |
| Linux                     | gradle-linux.properties | Full Support | Maximum performance |
| Other OS                  | gradle-common.properties | Basic Support | Safe defaults |
