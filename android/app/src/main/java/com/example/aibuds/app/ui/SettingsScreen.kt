package com.example.aibuds.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aibuds.platform.audio.MicMode
import com.example.aibuds.platform.llm.Dialect
import com.example.aibuds.platform.llm.ServerModel
import kotlinx.coroutines.launch

/**
 * 설정 화면. 값은 "저장"을 눌러야 반영되고, 다음 턴부터 적용된다.
 * 설정을 추가할 때: SettingsStore에 필드를 넣고, 여기에 편집 중인 값(remember) 하나와 입력칸 하나, 저장 버튼의 copy에 한 줄을 넣는다.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var llmBaseUrl by remember { mutableStateOf(s.llmBaseUrl) }
    var llmServer by remember { mutableStateOf(s.llmServer) }
    var llmModel by remember { mutableStateOf(s.llmModel) }
    var systemPrompt by remember { mutableStateOf(s.systemPrompt) }
    var includeDateTime by remember { mutableStateOf(s.includeDateTime) }
    var temperature by remember { mutableStateOf(s.temperature.toString()) }
    var historyTurns by remember { mutableStateOf(s.historyTurns.toString()) }
    var speakThinking by remember { mutableStateOf(s.speakThinking) }
    var micRecognition by remember { mutableStateOf(s.micSource == MicMode.Recognition.id) }
    var saveFinetune by remember { mutableStateOf(s.saveFinetune) }
    var showDebug by remember { mutableStateOf(s.showDebug) }

    // 숫자 칸은 글자로 편집하므로 저장 전에 확인한다.
    val temperatureValue = temperature.toFloatOrNull()?.takeIf { it in 0f..2f }
    val historyValue = historyTurns.toIntOrNull()?.takeIf { it >= 1 }

    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("LLM 서버")
        OutlinedTextField(llmBaseUrl, { llmBaseUrl = it }, label = { Text("주소") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Choice("llama.cpp", "프롬프트 캐시, 모델 자동 로드", llmServer == Dialect.LLAMA_CPP) { llmServer = Dialect.LLAMA_CPP }
        Choice("OpenAI 호환", "ollama, vllm 등", llmServer != Dialect.LLAMA_CPP) { llmServer = Dialect.OPENAI }
        ModelField(llmModel, { llmModel = it }) { vm.listModels(llmBaseUrl) }
        NumberField("temperature (0~2)", temperature, { temperature = it }, valid = temperatureValue != null, decimal = true)

        Section("대화")
        OutlinedTextField(systemPrompt, { systemPrompt = it }, label = { Text("시스템 프롬프트") }, modifier = Modifier.fillMaxWidth())
        Toggle("현재 날짜·시각을 모델에 알려주기", null, includeDateTime) { includeDateTime = it }
        Toggle("답변 전 thinking 읽기", null, speakThinking) { speakThinking = it }
        NumberField("모델에 보낼 최근 메시지 수", historyTurns, { historyTurns = it }, valid = historyValue != null, decimal = false)

        Section("마이크")
        Toggle("음성 인식 모드 (실험)", "끄면 통화 모드(잡음 억제·자동 음량). 켜면 처리를 줄인 원음. 저장 녹음에 mic 값으로 남아요", micRecognition) { micRecognition = it }
        Toggle("학습용 녹음 저장 (wav + 받아 적은 글)", "Download/ai_buds/finetune", saveFinetune) { saveFinetune = it }

        Section("화면")
        Toggle("디버그 정보 표시", "메인 화면에 LLM 주소와 사용한 마이크를, 이 화면 맨 아래에 동작 기록을 보여줘요", showDebug) { showDebug = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = temperatureValue != null && historyValue != null,
                onClick = {
                    vm.updateSettings {
                        it.copy(
                            llmBaseUrl = llmBaseUrl.trim(),
                            llmServer = llmServer,
                            llmModel = llmModel.trim(),
                            systemPrompt = systemPrompt,
                            includeDateTime = includeDateTime,
                            temperature = temperatureValue!!,
                            historyTurns = historyValue!!,
                            speakThinking = speakThinking,
                            micSource = (if (micRecognition) MicMode.Recognition else MicMode.Communication).id,
                            saveFinetune = saveFinetune,
                            showDebug = showDebug,
                        )
                    }
                    onClose()
                },
            ) { Text("저장") }
            TextButton(onClick = onClose) { Text("취소") }
        }
        if (showDebug) EventLogSection(vm)
    }
}

/** 동작 기록 (최신이 위). 볼륨 트리거가 안 먹을 때처럼, 무슨 일이 있었는지 보려고 둔다. */
@Composable
private fun EventLogSection(vm: MainViewModel) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(vm.recentLog()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("동작 기록", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = { lines = vm.recentLog() }) { Text("새로고침") }
        TextButton(onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ai_buds log", vm.recentLog(500).joinToString("\n")))
            Toast.makeText(context, "기록을 복사했어요", Toast.LENGTH_SHORT).show()
        }) { Text("복사") }
    }
    Text(
        if (lines.isEmpty()) "(기록 없음)" else lines.joinToString("\n"),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Toggle(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, Modifier.padding(end = 8.dp))
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, valid: Boolean, decimal: Boolean) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 모델 이름 입력칸 + 서버 목록에서 고르기. 비워 두면 요청에 모델 이름을 넣지 않는다(서버 기본값). */
@Composable
private fun ModelField(value: String, onChange: (String) -> Unit, load: suspend () -> List<ServerModel>) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ServerModel>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value, onChange,
            label = { Text("모델") },
            placeholder = { Text("비우면 서버 기본값") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Box {
            TextButton(onClick = {
                scope.launch {
                    models = load()
                    open = models.isNotEmpty()
                }
            }) { Text("목록") }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("(서버 기본값)") }, onClick = { onChange(""); open = false })
                models.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.id)
                                if (m.status.isNotEmpty()) Text(m.status, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { onChange(m.id); open = false },
                    )
                }
            }
        }
    }
}
