package com.example.aibuds.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aibuds.core.Message
import com.example.aibuds.core.Phase
import com.example.aibuds.core.Role

/** 대화 화면. 말하기는 볼륨 올림 → 내림으로만 시작한다. */
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 화면에 돌아올 때마다, 그리고 서버 주소가 바뀌면 연결을 다시 확인한다.
    LifecycleResumeEffect(settings.llmBaseUrl) {
        vm.checkConnection()
        onPauseOrDispose {}
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(vm) { showSettings = false }
        return
    }

    // 끝난 말들 뒤에, 듣는 중인 말과 도착 중인 답변을 이어 보여준다.
    val lines = buildList {
        addAll(state.messages)
        if (state.partial.isNotEmpty()) add(Message(Role.User, state.partial + " …"))
        if (state.reply.isNotEmpty()) add(Message(Role.Assistant, state.reply))
    }
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // 누르면 다시 확인한다. 주소는 디버그용이라 설정의 "디버그 정보 표시"가 켜져 있을 때만 붙인다.
            Text(
                when (connection) {
                    MainViewModel.Connection.Checking -> "LLM 확인 중…"
                    MainViewModel.Connection.Up -> "LLM 연결됨"
                    MainViewModel.Connection.Down -> "LLM 연결 안 됨"
                } + if (settings.showDebug) "\n${settings.llmBaseUrl}" else "",
                Modifier.weight(1f).clickable(onClick = vm::checkConnection),
                style = MaterialTheme.typography.bodySmall,
                color = if (connection == MainViewModel.Connection.Down) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            Row {
                TextButton(onClick = vm::newConversation) { Text("새 대화") }
                TextButton(onClick = { showSettings = true }) { Text("설정") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines.size) { i ->
                val m = lines[i]
                Text(
                    (if (m.role == Role.User) "나: " else "AI: ") + m.text,
                    color = if (m.role == Role.User) Color(0xFF1565C0) else Color.Unspecified,
                )
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        if (settings.showDebug && state.mic.isNotEmpty()) Text("마이크: ${state.mic}", style = MaterialTheme.typography.bodySmall)
        Text(
            when (state.phase) {
                Phase.Idle -> "이어폰을 낀 채 볼륨 올림 → 내림으로 말하기"
                Phase.Listening -> "듣는 중… (말을 멈추면 끝나요)"
                Phase.Thinking -> "생각하는 중…"
                Phase.Speaking -> "말하는 중…"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedButton(onClick = vm::cancel, enabled = state.phase != Phase.Idle, modifier = Modifier.fillMaxWidth()) { Text("중단") }
    }
}
