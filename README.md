# Temporal IntelliJ Plugin

A JetBrains IDE plugin for inspecting and interacting with [Temporal](https://temporal.io) workflow executions.

## Features

- **Workflow Inspector** - Browse and inspect workflow executions in real-time
- **Event History** - View workflow event history with live updates via long polling
- **Query Workflows** - Execute queries against running workflows
- **Codec Server Support** - Decode encrypted payloads using a configured codec server
- **Connection Management** - Connect to Temporal Cloud or self-hosted clusters

## Installation

### From JetBrains Marketplace

*Coming soon*

### Build from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/mfateev/temporal-intellij-plugin.git
   cd temporal-intellij-plugin
   ```

2. Build the plugin:
   ```bash
   ./gradlew build
   ```

3. Install the plugin:
   - Go to **Settings/Preferences > Plugins > Gear icon > Install Plugin from Disk**
   - Select `build/distributions/temporal-intellij-plugin-*.zip`

## Configuration

1. Open **Settings/Preferences > Tools > Temporal**
2. Configure your Temporal connection:
   - **Address**: Temporal server address (e.g., `localhost:7233` or `your-namespace.tmprl.cloud:7233`)
   - **Namespace**: Your Temporal namespace
   - **TLS**: Enable for Temporal Cloud connections
   - **API Key**: Required for Temporal Cloud
   - **Codec Server**: Optional URL for payload decoding

3. Click **Test Connection** to verify settings

## Usage

### Workflow Inspector

1. Open the **Temporal** tool window (right sidebar)
2. Enter a Workflow ID and optionally a Run ID
3. Click **Refresh** to load the workflow

### Live Updates

- **Live updates** checkbox enables real-time event streaming via long polling
- When disabled, events continue buffering in the background
- Click **Refresh** to see buffered events as a snapshot

### Display Modes

| Mode | Behavior |
|------|----------|
| **Live** | Events appear in real-time as they occur |
| **Paused** | Events buffer silently, display frozen |
| **Frozen** | Snapshot view after manual refresh |

### Query Panel

1. Switch to the **Query** tab
2. Enter a query type name
3. Click **Execute** to run the query against the workflow

## Development

### Prerequisites

- JDK 17+
- IntelliJ IDEA (for development)

### Running the Plugin

```bash
# Start IDE sandbox with plugin
./gradlew runIde

# Or use the helper script
./restart-ide.sh
```

### Running Tests

```bash
./gradlew test
```

## Project Structure

```
src/main/kotlin/io/temporal/intellij/
├── actions/          # IDE actions
├── codec/            # Codec server client
├── settings/         # Plugin configuration
├── toolwindow/       # Tool window factory
└── workflow/         # Workflow inspection panels
    ├── EventHistoryTreePanel.kt
    ├── HistoryEventIterator.kt
    ├── QueryPanel.kt
    ├── WorkflowChooserDialog.kt
    ├── WorkflowInspectorPanel.kt
    └── WorkflowService.kt
```

## License

MIT License - see [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.
