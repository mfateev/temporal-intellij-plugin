#!/bin/bash
# Setup script for UI testing - clones samples-java as the test project

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_PROJECT_DIR="$SCRIPT_DIR/testProject"
SAMPLES_REPO="https://github.com/temporalio/samples-java.git"

if [ -d "$TEST_PROJECT_DIR" ]; then
    echo "testProject already exists at $TEST_PROJECT_DIR"
    echo "To refresh, delete it and run this script again:"
    echo "  rm -rf testProject && ./setup-test-project.sh"
    exit 0
fi

echo "Cloning samples-java as test project..."
git clone "$SAMPLES_REPO" "$TEST_PROJECT_DIR"

echo ""
echo "Test project setup complete!"
echo "You can now run UI tests:"
echo "  Terminal 1: ./gradlew runIdeForUiTests"
echo "  Terminal 2: ./gradlew uiTest"
