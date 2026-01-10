# Design: Workflow Replay Feature

## Overview

Add support for replaying workflow executions using the `WorkflowReplayer` class from Temporal SDK. This allows developers to replay a workflow's history against their local implementation to verify compatibility and debug non-determinism issues.

## User Requirements

1. **Workflow class discovery**: Scan project classpath for `@WorkflowInterface` implementations, filter by workflow type name from history. If multiple matches, let user choose.
2. **History source**: Support both server download (from currently inspected workflow) AND JSON file import
3. **Results display**: Output to IntelliJ's Run tool window (like test output)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    WorkflowInspectorPanel                        │
│  [Replay] button  [Import JSON...] button                        │
└─────────────┬───────────────────────────┬───────────────────────┘
              │                           │
              ▼                           ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│   WorkflowReplayService │   │    HistoryExporter      │
│   - Orchestrates replay │   │    - Export to JSON     │
│   - Creates run config  │   │    - Load from file     │
└─────────────┬───────────┘   └───────────────────────────┘
              │
              ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│  WorkflowClassFinder    │   │ WorkflowClassChooser    │
│  - PSI-based discovery  │──▶│   Dialog (if multiple)  │
│  - Filter by type name  │   └─────────────────────────┘
└─────────────┬───────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────────┐
│              WorkflowReplayRunConfiguration                      │
│  - IntelliJ Run Configuration                                    │
│  - Executes WorkflowReplayRunner with project classpath          │
└─────────────┬───────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Run Tool Window                               │
│  - Shows replay progress                                         │
│  - Success/failure output                                        │
│  - NonDeterministicException details                             │
└─────────────────────────────────────────────────────────────────┘
```

## WorkflowReplayer API (from Temporal SDK)

The `WorkflowReplayer` class (in `io.temporal:temporal-testing`) provides these key methods:

```java
// From JSON string
public static void replayWorkflowExecution(
    String jsonSerializedHistory,
    Class<?> workflowClass,
    Class<?>... moreWorkflowClasses) throws Exception

// From file
public static void replayWorkflowExecution(
    File historyFile,
    Class<?> workflowClass,
    Class<?>... moreWorkflowClasses) throws Exception

// Batch replay with error collection
public static ReplayResults replayWorkflowExecutions(
    Iterable<WorkflowExecutionHistory> histories,
    boolean failFast,
    Class<?>... workflowClasses) throws Exception
```

**Key Points**:
- Requires workflow implementation class on classpath
- History must be in JSON format (Temporal CLI format)
- Throws `NonDeterministicException` if replay fails due to code changes
- Task queue is automatically extracted from history's first event

## File Structure

### New Files to Create

```
src/main/kotlin/io/temporal/intellij/replay/
├── WorkflowReplayService.kt           # Orchestrates replay operations
├── WorkflowClassFinder.kt             # PSI-based @WorkflowInterface discovery
├── WorkflowClassChooserDialog.kt      # UI for selecting workflow impl
├── HistoryExporter.kt                 # Export/import history JSON
└── runconfig/
    ├── WorkflowReplayRunConfigurationType.kt
    ├── WorkflowReplayConfigurationFactory.kt
    ├── WorkflowReplayRunConfiguration.kt
    ├── WorkflowReplayRunConfigurationOptions.kt
    ├── WorkflowReplaySettingsEditor.kt
    └── WorkflowReplayRunState.kt

src/main/java/io/temporal/intellij/replay/
└── WorkflowReplayRunner.java          # Entry point executed with project classpath
```

### Files to Modify

| File | Changes |
|------|---------|
| `build.gradle.kts` | Add `temporal-testing:1.27.0` dependency |
| `WorkflowService.kt` | Add `getRawHistory()` method for JSON export |
| `WorkflowInspectorPanel.kt` | Add Replay and Import JSON buttons |
| `plugin.xml` | Register run configuration type, add Java module dependency |

## Implementation Phases

### Phase 1: Dependencies and History Export

**Goal**: Enable exporting workflow history to JSON format

**Tasks**:

1. **Add dependency to `build.gradle.kts`**:
   ```kotlin
   dependencies {
       implementation("io.temporal:temporal-sdk:1.27.0")
       implementation("io.temporal:temporal-testing:1.27.0")  // NEW
   }
   ```

2. **Add `getRawHistory()` to `WorkflowService.kt`**:
   ```kotlin
   fun getRawHistory(workflowId: String, runId: String? = null): Result<History> {
       val stub = this.stub ?: return Result.failure(IllegalStateException("Not connected"))

       return try {
           val allEvents = mutableListOf<HistoryEvent>()
           var nextPageToken = ByteString.EMPTY

           do {
               val request = GetWorkflowExecutionHistoryRequest.newBuilder()
                   .setNamespace(settings.namespace)
                   .setExecution(WorkflowExecution.newBuilder()
                       .setWorkflowId(workflowId)
                       .apply { if (!runId.isNullOrEmpty()) setRunId(runId) }
                       .build())
                   .setMaximumPageSize(1000)
                   .setNextPageToken(nextPageToken)
                   .build()

               val response = stub.getWorkflowExecutionHistory(request)
               allEvents.addAll(response.history.eventsList)
               nextPageToken = response.nextPageToken
           } while (!nextPageToken.isEmpty)

           Result.success(History.newBuilder().addAllEvents(allEvents).build())
       } catch (e: Exception) {
           Result.failure(e)
       }
   }
   ```

3. **Create `HistoryExporter.kt`**:
   ```kotlin
   class HistoryExporter {
       fun exportToJson(
           workflowService: WorkflowService,
           workflowId: String,
           runId: String?,
           namespace: String
       ): Result<String> {
           return workflowService.getRawHistory(workflowId, runId).map { history ->
               JsonFormat.printer()
                   .includingDefaultValueFields()
                   .preservingProtoFieldNames()
                   .print(history)
           }
       }

       fun exportToFile(...): Result<File> {
           return exportToJson(...).map { json ->
               File.createTempFile("temporal-history-", ".json").apply {
                   deleteOnExit()
                   writeText(json)
               }
           }
       }

       fun loadFromFile(file: File): Result<String> = runCatching { file.readText() }

       fun extractWorkflowType(historyJson: String): String? {
           val regex = """"workflowType"\s*:\s*\{\s*"name"\s*:\s*"([^"]+)"""".toRegex()
           return regex.find(historyJson)?.groupValues?.get(1)
       }
   }
   ```

### Phase 2: Workflow Class Discovery

**Goal**: Find workflow implementations in user's project using IntelliJ PSI

**Tasks**:

1. **Create `WorkflowClassFinder.kt`**:
   ```kotlin
   class WorkflowClassFinder(private val project: Project) {

       data class WorkflowImplementation(
           val psiClass: PsiClass,
           val qualifiedName: String,
           val workflowTypeName: String,
           val interfaceClass: PsiClass
       )

       fun findByWorkflowType(workflowTypeName: String): List<WorkflowImplementation> {
           val facade = JavaPsiFacade.getInstance(project)
           val scope = GlobalSearchScope.projectScope(project)

           // Find @WorkflowInterface annotation
           val annotation = facade.findClass(
               "io.temporal.workflow.WorkflowInterface",
               GlobalSearchScope.allScope(project)
           ) ?: return emptyList()

           val implementations = mutableListOf<WorkflowImplementation>()

           // Find all interfaces annotated with @WorkflowInterface
           for (workflowInterface in AnnotatedElementsSearch.searchPsiClasses(annotation, scope)) {
               if (!workflowInterface.isInterface) continue

               val typeName = extractWorkflowTypeName(workflowInterface)
               if (typeName != workflowTypeName) continue

               // Find implementations
               for (impl in ClassInheritorsSearch.search(workflowInterface, scope, true)) {
                   if (impl.isInterface) continue
                   implementations.add(WorkflowImplementation(
                       impl, impl.qualifiedName!!, typeName, workflowInterface
                   ))
               }
           }
           return implementations
       }

       private fun extractWorkflowTypeName(workflowInterface: PsiClass): String {
           // Check @WorkflowMethod for custom name
           for (method in workflowInterface.methods) {
               val annotation = method.getAnnotation("io.temporal.workflow.WorkflowMethod")
               val nameAttr = annotation?.findAttributeValue("name")
               val customName = nameAttr?.text?.trim('"')
               if (!customName.isNullOrEmpty()) return customName
           }
           return workflowInterface.name ?: "Unknown"
       }
   }
   ```

2. **Create `WorkflowClassChooserDialog.kt`**:
   - Show list of matching implementations when multiple exist
   - Display class name and fully qualified name
   - Double-click or OK button to select

### Phase 3: Run Configuration

**Goal**: Execute replay in user's project context with proper classpath

**Tasks**:

1. **Create run configuration classes**:

   **WorkflowReplayRunConfigurationType.kt**:
   ```kotlin
   class WorkflowReplayRunConfigurationType : ConfigurationTypeBase(
       "TemporalWorkflowReplay",
       "Temporal Workflow Replay",
       "Replay a Temporal workflow execution against local implementation",
       NotNullLazyValue.createValue { TemporalIcons.TEMPORAL_16 }
   ) {
       init {
           addFactory(WorkflowReplayConfigurationFactory(this))
       }
   }
   ```

   **WorkflowReplayRunConfigurationOptions.kt**:
   ```kotlin
   class WorkflowReplayRunConfigurationOptions : ModuleBasedConfigurationOptions() {
       // History source: "server" or "file"
       var historySource: String = "server"
       var workflowId: String = ""
       var runId: String = ""
       var historyFilePath: String = ""
       var workflowClassName: String = ""
       var additionalWorkflowClasses: List<String> = emptyList()
   }
   ```

   **WorkflowReplayRunState.kt**:
   ```kotlin
   class WorkflowReplayRunState(
       private val configuration: WorkflowReplayRunConfiguration,
       environment: ExecutionEnvironment
   ) : JavaCommandLineState(environment) {

       override fun createJavaParameters(): JavaParameters {
           val params = JavaParameters()
           val module = configuration.configurationModule.module
               ?: throw IllegalStateException("No module selected")

           // Set up classpath from module (includes project classes + dependencies)
           params.configureByModule(module, JavaParameters.JDK_AND_CLASSES_AND_TESTS)

           // Main class
           params.mainClass = "io.temporal.intellij.replay.WorkflowReplayRunner"

           // Arguments
           params.programParametersList.add("--workflow-class", configuration.workflowClassName)
           params.programParametersList.add("--history-file", configuration.historyFilePath)

           return params
       }
   }
   ```

2. **Create `WorkflowReplayRunner.java`** (must be Java for runtime loading):
   ```java
   public class WorkflowReplayRunner {
       public static void main(String[] args) throws Exception {
           String workflowClassName = null;
           String historyFile = null;
           List<String> additionalClasses = new ArrayList<>();

           // Parse arguments
           for (int i = 0; i < args.length; i++) {
               switch (args[i]) {
                   case "--workflow-class": workflowClassName = args[++i]; break;
                   case "--history-file": historyFile = args[++i]; break;
                   case "--additional-class": additionalClasses.add(args[++i]); break;
               }
           }

           // Load classes
           Class<?> workflowClass = Class.forName(workflowClassName);
           Class<?>[] additional = additionalClasses.stream()
               .map(name -> { try { return Class.forName(name); } catch (Exception e) { throw new RuntimeException(e); } })
               .toArray(Class[]::new);

           // Load history
           String historyJson = Files.readString(Path.of(historyFile));

           System.out.println("=".repeat(60));
           System.out.println("Temporal Workflow Replay");
           System.out.println("=".repeat(60));
           System.out.println("Workflow Class: " + workflowClassName);

           try {
               long start = System.currentTimeMillis();
               WorkflowReplayer.replayWorkflowExecution(historyJson, workflowClass, additional);
               long duration = System.currentTimeMillis() - start;

               System.out.println();
               System.out.println("REPLAY SUCCESSFUL (" + duration + "ms)");
           } catch (Exception e) {
               System.err.println();
               System.err.println("REPLAY FAILED");
               System.err.println("Error: " + e.getMessage());
               e.printStackTrace();
               System.exit(1);
           }
       }
   }
   ```

3. **Create `WorkflowReplaySettingsEditor.kt`**:
   - Radio buttons for history source (Server / File)
   - Workflow ID and Run ID fields for server source
   - File chooser for file source
   - Workflow class field (with browse button for class selection)

### Phase 4: UI Integration

**Goal**: Add replay buttons to the inspector panel

**Tasks**:

1. **Modify `WorkflowInspectorPanel.kt`**:

   Add buttons to input panel:
   ```kotlin
   private val replayButton = JButton("Replay").apply {
       toolTipText = "Replay this workflow against local implementation"
       isEnabled = false
       addActionListener { startReplay() }
   }

   private val importHistoryButton = JButton("Import JSON...").apply {
       toolTipText = "Import workflow history from JSON file"
       addActionListener { importHistoryFile() }
   }
   ```

   Add replay logic:
   ```kotlin
   private fun startReplay() {
       val workflowId = currentWorkflowId ?: return
       val runId = currentRunId
       val service = workflowService ?: return

       // Get workflow type from loaded events
       val workflowType = cachedEvents.firstOrNull()?.details?.get("workflowType") ?: return

       // Find matching implementations
       val finder = WorkflowClassFinder(project)
       val implementations = finder.findByWorkflowType(workflowType)

       when {
           implementations.isEmpty() -> {
               showError("No workflow implementation found for type: $workflowType")
           }
           implementations.size == 1 -> {
               launchReplay(implementations.first(), workflowId, runId)
           }
           else -> {
               val dialog = WorkflowClassChooserDialog(project, implementations, workflowType)
               if (dialog.showAndGet()) {
                   dialog.selectedImplementation?.let { launchReplay(it, workflowId, runId) }
               }
           }
       }
   }

   private fun launchReplay(impl: WorkflowClassFinder.WorkflowImplementation, workflowId: String, runId: String?) {
       WorkflowReplayService(project).replayFromServer(workflowService!!, workflowId, runId, impl.qualifiedName)
   }
   ```

2. **Create `WorkflowReplayService.kt`**:
   ```kotlin
   class WorkflowReplayService(private val project: Project) {

       fun replayFromServer(
           workflowService: WorkflowService,
           workflowId: String,
           runId: String?,
           workflowClassName: String
       ) {
           ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Workflow Replay") {
               override fun run(indicator: ProgressIndicator) {
                   indicator.text = "Exporting workflow history..."

                   val exporter = HistoryExporter()
                   val file = exporter.exportToFile(workflowService, workflowId, runId, workflowService.settings.namespace)
                       .getOrElse { throw it }

                   ApplicationManager.getApplication().invokeLater {
                       createAndRunConfiguration(
                           historyFilePath = file.absolutePath,
                           workflowClassName = workflowClassName,
                           configName = "Replay: $workflowId"
                       )
                   }
               }
           })
       }

       fun replayFromFile(historyFilePath: String) {
           val exporter = HistoryExporter()
           val historyJson = exporter.loadFromFile(File(historyFilePath)).getOrNull() ?: return
           val workflowType = exporter.extractWorkflowType(historyJson) ?: return

           val finder = WorkflowClassFinder(project)
           val implementations = finder.findByWorkflowType(workflowType)

           // Show chooser if multiple, then run
           ...
       }

       private fun createAndRunConfiguration(historyFilePath: String, workflowClassName: String, configName: String) {
           val runManager = RunManager.getInstance(project)
           val type = WorkflowReplayRunConfigurationType()
           val factory = type.configurationFactories.first()

           val settings = runManager.createConfiguration(configName, factory)
           val config = settings.configuration as WorkflowReplayRunConfiguration

           config.historySource = "file"
           config.historyFilePath = historyFilePath
           config.workflowClassName = workflowClassName

           runManager.addConfiguration(settings)
           runManager.selectedConfiguration = settings

           ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
       }
   }
   ```

3. **Update `plugin.xml`**:
   ```xml
   <!-- Add Java module dependency -->
   <depends>com.intellij.modules.java</depends>

   <extensions defaultExtensionNs="com.intellij">
       <!-- Register run configuration -->
       <configurationType
           implementation="io.temporal.intellij.replay.runconfig.WorkflowReplayRunConfigurationType"/>
   </extensions>
   ```

## User Flow

### Replay from Server (Current Workflow)

1. User inspects a workflow in the Temporal tool window
2. Clicks **[Replay]** button
3. Plugin extracts workflow type from history
4. Plugin searches project for matching `@WorkflowInterface` implementations
5. If multiple matches: shows chooser dialog
6. Plugin exports history to temp JSON file
7. Creates and runs `WorkflowReplayRunConfiguration`
8. Run tool window shows output:
   ```
   ============================================================
   Temporal Workflow Replay
   ============================================================
   Workflow Class: com.example.MyWorkflowImpl

   REPLAY SUCCESSFUL (127ms)
   ```
   Or on failure:
   ```
   REPLAY FAILED
   Error: [TMPRL1100] Nondeterministic error: Activity task scheduled...
   io.temporal.worker.NonDeterministicException: ...
   ```

### Replay from JSON File

1. User clicks **[Import JSON...]** button
2. File chooser opens for `.json` files
3. Plugin reads file, extracts workflow type
4. Same flow as above from step 4

## Verification

### Manual Testing

1. **Test Replay Success**:
   - Run a simple workflow to completion
   - Inspect it in the plugin
   - Click Replay
   - Verify: "REPLAY SUCCESSFUL" in Run tool window

2. **Test Non-Determinism Detection**:
   - Run workflow
   - Modify workflow implementation (add/remove activity)
   - Replay
   - Verify: "REPLAY FAILED" with `NonDeterministicException`

3. **Test Multiple Implementations**:
   - Create two implementations of same interface
   - Replay
   - Verify: Chooser dialog appears

4. **Test File Import**:
   - Export history: `temporal workflow show -w <id> -o json > history.json`
   - Import in plugin
   - Verify: Replay runs successfully

### Error Cases to Handle

| Scenario | Expected Behavior |
|----------|-------------------|
| No workflow implementations found | Error dialog: "No workflow implementation found for type: X" |
| Project not built (no .class files) | Error in Run window: `ClassNotFoundException` |
| History file invalid JSON | Error dialog: "Invalid history file format" |
| Server connection lost during export | Error dialog with connection error |

## Dependencies

- `io.temporal:temporal-testing:1.27.0` - Contains `WorkflowReplayer`, `WorkflowHistoryLoader`
- `com.intellij.modules.java` - For PSI access to Java/Kotlin classes

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Project classes not compiled | Show clear error message prompting user to build |
| temporal-testing version mismatch with user's SDK | Document that plugin uses 1.27.0; consider making configurable |
| Very large history files (>100MB) | Consider streaming or pagination; show progress indicator |
| Kotlin workflow implementations | Test PSI search works for Kotlin classes too |

## Future Enhancements

1. **Batch replay**: Replay multiple workflows from a list
2. **Replay from event**: Start replay from specific history event
3. **Diff view**: Show comparison between expected and actual commands on failure
4. **Save replay configurations**: Persist for re-running
