package com.terminalwatcher.windows

import java.io.File

/** Resolve at playback time, including before the settings screen has ever been saved. */
internal fun resolveWindowsSoundPath(soundName: String, customPath: String, mediaDirectory: File): String? {
    if (customPath.isNotBlank()) return customPath.trim()
    val names = listOf(soundName, "chimes", "notify", "chord", "ding")
    return names.asSequence()
        .filter { it.isNotBlank() && '/' !in it && '\\' !in it }
        .map { File(mediaDirectory, "$it.wav") }
        .firstOrNull { it.isFile }
        ?.absolutePath
}
