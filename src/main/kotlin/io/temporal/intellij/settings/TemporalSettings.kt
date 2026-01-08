package io.temporal.intellij.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "TemporalSettings",
    storages = [Storage("temporal.xml")]
)
class TemporalSettings : PersistentStateComponent<TemporalSettings.State> {
    private var myState = State()

    data class State(
        // Basic connection settings
        var address: String = "localhost:7233",
        var namespace: String = "default",
        var apiKey: String = "",

        // TLS settings
        var tlsEnabled: Boolean = false,
        var clientCertPath: String = "",
        var clientKeyPath: String = "",
        var serverCACertPath: String = "",
        var serverName: String = "",
        var disableHostVerification: Boolean = false
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): TemporalSettings =
            project.getService(TemporalSettings::class.java)
    }
}
