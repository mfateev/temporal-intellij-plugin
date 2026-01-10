package io.temporal.intellij.replay

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import io.temporal.api.history.v1.History
import io.temporal.intellij.replay.runconfig.WorkflowReplayRunConfiguration
import io.temporal.intellij.replay.runconfig.WorkflowReplayRunConfigurationType
import io.temporal.intellij.workflow.WorkflowService
import java.io.File

/**
 * Service that orchestrates workflow replay operations.
 */
class WorkflowReplayService(private val project: Project) {

    private val historyExporter = HistoryExporter()
    private val classFinder = WorkflowClassFinder(project)

    /**
     * Replay using already-cached history (no server fetch needed).
     * Exports the cached history to a temp file and launches replay.
     */
    fun replayWithCachedHistory(
        history: History,
        workflowType: String,
        workflowId: String
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Workflow Replay") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Finding workflow implementation..."
                indicator.isIndeterminate = true

                // Export cached history to temp file
                val historyFile = historyExporter.exportToFile(history, "replay-$workflowId-")

                // Find matching implementations
                ApplicationManager.getApplication().runReadAction {
                    val implementations = classFinder.findByWorkflowType(workflowType)

                    ApplicationManager.getApplication().invokeLater {
                        when {
                            implementations.isEmpty() -> {
                                showError("No workflow implementation found for type: $workflowType\n\n" +
                                         "Make sure your project contains a class that implements a\n" +
                                         "@WorkflowInterface annotated interface with this workflow type.")
                            }
                            implementations.size == 1 -> {
                                val impl = implementations.first()
                                createAndRunConfiguration(
                                    historyFilePath = historyFile.absolutePath,
                                    workflowClassName = impl.qualifiedName,
                                    configName = "Replay: $workflowId",
                                    psiClass = impl.psiClass
                                )
                            }
                            else -> {
                                val dialog = WorkflowClassChooserDialog(project, implementations, workflowType)
                                if (dialog.showAndGet()) {
                                    dialog.selectedImplementation?.let { impl ->
                                        createAndRunConfiguration(
                                            historyFilePath = historyFile.absolutePath,
                                            workflowClassName = impl.qualifiedName,
                                            configName = "Replay: $workflowId",
                                            psiClass = impl.psiClass
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    /**
     * Replay a workflow from the server.
     * Exports history to a temp file and launches the replay run configuration.
     */
    fun replayFromServer(
        workflowService: WorkflowService,
        workflowId: String,
        runId: String?,
        workflowClassName: String,
        psiClass: PsiClass? = null
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Workflow Replay") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Exporting workflow history..."
                indicator.isIndeterminate = true

                // Get raw history from server
                val historyResult = workflowService.getRawHistory(workflowId, runId)
                if (historyResult.isFailure) {
                    val error = historyResult.exceptionOrNull()?.message ?: "Unknown error"
                    showError("Failed to export history: $error")
                    return
                }

                val history = historyResult.getOrNull()!!

                // Export to temp file
                val historyFile = historyExporter.exportToFile(history, "replay-$workflowId-")

                ApplicationManager.getApplication().invokeLater {
                    createAndRunConfiguration(
                        historyFilePath = historyFile.absolutePath,
                        workflowClassName = workflowClassName,
                        configName = "Replay: $workflowId",
                        psiClass = psiClass
                    )
                }
            }
        })
    }

    /**
     * Import a workflow history from a JSON file and replay it.
     * Shows a file chooser, extracts workflow type, finds implementations, and runs replay.
     */
    fun replayFromFile() {
        // Show file chooser
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            .withTitle("Select Workflow History File")
            .withDescription("Select a JSON file containing Temporal workflow history")

        FileChooser.chooseFile(descriptor, project, null) { virtualFile ->
            val file = File(virtualFile.path)

            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Processing History File") {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Reading history file..."

                    // Load and validate file
                    val jsonResult = historyExporter.loadFromFile(file)
                    if (jsonResult.isFailure) {
                        showError("Failed to read file: ${jsonResult.exceptionOrNull()?.message}")
                        return
                    }

                    val historyJson = jsonResult.getOrNull()!!

                    if (!historyExporter.isValidHistoryJson(historyJson)) {
                        showError("Invalid history file format. Expected Temporal workflow history JSON.")
                        return
                    }

                    // Extract workflow type
                    val workflowType = historyExporter.extractWorkflowType(historyJson)
                    if (workflowType == null) {
                        showError("Could not determine workflow type from history file.")
                        return
                    }

                    indicator.text = "Finding workflow implementations..."

                    // Find matching implementations
                    ApplicationManager.getApplication().runReadAction {
                        val implementations = classFinder.findByWorkflowType(workflowType)

                        ApplicationManager.getApplication().invokeLater {
                            when {
                                implementations.isEmpty() -> {
                                    showError("No workflow implementation found for type: $workflowType\n\n" +
                                             "Make sure your project contains a class that implements a\n" +
                                             "@WorkflowInterface annotated interface with this workflow type.")
                                }
                                implementations.size == 1 -> {
                                    val impl = implementations.first()
                                    createAndRunConfiguration(
                                        historyFilePath = file.absolutePath,
                                        workflowClassName = impl.qualifiedName,
                                        configName = "Replay: ${file.nameWithoutExtension}",
                                        psiClass = impl.psiClass
                                    )
                                }
                                else -> {
                                    val dialog = WorkflowClassChooserDialog(project, implementations, workflowType)
                                    if (dialog.showAndGet()) {
                                        dialog.selectedImplementation?.let { impl ->
                                            createAndRunConfiguration(
                                                historyFilePath = file.absolutePath,
                                                workflowClassName = impl.qualifiedName,
                                                configName = "Replay: ${file.nameWithoutExtension}",
                                                psiClass = impl.psiClass
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    /**
     * Find workflow implementations for a given workflow type and launch replay.
     * Used when replaying from the inspector panel.
     */
    fun findAndReplay(
        workflowService: WorkflowService,
        workflowId: String,
        runId: String?,
        workflowType: String
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finding Workflow Implementation") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Searching for workflow implementations..."

                ApplicationManager.getApplication().runReadAction {
                    val implementations = classFinder.findByWorkflowType(workflowType)

                    ApplicationManager.getApplication().invokeLater {
                        when {
                            implementations.isEmpty() -> {
                                showError("No workflow implementation found for type: $workflowType\n\n" +
                                         "Make sure your project contains a class that implements a\n" +
                                         "@WorkflowInterface annotated interface with this workflow type.")
                            }
                            implementations.size == 1 -> {
                                val impl = implementations.first()
                                replayFromServer(workflowService, workflowId, runId, impl.qualifiedName, impl.psiClass)
                            }
                            else -> {
                                val dialog = WorkflowClassChooserDialog(project, implementations, workflowType)
                                if (dialog.showAndGet()) {
                                    dialog.selectedImplementation?.let { impl ->
                                        replayFromServer(workflowService, workflowId, runId, impl.qualifiedName, impl.psiClass)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    /**
     * Create and execute a workflow replay run configuration.
     */
    private fun createAndRunConfiguration(
        historyFilePath: String,
        workflowClassName: String,
        configName: String,
        psiClass: PsiClass? = null
    ) {
        val runManager = RunManager.getInstance(project)
        val configurationType = WorkflowReplayRunConfigurationType()
        val factory = configurationType.configurationFactories.first()

        val settings = runManager.createConfiguration(configName, factory)
        val config = settings.configuration as WorkflowReplayRunConfiguration

        config.historySource = "file"
        config.historyFilePath = historyFilePath
        config.workflowClassName = workflowClassName

        // Find the module containing the workflow class
        val module = if (psiClass != null) {
            ModuleUtilCore.findModuleForPsiElement(psiClass)
        } else {
            null
        }

        if (module != null) {
            config.setModule(module)
        } else {
            // Fallback to first valid module
            val modules = config.validModules
            if (modules.isNotEmpty()) {
                config.setModule(modules.first())
            }
        }

        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Workflow Replay Error")
        }
    }
}
