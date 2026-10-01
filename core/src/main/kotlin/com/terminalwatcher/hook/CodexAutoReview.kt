package com.terminalwatcher.hook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.RandomAccessFile

private val TURN_CONTEXT_MARKER = "\"type\":\"turn_context\"".toByteArray()
private val AUTO_REVIEWERS = setOf("auto_review", "guardian_subagent")

/**
 * Whether Codex sends this session's approval requests to its Auto-review agent first. Codex
 * only does so for interactive policies (`on-request` or granular); `untrusted` still asks
 * the user directly. The hook payload omits the reviewer, but every turn's settings are
 * recorded in the session transcript.
 */
internal fun isCodexAutoReviewSession(transcriptPath: String?): Boolean {
    val file = transcriptPath?.let(::File)?.takeIf { it.isFile } ?: return false
    return readLastCodexTurnContext(file)?.let(::codexTurnRoutesToAutoReview) ?: false
}

internal fun codexTurnRoutesToAutoReview(turnContextLine: String): Boolean {
    val payload = runCatching { Json.parseToJsonElement(turnContextLine) }.getOrNull()
        ?.let { (it as? JsonObject)?.get("payload") as? JsonObject } ?: return false
    val reviewer = (payload["approvals_reviewer"] as? JsonPrimitive)?.contentOrNull
    val policy = payload["approval_policy"]
    val interactive = policy is JsonObject || (policy as? JsonPrimitive)?.contentOrNull == "on-request"
    return reviewer in AUTO_REVIEWERS && interactive
}

/**
 * The newest `turn_context` line, read from the end because transcripts grow to many MB
 * (embedded screenshots) and the current turn's context is near the end.
 */
internal fun readLastCodexTurnContext(
    file: File,
    chunkSize: Int = 1 shl 20,
    maxScanBytes: Long = 64L shl 20,
    maxLineBytes: Int = 8 shl 20,
): String? = RandomAccessFile(file, "r").use { raf ->
    val markerAt = findLastMarker(raf, chunkSize, maxScanBytes) ?: return null
    val lookBack = minOf(markerAt, 4096L)
    val before = ByteArray(lookBack.toInt()).also { raf.seek(markerAt - lookBack); raf.readFully(it) }
    val lineStart = markerAt - lookBack + before.lastIndexOf('\n'.code.toByte()) + 1
    raf.seek(lineStart)
    val line = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 shl 10)
    while (line.size() < maxLineBytes) {
        val read = raf.read(buffer)
        if (read <= 0) break
        val newline = buffer.indexOf('\n'.code.toByte()).takeIf { it in 0 until read }
        line.write(buffer, 0, newline ?: read)
        if (newline != null) break
    }
    line.toString(Charsets.UTF_8)
}

private fun findLastMarker(raf: RandomAccessFile, chunkSize: Int, maxScanBytes: Long): Long? {
    val length = raf.length()
    val overlap = TURN_CONTEXT_MARKER.size - 1
    var end = length
    while (end > 0 && length - end < maxScanBytes) {
        val start = maxOf(0L, end - chunkSize)
        val chunkEnd = minOf(length, end + overlap)
        val chunk = ByteArray((chunkEnd - start).toInt()).also { raf.seek(start); raf.readFully(it) }
        for (i in chunk.size - TURN_CONTEXT_MARKER.size downTo 0) {
            if (TURN_CONTEXT_MARKER.indices.all { chunk[i + it] == TURN_CONTEXT_MARKER[it] }) return start + i
        }
        end = start
    }
    return null
}
