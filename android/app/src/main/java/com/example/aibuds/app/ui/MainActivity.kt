package com.example.aibuds.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.aibuds.app.App
import com.example.aibuds.app.ConversationService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // 마이크 권한이 있어야 마이크용 포그라운드 서비스를 시작할 수 있다.
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startForegroundService(Intent(this@MainActivity, ConversationService::class.java))
                    }
                }
                LaunchedEffect(Unit) {
                    launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
                }
                MainScreen()
            }
        }
    }

    /** 앱이 화면에 있을 때의 볼륨 키. 막지 않고 지켜보기만 해서 평소 볼륨 조절은 그대로 된다. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val trigger = (application as App).container.volumeTrigger
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> trigger.onVolume(up = true, source = "key")
                KeyEvent.KEYCODE_VOLUME_DOWN -> trigger.onVolume(up = false, source = "key")
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 권한 팝업 등으로 포커스를 잃었다 돌아오면 시스템이 바를 다시 보여주므로 그때마다 숨긴다. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}
