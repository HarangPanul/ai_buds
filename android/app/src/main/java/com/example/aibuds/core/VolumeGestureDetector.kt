package com.example.aibuds.core

/** 볼륨 올림 → 내림이 [windowMs] 안에 이어지면 트리거로 본다. Android 의존 없음. */
class VolumeGestureDetector(
    private val windowMs: Long = 2000,
    private val now: () -> Long,
) {
    private var upAt = -1L

    /** 키 입력을 알린다. 올림→내림이 완성되는 순간 true. */
    fun onKey(up: Boolean): Boolean {
        val t = now()
        if (up) {
            upAt = t
            return false
        }
        val hit = upAt >= 0 && t - upAt <= windowMs
        upAt = -1L
        return hit
    }
}
