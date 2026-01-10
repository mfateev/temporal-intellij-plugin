package io.temporal.intellij.replay.runconfig

import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.io.File
import java.nio.file.Files

/**
 * Run state that executes the workflow replay using the user's project classpath.
 */
class WorkflowReplayRunState(
    private val configuration: WorkflowReplayRunConfiguration,
    environment: ExecutionEnvironment
) : JavaCommandLineState(environment) {

    companion object {
        private const val RUNNER_CLASS = "io.temporal.intellij.replay.WorkflowReplayRunner"
        private const val RUNNER_CLASS_PATH = "io/temporal/intellij/replay/WorkflowReplayRunner.class"
        private const val PLUGIN_ID = "io.temporal.intellij"
    }

    override fun createJavaParameters(): JavaParameters {
        val params = JavaParameters()

        val module = configuration.configurationModule.module
            ?: throw IllegalStateException("No module selected for workflow replay. " +
                "Please select a module in the run configuration settings.")

        // Set up classpath from module (includes project classes + dependencies)
        params.configureByModule(module, JavaParameters.JDK_AND_CLASSES_AND_TESTS)

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

        // Add temporal-testing JAR from the plugin (needed for WorkflowReplayer)
        addTemporalTestingJar(params)

        // Main class - the replay runner
        params.mainClass = RUNNER_CLASS

        // Pass arguments
        params.programParametersList.add("--workflow-class")
        params.programParametersList.add(configuration.workflowClassName)

        params.programParametersList.add("--history-file")
        params.programParametersList.add(configuration.historyFilePath)

        return params
    }

    /**
     * Extract the WorkflowReplayRunner.class from the plugin to a temp directory.
     * This allows the runner to be executed with the user's project classpath
     * without requiring the entire plugin JAR.
     */
    private fun extractRunnerClass(): File {
        // Create temp directory for extracted class
        val tempDir = Files.createTempDirectory("temporal-replay-runner").toFile()
        tempDir.deleteOnExit()

        // Create package directory structure
        val packageDir = File(tempDir, "io/temporal/intellij/replay")
        packageDir.mkdirs()

        // Extract the class file from plugin resources
        val classStream = this::class.java.classLoader.getResourceAsStream(RUNNER_CLASS_PATH)
            ?: throw IllegalStateException("Could not find WorkflowReplayRunner.class in plugin resources")

        val targetFile = File(packageDir, "WorkflowReplayRunner.class")
        classStream.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        targetFile.deleteOnExit()

        return tempDir
    }

    /**
     * Add the temporal-testing and temporal-test-server JARs from the plugin to the classpath.
     * This provides the WorkflowReplayer class needed for replay functionality.
     * The user's project may only have temporal-testing as a test dependency,
     * so we include it from the plugin to ensure it's always available.
     */
    private fun addTemporalTestingJar(params: JavaParameters) {
        val pluginId = PluginId.getId(PLUGIN_ID)
        val plugin = PluginManagerCore.getPlugin(pluginId) ?: return

        val pluginPath = plugin.pluginPath ?: return
        val pluginDir = pluginPath.toFile()

        // Look for temporal-testing and temporal-test-server JARs in the plugin's lib directory
        val libDir = File(pluginDir, "lib")
        if (libDir.exists() && libDir.isDirectory) {
            libDir.listFiles()?.filter {
                (it.name.startsWith("temporal-testing") || it.name.startsWith("temporal-test-server"))
                    && it.extension == "jar"
            }?.forEach { jar ->
                params.classPath.add(jar.absolutePath)
            }
        }
    }
}
