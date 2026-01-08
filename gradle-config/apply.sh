#!/bin/bash

# Usage: ./scripts/setup-gradle.sh [force|common]

set -e

# define
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_CONFIG_DIR="$PROJECT_ROOT/gradle-config"
GRADLE_PROPERTIES="$PROJECT_ROOT/gradle.properties"

# log functions
log_info() {
    echo -e "${BLUE}INFO:  $1${NC}"
}

log_success() {
    echo -e "${GREEN}SUCCESS: $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}WARNING:  $1${NC}"
}

log_error() {
    echo -e "${RED}ERROR: $1${NC}"
}

# detect os function
detect_os() {
    case "$(uname -s)" in
        Darwin*)
            echo "macos"
            ;;
        Linux*)
            echo "linux"
            ;;
        CYGWIN*|MINGW*|MSYS*)
            echo "windows"
            ;;
        *)
            echo "unknown"
            ;;
    esac
}

# System
check_system_info() {
    local os_type="$1"

    log_info "Checking system information..."
    echo "  OS: $(uname -s)"
    echo "  Architecture: $(uname -m)"

    # Check memory
    case "$os_type" in
        "macos")
            local memory_gb=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))
            echo "  Memory: ${memory_gb}GB"
            if [ "$memory_gb" -lt 16 ]; then
                log_warning "Detected less than 16GB memory. Consider lowering memory settings."
            fi
            ;;
        "linux")
            local memory_gb=$(( $(grep MemTotal /proc/meminfo | awk '{print $2}') / 1024 / 1024 ))
            echo "  Memory: ${memory_gb}GB"
            if [ "$memory_gb" -lt 16 ]; then
                log_warning "Detected less than 16GB memory. Consider lowering memory settings."
            fi
            ;;
    esac

    # Check Java
    if command -v java &> /dev/null; then
        local java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
        echo "  Java: $java_version"
    else
        log_warning "Java is not installed"
    fi
}

# gradle.properties backup
backup_existing_config() {
    if [ -f "$GRADLE_PROPERTIES" ]; then
        local backup_file="${GRADLE_PROPERTIES}.backup.$(date +%Y%m%d_%H%M%S)"
        cp "$GRADLE_PROPERTIES" "$backup_file"
        log_info "Backed up existing gradle.properties: $(basename "$backup_file")"
    fi
}

# Apply OS-specific config
apply_os_config() {
    local os_type="$1"
    local force="$2"

    local config_file="$GRADLE_CONFIG_DIR/gradle-${os_type}.properties"

    if [ ! -f "$config_file" ]; then
        log_error "Configuration file not found: $config_file"
        exit 1
    fi

    if [ "$force" != "force" ] && [ -f "$GRADLE_PROPERTIES" ]; then
        backup_existing_config
    fi

    cp "$config_file" "$GRADLE_PROPERTIES"

    log_success "${os_type} optimized configuration applied!"
    log_info "Applied config file: gradle-${os_type}.properties"
}

# Apply common config
apply_common_config() {
    local config_file="$GRADLE_CONFIG_DIR/gradle-common.properties"

    if [ ! -f "$config_file" ]; then
        log_error "Common configuration file not found: $config_file"
        exit 1
    fi

    backup_existing_config
    cp "$config_file" "$GRADLE_PROPERTIES"

    log_success "Common configuration applied!"
    log_info "Safe default configuration for all OS."
}

# Verify config
verify_config() {
    log_info "Verifying configuration..."

    cd "$PROJECT_ROOT"

    if command -v ./gradlew &> /dev/null; then
        ./gradlew --stop &> /dev/null || true
    fi

    if [ -f "./gradlew" ] && ./gradlew help &> /dev/null; then
        log_success "Configuration applied correctly!"
    else
        log_error "Configuration has issues. Please check gradle.properties."
        return 1
    fi
}

# Show usage
show_usage() {
    echo "Gradle Properties OS Setup Tool"
    echo ""
    echo "Usage: $0 [option]"
    echo ""
    echo "Options:"
    echo "  (none)    Automatically apply OS-optimized configuration"
    echo "  force     Apply configuration without backing up existing file"
    echo "  common    Apply OS-independent common configuration"
    echo "  --help    Show this help message"
    echo ""
    echo "Supported OS:"
    echo "  macOS     gradle-macos.properties"
    echo "  Linux     gradle-linux.properties"
    echo "  Common    gradle-common.properties"
}

# Main function
main() {
    local option="$1"

    echo "Gradle Properties OS Setup Tool"
    echo "================================================"

    if [ "$option" = "--help" ] || [ "$option" = "-h" ]; then
        show_usage
        exit 0
    fi

    if [ ! -d "$GRADLE_CONFIG_DIR" ]; then
        log_error "gradle-config directory not found: $GRADLE_CONFIG_DIR"
        log_info "Please run this from the project root."
        exit 1
    fi

    if [ "$option" = "common" ]; then
        apply_common_config
        verify_config
        exit 0
    fi

    local detected_os=$(detect_os)

    if [ "$detected_os" = "unknown" ]; then
        log_warning "Unsupported OS detected. Applying common configuration."
        apply_common_config
    else
        log_info "Detected OS: $detected_os"
        check_system_info "$detected_os"
        echo ""
        apply_os_config "$detected_os" "$option"
    fi

    if verify_config; then
        echo ""
        echo "Setup complete!"
        echo "You can now run the build with:"
        echo "  ./gradlew clean build"
        echo ""
        echo "To change configuration:"
        echo "  - Apply other OS configuration: $0 common"
        echo "  - Edit manually: gradle.properties"
    fi
}

# Execute script
main "$@"
