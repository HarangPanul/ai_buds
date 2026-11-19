package com.example.aibuds.core

/**
 * 볼륨 올림 → 내림 제스처로 턴을 시작한다. 이어폰이 연결돼 있을 때만.
 * 같은 누름이 두 경로(키 입력, 볼륨 변경 방송)로 들어와도 한 번만 트리거한다.
 */
class VolumeTrigger(
    now: () -> Long,
    private val earphoneConnected: () -> Boolean,
    /** 진단 기록. 트리거가 안 먹을 때 볼륨 입력이 어디까지 왔는지 보려고 남긴다. */
    private val log: (String) -> Unit = {},
    private val onTrigger: () -> Unit,
) {
    private val detector = VolumeGestureDetector(now = now)

    /** @param source 어느 경로로 들어왔는지 (기록용). 예: "key", "broadcast". */
    fun onVolume(up: Boolean, source: String = "") {
        val hit = detector.onKey(up)
        log("volume ${if (up) "up" else "down"} ($source)" + if (hit) " → trigger" else "")
        if (!hit) return
        if (!earphoneConnected()) {
            log("trigger ignored: no earphone output")
            return
        }
        onTrigger()
    }
}
