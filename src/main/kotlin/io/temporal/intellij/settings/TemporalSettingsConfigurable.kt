package io.temporal.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*

class TemporalSettingsConfigurable(private val project: Project) : BoundConfigurable("Temporal") {
    private val settings = TemporalSettings.getInstance(project)

    override fun createPanel(): DialogPanel = panel {
        val state = settings.state

        group("Connection") {
            row("Address:") {
                textField()
                    .bindText(state::address)
                    .columns(COLUMNS_LARGE)
                    .comment("Temporal server address (e.g., localhost:7233)")
            }
            row("Namespace:") {
                textField()
                    .bindText(state::namespace)
                    .columns(COLUMNS_MEDIUM)
                    .comment("Temporal namespace (default: \"default\")")
            }
            row("API Key:") {
                passwordField()
                    .bindText(state::apiKey)
                    .columns(COLUMNS_LARGE)
                    .comment("API key for Temporal Cloud or authenticated connections")
            }
            row {
                button("Test Connection") {
                    testConnection()
                }
            }
        }

        group("TLS") {
            row {
                checkBox("Enable TLS")
                    .bindSelected(state::tlsEnabled)
            }
            row("Client Certificate:") {
                textFieldWithBrowseButton(
                    "Select Client Certificate",
                    project,
                    FileChooserDescriptorFactory.createSingleFileDescriptor("pem")
                ).bindText(state::clientCertPath)
                    .columns(COLUMNS_LARGE)
            }
            row("Client Key:") {
                textFieldWithBrowseButton(
                    "Select Client Key",
                    project,
                    FileChooserDescriptorFactory.createSingleFileDescriptor("pem")
                ).bindText(state::clientKeyPath)
                    .columns(COLUMNS_LARGE)
            }
            row("Server CA Certificate:") {
                textFieldWithBrowseButton(
                    "Select Server CA Certificate",
                    project,
                    FileChooserDescriptorFactory.createSingleFileDescriptor("pem")
                ).bindText(state::serverCACertPath)
                    .columns(COLUMNS_LARGE)
            }
            row("Server Name:") {
                textField()
                    .bindText(state::serverName)
                    .columns(COLUMNS_MEDIUM)
                    .comment("Override server name for TLS verification")
            }
            row {
                checkBox("Disable Host Verification")
                    .bindSelected(state::disableHostVerification)
                    .comment("Warning: Only use for development/testing")
            }
        }
    }

    private fun testConnection() {
        // Apply current UI values to state before testing
        apply()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Testing Temporal Connection", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to ${settings.state.address}..."
                indicator.isIndeterminate = true

                val result = TemporalConnectionTester.testConnection(settings.state)

                ApplicationManager.getApplication().invokeLater {
                    if (result.success) {
                        val message = buildString {
                            append(result.message)
                            result.serverInfo?.let { append("\n\n$it") }
                        }
                        Messages.showInfoMessage(project, message, "Connection Successful")
                    } else {
                        Messages.showErrorDialog(project, result.message, "Connection Failed")
                    }
                }
            }
        })
    }
}
