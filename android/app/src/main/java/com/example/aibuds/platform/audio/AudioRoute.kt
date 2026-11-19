package com.example.aibuds.platform.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * 마이크 입력 모드.
 * [Communication]: 통화용. 플랫폼이 잡음 억제·자동 음량·에코 제거를 건다.
 * [Recognition]: 음성 인식용. 잡음 억제·자동 음량을 끄도록 되어 있다 (기기마다 다를 수 있음).
 */
enum class MicMode(val id: String, val audioSource: Int) {
    Communication("communication", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    Recognition("recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION);

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Communication
    }
}

/**
 * 이어폰 마이크로 듣기 위한 경로 잡기. 앱의 다른 곳은 장치 종류를 알지 못한다.
 * 블루투스 이어폰의 마이크는 "통신 장치"로 지정해야 열린다. 그러면 소리도 통화 경로로 나가고 통화 음량이 적용된다.
 */
class AudioRoute(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)

    /** 이어폰이 있으면 통신 장치로 지정하고, 녹음에 지정할 입력 장치를 돌려준다. 없으면 null(시스템 기본 마이크). */
    fun acquire(): AudioDeviceInfo? {
        val candidates = am.availableCommunicationDevices
        val comm = PRIORITY.firstNotNullOfOrNull { type -> candidates.firstOrNull { it.type == type } } ?: return null
        am.setCommunicationDevice(comm)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == comm.type }
    }

    fun release() = am.clearCommunicationDevice()

    /** 소리가 이어폰으로 나가는 중인지. 폰 스피커로 답이 흘러나오지 않게, 트리거는 이때만 받는다. */
    fun isEarphoneConnected(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in EARPHONES }

    private companion object {
        val EARPHONES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        val PRIORITY = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
    }
}
