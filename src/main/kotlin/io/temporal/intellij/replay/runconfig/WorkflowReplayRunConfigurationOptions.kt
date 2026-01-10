package io.temporal.intellij.replay.runconfig

import com.intellij.execution.configurations.ModuleBasedConfigurationOptions
import com.intellij.openapi.components.StoredProperty

/**
 * Persistent options for the workflow replay run configuration.
 */
class WorkflowReplayRunConfigurationOptions : ModuleBasedConfigurationOptions() {

    private val historySourceProperty: StoredProperty<String?> = string("file")
        .provideDelegate(this, "historySource")

    private val workflowIdProperty: StoredProperty<String?> = string("")
        .provideDelegate(this, "workflowId")

    private val runIdProperty: StoredProperty<String?> = string("")
        .provideDelegate(this, "runId")

    private val historyFilePathProperty: StoredProperty<String?> = string("")
        .provideDelegate(this, "historyFilePath")

    private val workflowClassNameProperty: StoredProperty<String?> = string("")
        .provideDelegate(this, "workflowClassName")

    var historySource: String
        get() = historySourceProperty.getValue(this) ?: "file"
        set(value) = historySourceProperty.setValue(this, value)

    var workflowId: String
        get() = workflowIdProperty.getValue(this) ?: ""
        set(value) = workflowIdProperty.setValue(this, value)

    var runId: String
        get() = runIdProperty.getValue(this) ?: ""
        set(value) = runIdProperty.setValue(this, value)

    var historyFilePath: String
        get() = historyFilePathProperty.getValue(this) ?: ""
        set(value) = historyFilePathProperty.setValue(this, value)

    var workflowClassName: String
        get() = workflowClassNameProperty.getValue(this) ?: ""
        set(value) = workflowClassNameProperty.setValue(this, value)
}
