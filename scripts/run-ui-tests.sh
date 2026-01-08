#!/bin/bash
# Run UI tests for the Temporal IntelliJ Plugin
# This script starts the IDE with Robot Server and runs the UI tests
#
# Usage:
#   ./scripts/run-ui-tests.sh           # Start IDE if not running, then run tests
#   ./scripts/run-ui-tests.sh --restart # Restart IDE (rebuild plugin), then run tests
#   ./scripts/run-ui-tests.sh --stop    # Stop the running IDE

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ROBOT_PORT=8082
ROBOT_URL="http://127.0.0.1:$ROBOT_PORT"
IDE_STARTUP_TIMEOUT=120  # seconds

cd "$PROJECT_DIR"

echo "=== Temporal Plugin UI Test Runner ==="
echo "Project: $PROJECT_DIR"
echo ""

# Function to check if IDE is ready
check_ide_ready() {
    curl -s "$ROBOT_URL" > /dev/null 2>&1
}

# Function to stop the IDE
stop_ide() {
    echo "Stopping IDE..."
    # Kill any running runIdeForUiTests gradle processes
    pkill -f "runIdeForUiTests" 2>/dev/null || true
    # Also try to kill the IDE process directly
    pkill -f "idea.*robot-server" 2>/dev/null || true
    sleep 2

    if check_ide_ready; then
        echo "Warning: IDE may still be running. Trying harder..."
        pkill -9 -f "runIdeForUiTests" 2>/dev/null || true
        pkill -9 -f "idea" 2>/dev/null || true
        sleep 2
    fi

    if check_ide_ready; then
        echo "ERROR: Could not stop IDE"
        return 1
    fi
    echo "IDE stopped."
    return 0
}

# Function to start the IDE
start_ide() {
    echo "Starting IDE with Robot Server..."

    # Create log directory
    LOG_DIR="$PROJECT_DIR/build/logs"
    mkdir -p "$LOG_DIR"
    IDE_LOG="$LOG_DIR/ide-ui-test.log"

    # Start IDE in background, redirect output to log file
    nohup ./gradlew runIdeForUiTests > "$IDE_LOG" 2>&1 &
    IDE_PID=$!
    disown $IDE_PID
    echo "IDE process started (PID: $IDE_PID)"
    echo "IDE logs: $IDE_LOG"

    # Wait for IDE to be ready
    if ! wait_for_ide; then
        echo "Failed to start IDE. Check logs at: $IDE_LOG"
        kill $IDE_PID 2>/dev/null || true
        return 1
    fi
    return 0
}

# Function to wait for IDE
wait_for_ide() {
    echo "Waiting for IDE to start (timeout: ${IDE_STARTUP_TIMEOUT}s)..."
    local elapsed=0
    while [ $elapsed -lt $IDE_STARTUP_TIMEOUT ]; do
        if check_ide_ready; then
            echo ""
            echo "IDE is ready!"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
        echo -n "."
    done
    echo ""
    echo "ERROR: IDE did not start within ${IDE_STARTUP_TIMEOUT}s"
    return 1
}

# Parse arguments
RESTART=false
STOP_ONLY=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --restart|-r)
            RESTART=true
            shift
            ;;
        --stop|-s)
            STOP_ONLY=true
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --restart, -r  Restart IDE before running tests (rebuilds plugin)"
            echo "  --stop, -s     Stop the running IDE and exit"
            echo "  --help, -h     Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Handle --stop
if [ "$STOP_ONLY" = true ]; then
    if check_ide_ready; then
        stop_ide
    else
        echo "IDE is not running."
    fi
    exit 0
fi

# Handle --restart
if [ "$RESTART" = true ]; then
    if check_ide_ready; then
        stop_ide
    fi
    start_ide || exit 1
elif check_ide_ready; then
    echo "IDE with Robot Server is already running on $ROBOT_URL"
else
    start_ide || exit 1
fi

echo ""
echo "Running UI tests..."
./gradlew uiTest --console=plain

echo ""
echo "=== Test run complete ==="
echo ""
echo "IDE is still running at $ROBOT_URL"
echo "You can inspect the UI hierarchy at: $ROBOT_URL"
echo "To stop: ./scripts/run-ui-tests.sh --stop"
