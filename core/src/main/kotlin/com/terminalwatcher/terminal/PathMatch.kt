package com.terminalwatcher.terminal

import com.intellij.openapi.util.SystemInfo

/**
 * Hook payloads report the cwd with the OS separator (`C:\a\b` on Windows) while
 * `Project.basePath` always uses '/', so compare both in one form.
 */
internal fun isSameOrUnderPath(
    path: String,
    base: String,
    ignoreCase: Boolean = !SystemInfo.isFileSystemCaseSensitive,
): Boolean {
    val normalizedPath = path.replace('\\', '/').trimEnd('/')
    val normalizedBase = base.replace('\\', '/').trimEnd('/')
    return normalizedPath.equals(normalizedBase, ignoreCase) ||
        normalizedPath.startsWith("$normalizedBase/", ignoreCase)
}
