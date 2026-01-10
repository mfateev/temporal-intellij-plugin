package io.temporal.intellij.replay.runconfig

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ModuleBasedConfiguration
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationModule
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

/**
 * Run configuration for replaying Temporal workflows.
 */
class WorkflowReplayRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : ModuleBasedConfiguration<RunConfigurationModule, WorkflowReplayRunConfigurationOptions>(
    name,
    RunConfigurationModule(project),
    factory
) {

    override fun getOptions(): WorkflowReplayRunConfigurationOptions {
        return super.getOptions() as WorkflowReplayRunConfigurationOptions
    }

    var historySource: String
        get() = options.historySource
        set(value) { options.historySource = value }

    var workflowId: String
        get() = options.workflowId
        set(value) { options.workflowId = value }

    var runId: String
        get() = options.runId
        set(value) { options.runId = value }

    var historyFilePath: String
        get() = options.historyFilePath
        set(value) { options.historyFilePath = value }

    var workflowClassName: String
        get() = options.workflowClassName
        set(value) { options.workflowClassName = value }

    override fun getValidModules(): Collection<Module> {
        return allModules.toList()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return WorkflowReplaySettingsEditor(project)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        return WorkflowReplayRunState(this, environment)
    }
}
