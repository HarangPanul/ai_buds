package com.example.aibuds.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 화면이 꺼져 있어도 마이크를 쓸 수 있게 프로세스를 포그라운드로 붙잡아 두고, 그동안 볼륨 변경 방송을 받아 트리거에 넘긴다.
 * 앱이 최근 앱 화면에 있는 동안만 돈다: 밀어서 닫으면 끝나고, 시스템이 죽여도 다시 살리지 않는다.
 * 대화 자체는 여기서 하지 않는다 ([AppContainer.conversation]).
 */
class ConversationService : Service() {
    private val container get() = (application as App).container

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = intent.getIntExtra(EXTRA_VALUE, -1)
            val prev = intent.getIntExtra(EXTRA_PREV, -1)
            val stream = intent.getIntExtra(EXTRA_STREAM, -1)
            if (now < 0 || prev < 0 || now == prev) {
                container.log.log("broadcast: stream $stream $prev→$now (ignored)")
                return
            }
            container.log.log("broadcast: stream $stream $prev→$now")
            container.volumeTrigger.onVolume(up = now > prev, source = "broadcast")
        }
    }

    /** 앱이 살아 있는지 기록에 남긴다. 기록에 이 줄이 오래 비면 그동안 앱이 멈춰 있었다는 뜻이다. */
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            container.log.log("service: alive")
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ai_buds", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("ai_buds")
            .setContentText("이어폰 연결 시 볼륨 올림 → 내림으로 말하기")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            container.log.log("service: started")
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
        } catch (e: Exception) {
            // 앱이 화면에 없을 때는 마이크용 포그라운드 서비스를 시작할 수 없다. 앱을 다시 열어야 한다.
            container.log.log("service: startForeground failed: ${e.javaClass.simpleName} ${e.message}")
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter(ACTION_VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** 시스템이 프로세스를 죽여도 다시 살리지 않는다. 앱을 열 때만 시작한다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** 최근 앱 화면에서 밀어서 닫으면 서비스도 끝낸다. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        container.log.log("service: task removed (swiped from recents) → stop")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        container.log.log("service: destroyed")
        container.conversation.cancel()
        handler.removeCallbacks(heartbeat)
        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: IllegalArgumentException) {
            // startForeground가 실패해 등록하지 못한 경우
        }
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "ai_buds"
        // AudioManager의 숨겨진 상수. 시스템이 보내는 보호된 방송이다.
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val HEARTBEAT_MS = 15 * 60 * 1000L
    }
}
