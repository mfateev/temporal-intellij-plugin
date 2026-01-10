package io.temporal.intellij.replay.runconfig

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

/**
 * Factory for creating workflow replay run configurations.
 */
class WorkflowReplayConfigurationFactory(
    type: WorkflowReplayRunConfigurationType
) : ConfigurationFactory(type) {

    override fun getId(): String = WorkflowReplayRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return WorkflowReplayRunConfiguration(project, this, "Workflow Replay")
    }

    override fun getOptionsClass(): Class<out BaseState> {
        return WorkflowReplayRunConfigurationOptions::class.java
    }
}
