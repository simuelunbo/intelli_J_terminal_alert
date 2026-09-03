package com.terminalwatcher.hook

/**
 * Safe editing of the top-level `notify` assignment in `~/.codex/config.toml`.
 *
 * The Codex desktop app rewrites this file on its own: it may wrap our notify command inside its
 * own one (`... --previous-notify "<our command>"`) and drops comment lines while doing so. Two
 * rules keep us from fighting it:
 *  - "already configured" is decided by the assignment's content (does the value reference our
 *    script?), never by a marker comment;
 *  - an existing assignment is located with a quote-aware scan, not a regex - the value routinely
 *    contains `]` inside strings and may span lines. A naive regex once failed to remove the
 *    wrapped assignment and prepended a second `notify` key; duplicate keys are invalid TOML, so
 *    Codex refused to start.
 */
internal object CodexNotifyToml {

    /**
     * Ensures the top-level `notify` assignment is an argv array referencing [scriptMarker].
     * Returns the rewritten content, or null when the config is already fine.
     *
     * - Exactly one top-level assignment whose array value mentions [scriptMarker] (possibly
     *   wrapped by another tool): unchanged.
     * - Otherwise every top-level `notify` assignment and stale Terminal Watcher comment is
     *   removed and [headerComment] plus one assignment is prepended. If one of the removed
     *   assignments was a wrapper referencing [scriptMarker], that wrapper is what gets kept
     *   (repairing a duplicate-key corruption without dropping the other tool's chain);
     *   otherwise [notifyLine] is inserted.
     */
    fun upsertNotify(
        content: String,
        scriptMarker: String,
        headerComment: String,
        notifyLine: String,
    ): String? {
        val lines = content.lines()
        val assignments = findTopLevelNotifyAssignments(lines)

        if (assignments.size == 1 && referencesScript(lines, assignments[0], scriptMarker)) {
            return null
        }

        val keep = assignments
            .filter { referencesScript(lines, it, scriptMarker) }
            .maxByOrNull { text(lines, it).length }
            ?.let { text(lines, it) }
            ?: notifyLine

        val topEnd = lines.indexOfFirst { it.trimStart().startsWith("[") }
            .let { if (it == -1) lines.size else it }
        val drop = mutableSetOf<Int>()
        assignments.forEach { r -> r.forEach(drop::add) }
        for (i in 0 until topEnd) {
            val t = lines[i].trim()
            if (t.startsWith("# Terminal Watcher") || t == "# Added by Terminal AI Watcher plugin") {
                drop.add(i)
            }
        }

        val rest = lines.filterIndexed { i, _ -> i !in drop }
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trimEnd()
        return buildList {
            add(headerComment)
            add(keep)
            if (rest.isNotEmpty()) add(rest)
        }.joinToString("\n")
    }

    /**
     * Ensures a `[[hooks.PermissionRequest]]` command hook referencing [scriptMarker] exists.
     * Codex's external `notify` program only fires on agent-turn-complete, so approval prompts
     * are only observable through this lifecycle hook (the event JSON arrives on stdin). The
     * block is appended at the end of the file, which keeps foreign hook tables intact; returns
     * null when a PermissionRequest hook already references [scriptMarker].
     */
    fun ensurePermissionRequestHook(content: String, scriptMarker: String, hookBlock: String): String? {
        val idx = content.indexOf("[[hooks.PermissionRequest")
        if (idx >= 0 && content.indexOf(scriptMarker, idx) >= 0) return null
        val head = if (content.isBlank()) "" else content.trimEnd() + "\n\n"
        return head + hookBlock.trimEnd() + "\n"
    }

    /** Line ranges of `notify = ...` assignments in the root table (before the first `[table]`). */
    private fun findTopLevelNotifyAssignments(lines: List<String>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trimStart()
            if (trimmed.startsWith("[")) break
            if (NOTIFY_KEY.containsMatchIn(trimmed)) {
                val end = assignmentEnd(lines, i)
                out.add(i..end)
                i = end + 1
            } else {
                i++
            }
        }
        return out
    }

    /** Last line of the assignment starting at [start]: scans past `]` inside strings and lines. */
    private fun assignmentEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            var inBasic = false
            var inLiteral = false
            var escaped = false
            var j = if (i == start) line.indexOf('=') + 1 else 0
            loop@ while (j < line.length) {
                val c = line[j]
                when {
                    escaped -> escaped = false
                    inBasic -> when (c) {
                        '\\' -> escaped = true
                        '"' -> inBasic = false
                    }
                    inLiteral -> if (c == '\'') inLiteral = false
                    c == '"' -> inBasic = true
                    c == '\'' -> inLiteral = true
                    c == '#' -> break@loop
                    c == '[' -> depth++
                    c == ']' -> depth--
                }
                j++
            }
            if (depth <= 0) return i
            i++
        }
        return lines.size - 1
    }

    private fun referencesScript(lines: List<String>, range: IntRange, marker: String): Boolean {
        val t = text(lines, range)
        return t.substringAfter('=').trimStart().startsWith("[") && t.contains(marker)
    }

    private fun text(lines: List<String>, range: IntRange): String =
        lines.subList(range.first, range.last + 1).joinToString("\n")

    private val NOTIFY_KEY = Regex("""^notify\s*=""")
}
