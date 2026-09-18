package com.terminalwatcher.terminal

import com.intellij.openapi.diagnostic.Logger

/**
 * Turns the PID a hook script reports into the set of PIDs that could belong to
 * the terminal tab the event came from.
 *
 * ## Why this is needed
 * The hook scripts send `shell_pid=$$` (bash) / `shell_pid=$PID` (PowerShell),
 * which is the PID of the *hook script itself*, not of the tab's shell. The
 * scripts only correct it to the real shell PID inside the legacy PPID-walk
 * branch, which is skipped whenever the injected env vars are present — i.e.
 * almost always. Comparing that PID against a tab's shell PID therefore never
 * matched, which is why [TerminalTabTracker]'s pid path always fell through.
 *
 * ## What this does instead
 * Walks the OS process tree upward from the reported PID using [ProcessHandle],
 * collecting every ancestor until the IDE's own process is reached. A verified
 * chain on Windows looks like:
 *
 * ```
 * powershell.exe (hook) -> claude.exe -> powershell.exe (tab shell) -> idea64.exe (IDE)
 * ```
 *
 * The ancestor whose parent is the IDE is the tab's shell — an exact rule, not a
 * guess, because the IDE spawns exactly one shell process per terminal tab.
 * The full chain is returned as well so callers can match any ancestor, which
 * keeps this working if the platform ever inserts a wrapper process (ConPTY
 * helpers, login shells) between the IDE and the shell.
 *
 * Everything is defensive: a dead or unreadable process simply shortens the
 * chain, and callers fall back to the cwd/selection heuristics as before.
 */
object ShellPidResolver {

    private val log = Logger.getInstance(ShellPidResolver::class.java)

    /** Guards against pathological trees; real chains are well under ten deep. */
    private const val MAX_DEPTH = 24

    /**
     * @param pids reported PID first, then each ancestor, stopping before the IDE process.
     * @param shellPid the ancestor whose direct parent is the IDE process, or null
     *   if the walk never reached the IDE (remote shell, wrapper, dead process).
     */
    data class ProcessChain(
        val pids: List<Long>,
        val shellPid: Long?,
    ) {
        val candidates: Set<Long> get() = pids.toSet()
    }

    fun resolve(reportedPid: Long?): ProcessChain {
        if (reportedPid == null || reportedPid <= 0) return ProcessChain(emptyList(), null)

        val idePid = runCatching { ProcessHandle.current().pid() }.getOrNull()
        var handle = runCatching { ProcessHandle.of(reportedPid).orElse(null) }.getOrNull()
            ?: return ProcessChain(listOf(reportedPid), null)

        val pids = mutableListOf<Long>()
        var shellPid: Long? = null
        var depth = 0

        while (depth < MAX_DEPTH) {
            val pid = handle.pid()
            if (idePid != null && pid == idePid) break
            pids.add(pid)

            val parent = runCatching { handle.parent().orElse(null) }.getOrNull() ?: break
            if (idePid != null && parent.pid() == idePid) {
                shellPid = pid
                break
            }
            handle = parent
            depth++
        }

        log.info("[TWatcher] Process chain from $reportedPid: $pids (tab shell=$shellPid, ide=$idePid)")
        return ProcessChain(pids, shellPid)
    }
}
