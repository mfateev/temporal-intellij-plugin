package io.temporal.intellij.replay.runconfig

import com.intellij.execution.configurations.ConfigurationTypeBase
import io.temporal.intellij.TemporalIcons

/**
 * Run configuration type for Temporal workflow replay.
 */
class WorkflowReplayRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Temporal Workflow Replay",
    "Replay a Temporal workflow execution against local implementation",
    TemporalIcons.Action
) {
    companion object {
        const val ID = "TemporalWorkflowReplay"
    }

    init {
        addFactory(WorkflowReplayConfigurationFactory(this))
    }
}
