#!/bin/bash
# Restart the IntelliJ IDE sandbox for plugin development

echo "Stopping any running IDE instances..."
pkill -f "runIde" 2>/dev/null || true
sleep 2

echo "Starting IDE..."
./gradlew runIde &

echo "IDE starting in background. Check for the window to appear."
