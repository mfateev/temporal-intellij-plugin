# Automated UI Testing

This plugin uses **JetBrains Remote-Robot** (v0.11.23) for automated UI testing. Tests run in a separate process and communicate with the IDE via HTTP on port 8082.

## Prerequisites

Before running UI tests, you need to set up the test project:

```bash
# Clone samples-java as the test project (if not already present)
./setup-test-project.sh
```

Or manually:
```bash
git clone https://github.com/temporalio/samples-java.git testProject
```

The test project must have `temporal-testing` as a dependency (already included in samples-java).

## Architecture

```
┌─────────────────────┐         HTTP/REST         ┌─────────────────────┐
│   Test Process      │◄────────────────────────►│   IDE Process       │
│   (./gradlew uiTest)│      port 8082           │   (runIdeForUiTests)│
│                     │                           │                     │
│  - JUnit 5 tests    │    ←── Find elements ──   │  - Robot Server     │
│  - RemoteRobot API  │    ←── Click/type ─────   │  - Plugin loaded    │
│  - Assertions       │    ←── Read state ─────   │  - Test project     │
└─────────────────────┘                           └─────────────────────┘
```

## Running UI Tests (Two Terminals Required)

**Terminal 1 - Start IDE with Robot Server:**
```bash
./gradlew runIdeForUiTests
```
Wait until IDE fully loads and shows the test project.

**Terminal 2 - Run UI Tests:**
```bash
./gradlew uiTest
```

## Important Notes

- **Tests FAIL (not skip)** if IDE isn't running - you'll see a clear error message
- UI tests are **excluded** from `./gradlew test` - use `./gradlew uiTest` specifically
- The IDE opens `testProject/` automatically for testing context
- Tests have a 60-second timeout for IDE interactions

## Test Files

| File | Purpose |
|------|---------|
| `src/test/kotlin/io/temporal/intellij/ui/BaseUiTest.kt` | Base class, waits for IDE connection |
| `src/test/kotlin/io/temporal/intellij/ui/SharedIdeManager.kt` | Singleton managing RemoteRobot connection |
| `src/test/kotlin/io/temporal/intellij/ui/TemporalToolWindowFixture.kt` | Custom fixture for Temporal UI elements |
| `src/test/kotlin/io/temporal/intellij/ui/TemporalToolWindowTest.kt` | Tool window test cases |

## Writing New UI Tests

1. Extend `BaseUiTest` to get IDE connection handling
2. Use `remoteRobot.ideFrame()` to get the main IDE window
3. Find elements with XPath: `byXpath("//div[@class='JButton' and @text='Click Me']")`
4. Create fixtures in `TemporalToolWindowFixture.kt` for reusable element patterns

Example:
```kotlin
class MyTest : BaseUiTest() {
    @Test
    fun `test button click`() {
        val ideFrame = remoteRobot.ideFrame()
        val toolWindow = ideFrame.openTemporalToolWindow()
        toolWindow.settingsButton.click()
        // assertions...
    }
}
```

## Gradle Configuration

The build is configured with separate tasks for unit tests and UI tests:

```kotlin
// UI tests are EXCLUDED from regular ./gradlew test
tasks.test {
    exclude("**/ui/**")
}

// Dedicated task for UI tests
val uiTest by tasks.registering(Test::class) {
    include("**/ui/**")
    systemProperty("robot.server.port", "8082")
    // JDK17 fix for GSON/Retrofit
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// IDE launcher with Robot Server
val runIdeForUiTests by intellijPlatformTesting.runIde.registering {
    plugins { robotServerPlugin() }
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Drobot-server.port=8082", ...)
        }
    }
}
```

## Troubleshooting

### Tests fail with "IDE IS NOT RUNNING"
Start the IDE first: `./gradlew runIdeForUiTests`

### Connection timeout
- Ensure port 8082 is not blocked
- Wait for IDE to fully initialize before running tests
- Check if another IDE instance is using the port

### Element not found
- Use `IdeInspector` to debug the UI hierarchy
- XPath locators may need adjustment for different IDE versions
- Increase timeout in `Duration.ofSeconds()` calls
