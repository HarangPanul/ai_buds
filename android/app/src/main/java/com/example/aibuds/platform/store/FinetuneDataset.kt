package com.example.aibuds.platform.store

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.aibuds.core.Utterance
import com.example.aibuds.platform.audio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 파인튜닝용 (wav, 전사) 쌍을 공용 다운로드 폴더에 저장한다 (MediaStore, 권한 불필요).
 *
 * 구조:
 *   Download/ai_buds/finetune/audio/20261114_153012_123.wav   16kHz mono PCM16
 *   Download/ai_buds/finetune/metadata.jsonl                  {"audio":"audio/....wav","text":"...","stt":"phone","mic":"communication","duration":2.31,"created":"..."}
 *
 * 아무것도 못 들은 녹음은 "text":"" 로 저장된다 (학습 전에 직접 받아 적거나 빼야 한다).
 *
 * 이전 앱과 같은 자리·같은 형식이라 예전 녹음에 이어서 쌓인다. tools/의 stt_eval.py, audio_check.py가 이 형식을 읽는다.
 *
 * 앱을 지웠다 다시 깔면 예전 metadata.jsonl은 이 앱 소유가 아니게 되어 이어 쓸 수 없다.
 * 그때는 시스템이 "metadata (1).jsonl" 같은 새 파일을 만든다.
 */
class FinetuneDataset(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val lock = Mutex()
    private var metadataUri: Uri? = null

    /** 저장했으면 true. 오디오가 없거나 너무 짧으면 저장하지 않는다. */
    suspend fun save(utterance: Utterance): Boolean = withContext(Dispatchers.IO) {
        val wav = utterance.audio ?: return@withContext false
        val seconds = Wav.stats(wav).seconds
        if (seconds < MIN_SECONDS) return@withContext false
        try {
            val now = Date()
            val fileName = "${nameFormat.format(now)}.wav"
            writeNew("$DIR/audio/", fileName, "audio/wav", wav)
            val line = JSONObject()
                .put("audio", "audio/$fileName")
                .put("text", utterance.text.trim())
                .put("stt", utterance.engine)
                .put("mic", utterance.micMode)
                .put("duration", Math.round(seconds * 100) / 100.0)
                .put("created", isoFormat.format(now))
                .toString() + "\n"
            lock.withLock { appendMetadata(line.toByteArray()) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeNew(relativePath: String, name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(COLLECTION, values)) { "파일을 만들 수 없어요" }
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun appendMetadata(bytes: ByteArray) {
        val uri = metadataUri ?: findMetadata()
        if (uri != null) {
            try {
                checkNotNull(resolver.openOutputStream(uri, "wa")).use { it.write(bytes) }
                metadataUri = uri
                return
            } catch (_: Exception) {
                // 사용자가 지웠거나 소유권을 잃었다 → 새로 만든다.
            }
        }
        metadataUri = writeNew("$DIR/", METADATA, "application/x-ndjson", bytes)
    }

    /** 이 앱이 만든 metadata.jsonl을 찾는다 (다른 앱이 만든 파일은 쿼리에 보이지 않는다). */
    private fun findMetadata(): Uri? = resolver.query(
        COLLECTION,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf("$DIR/", METADATA),
        null,
    )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(COLLECTION, c.getLong(0)) else null }

    private companion object {
        const val MIN_SECONDS = 0.3f
        const val DIR = "Download/ai_buds/finetune"
        const val METADATA = "metadata.jsonl"
        val COLLECTION: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
