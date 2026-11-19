package com.example.aibuds.app.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aibuds.app.App
import com.example.aibuds.platform.llm.Dialect
import com.example.aibuds.platform.llm.ServerModel
import com.example.aibuds.platform.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as App).container
    private val conversation = container.conversation

    val state = conversation.state
    val settings = container.settings.flow

    enum class Connection { Checking, Up, Down }

    private val _connection = MutableStateFlow(Connection.Checking)
    /** LLM 서버에 닿는지. [checkConnection]을 부를 때마다 갱신된다. */
    val connection: StateFlow<Connection> = _connection.asStateFlow()
    private var checking: Job? = null

    fun checkConnection() {
        checking?.cancel()
        _connection.value = Connection.Checking
        checking = viewModelScope.launch {
            _connection.value = if (container.serverCheck.isUp(container.settings.value.llmBaseUrl)) Connection.Up else Connection.Down
        }
    }

    /** 동작 기록의 최근 [n]줄, 최신이 먼저. */
    fun recentLog(n: Int = 80): List<String> = container.log.recent(n)

    fun cancel() = conversation.cancel()
    fun newConversation() = conversation.newConversation()

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = container.settings.value
        container.settings.update(transform)
        val s = container.settings.value
        // llama.cpp에서 모델을 새로 고르면 첫 질문을 기다리지 않게 바로 올려 둔다.
        val modelChanged = s.llmModel != before.llmModel || s.llmBaseUrl != before.llmBaseUrl || s.llmServer != before.llmServer
        if (s.llmServer == Dialect.LLAMA_CPP && s.llmModel.isNotBlank() && modelChanged) preload(s.llmBaseUrl, s.llmModel)
    }

    private fun preload(baseUrl: String, model: String) {
        viewModelScope.launch {
            try {
                container.models.ensureLoaded(baseUrl, model)
                toast("모델 로드됨: $model")
            } catch (e: Exception) {
                toast("모델 로드 실패: ${e.message}")
            }
        }
    }

    /** 서버의 모델 목록. 실패하면 이유를 토스트로 띄우고 빈 목록. */
    suspend fun listModels(baseUrl: String): List<ServerModel> =
        try {
            container.models.list(baseUrl.trim())
        } catch (e: Exception) {
            toast("모델 목록을 못 불러왔어요: ${e.message}")
            emptyList()
        }

    private fun toast(message: String) = Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
}
