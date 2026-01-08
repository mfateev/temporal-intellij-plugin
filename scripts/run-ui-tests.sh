#!/bin/bash
# Run UI tests for the Temporal IntelliJ Plugin
# This script starts the IDE with Robot Server and runs the UI tests

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

# Function to wait for IDE
wait_for_ide() {
    echo "Waiting for IDE to start (timeout: ${IDE_STARTUP_TIMEOUT}s)..."
    local elapsed=0
    while [ $elapsed -lt $IDE_STARTUP_TIMEOUT ]; do
        if check_ide_ready; then
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

# Check if IDE is already running
if check_ide_ready; then
    echo "IDE with Robot Server is already running on $ROBOT_URL"
else
    echo "Starting IDE with Robot Server..."
    ./gradlew runIdeForUiTests &
    IDE_PID=$!
    echo "IDE process started (PID: $IDE_PID)"

    # Wait for IDE to be ready
    if ! wait_for_ide; then
        echo "Failed to start IDE. Check logs."
        kill $IDE_PID 2>/dev/null || true
        exit 1
    fi
fi

echo ""
echo "Running UI tests..."
./gradlew test --tests "io.temporal.intellij.ui.*" --console=plain

echo ""
echo "=== Test run complete ==="
echo ""
echo "IDE is still running at $ROBOT_URL"
echo "You can inspect the UI hierarchy at: $ROBOT_URL"
echo "To stop the IDE, kill the Gradle process or press Ctrl+C in its terminal."
