package io.temporal.intellij.replay.runconfig

import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.OrderEnumerator
import io.temporal.intellij.replay.ReplayStatusServer
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Run state that executes the workflow replay using the user's project classpath.
 *
 * IMPORTANT: The user's project must have `io.temporal:temporal-testing` as a test dependency
 * for replay to work. This is intentional - we use the project's version to avoid
 * version conflicts between the plugin and the project's temporal-sdk.
 */
class WorkflowReplayRunState(
    private val configuration: WorkflowReplayRunConfiguration,
    environment: ExecutionEnvironment
) : JavaCommandLineState(environment) {

    companion object {
        private const val RUNNER_CLASS = "io.temporal.intellij.replay.WorkflowReplayRunner"
        private const val PORT_PROPERTY = "temporal.replay.status.port"
        private const val TOKEN_PROPERTY = "temporal.replay.status.token"
        private val RUNNER_CLASSES = listOf(
            "io/temporal/intellij/replay/WorkflowReplayRunner.class",
            "io/temporal/intellij/replay/DebugReplayMarker.class"
        )
    }

    private var statusServer: ReplayStatusServer? = null

    override fun startProcess(): OSProcessHandler {
        val handler = super.startProcess()

        // Clean up status server when process terminates
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                statusServer?.stop()
                statusServer = null
            }
        })

        return handler
    }

    override fun createJavaParameters(): JavaParameters {
        val params = JavaParameters()

        val module = configuration.configurationModule.module
            ?: throw IllegalStateException("No module selected for workflow replay. " +
                "Please select a module in the run configuration settings.")

        // Set up JDK
        params.configureByModule(module, JavaParameters.JDK_ONLY)

        // For Gradle projects, IntelliJ creates separate modules for main and test source sets.
        // Test dependencies (like temporal-testing) are only on the test module.
        // Try to find the corresponding test module to include test dependencies.
        val testModule = findTestModule(module)
        val moduleForClasspath = testModule ?: module

        // Get classpath from the appropriate module
        val classpath = OrderEnumerator.orderEntries(moduleForClasspath)
            .withoutSdk()
            .recursively()
            .classes()
            .pathsList
        params.classPath.addAll(classpath.pathList)

        // Log classpath info for debugging
        val classpathSize = params.classPath.pathList.size
        if (classpathSize == 0) {
            throw IllegalStateException(
                "Module '${module.name}' has no classpath entries. " +
                "Make sure the project is compiled and the module is properly configured."
            )
        }

        // Extract and add the replay runner class to the classpath
        val runnerDir = extractRunnerClass()
        params.classPath.add(runnerDir.absolutePath)

        // Main class - the replay runner
        params.mainClass = RUNNER_CLASS

        // Generate a random token for handshake validation
        val token = UUID.randomUUID().toString()

        // Start status server for replay progress reporting
        statusServer = ReplayStatusServer(environment.project, token)
        val port = statusServer!!.start()

        // Pass the status server port and token as system properties
        params.vmParametersList.add("-D$PORT_PROPERTY=$port")
        params.vmParametersList.add("-D$TOKEN_PROPERTY=$token")

        // Pass arguments
        params.programParametersList.add("--workflow-class")
        params.programParametersList.add(configuration.workflowClassName)

        params.programParametersList.add("--history-file")
        params.programParametersList.add(configuration.historyFilePath)

        return params
    }

    /**
     * Find the test module corresponding to the given module.
     * For Gradle projects, IntelliJ creates separate modules:
     * - project.main (or just project) - main source set
     * - project.test - test source set with testImplementation dependencies
     */
    private fun findTestModule(module: Module): Module? {
        val project = module.project
        val moduleManager = ModuleManager.getInstance(project)
        val moduleName = module.name

        // Try different naming patterns for test modules
        val testModuleNames = listOf(
            // Gradle pattern: project.main -> project.test
            moduleName.replace(".main", ".test"),
            // If module doesn't end with .main, try appending .test
            "$moduleName.test",
            // Some projects use _test suffix
            "${moduleName}_test"
        )

        for (testName in testModuleNames) {
            if (testName != moduleName) {
                val testModule = moduleManager.findModuleByName(testName)
                if (testModule != null) {
                    return testModule
                }
            }
        }

        return null
    }

    /**
     * Extract the replay runner classes from the plugin to a temp directory.
     * This allows the runner to be executed with the user's project classpath
     * without requiring the entire plugin JAR.
     */
    private fun extractRunnerClass(): File {
        // Create temp directory for extracted classes
        val tempDir = Files.createTempDirectory("temporal-replay-runner").toFile()
        tempDir.deleteOnExit()

        // Create package directory structure
        val packageDir = File(tempDir, "io/temporal/intellij/replay")
        packageDir.mkdirs()

        // Extract all required class files from plugin resources
        for (classPath in RUNNER_CLASSES) {
            val classStream = this::class.java.classLoader.getResourceAsStream(classPath)
                ?: throw IllegalStateException("Could not find $classPath in plugin resources")

            val className = classPath.substringAfterLast("/")
            val targetFile = File(packageDir, className)
            classStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.deleteOnExit()
        }

        return tempDir
    }
}
