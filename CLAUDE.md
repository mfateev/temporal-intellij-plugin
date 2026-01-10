# Temporal IntelliJ Plugin

## Instructions

When asked a question, give an answer. Never jump to implementation or anything else unless there is explicit confirmation.

## Development Scripts

### Restart IDE Sandbox

Use `./restart-ide.sh` to quickly restart the IDE sandbox for testing plugin changes:

```bash
./restart-ide.sh
```

This script:
- Kills any running IDE instances
- Starts a new IDE sandbox in the background

## Build Commands

```bash
# Build the plugin
./gradlew build

# Run the IDE sandbox
./gradlew runIde

# Run unit tests (excludes UI tests)
./gradlew test
```

## UI Testing

For automated UI testing with Remote-Robot, see [docs/UI_TESTING.md](docs/UI_TESTING.md).

Quick reference:
```bash
# Terminal 1: Start IDE with Robot Server
./gradlew runIdeForUiTests

# Terminal 2: Run UI tests
./gradlew uiTest
```

## Project Structure

- `src/main/kotlin/io/temporal/intellij/` - Main plugin code
  - `toolwindow/` - Tool window components
  - `workflow/` - Workflow service and UI panels
  - `settings/` - Plugin settings
  - `codec/` - Codec server client
  - `actions/` - IDE actions
- `src/main/resources/` - Plugin resources and icons
