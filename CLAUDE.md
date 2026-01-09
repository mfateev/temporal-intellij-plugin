# Temporal IntelliJ Plugin

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

# Run tests
./gradlew test
```

## Project Structure

- `src/main/kotlin/io/temporal/intellij/` - Main plugin code
  - `toolwindow/` - Tool window components
  - `workflow/` - Workflow service and UI panels
  - `settings/` - Plugin settings
  - `codec/` - Codec server client
  - `actions/` - IDE actions
- `src/main/resources/` - Plugin resources and icons
