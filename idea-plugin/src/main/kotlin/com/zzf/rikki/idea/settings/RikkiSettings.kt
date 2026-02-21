package com.zzf.rikki.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "RikkiSettings",
    storages = [Storage("rikki.xml")]
)
class RikkiSettings : PersistentStateComponent<RikkiSettings.State> {

    data class State(
        var apiKey: String = "",
        var baseUrl: String = "https://api.deepseek.com/v1",
        var modelName: String = "deepseek-chat",
        var completionEnabled: Boolean = true,
        /** 留空则与 chat 使用同一模型 */
        var completionModelName: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): RikkiSettings =
            ApplicationManager.getApplication().getService(RikkiSettings::class.java)
    }
}
