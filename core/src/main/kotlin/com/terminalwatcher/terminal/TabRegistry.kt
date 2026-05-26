package com.terminalwatcher.terminal

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.content.Content
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry mapping plugin-issued `tabId` UUIDs (injected via
 * [TerminalWatcherEnvCustomizer]) to the IntelliJ [Content] that hosts the
 * terminal tab. The customizer registers a "pending" entry and then, on the
 * EDT via `invokeLater`, self-binds the resulting Content with [bindByTabId]
 * (the ContentManager must only be touched on the EDT).
 *
 * Lookup is exact-match on tabId (cmux's surface-id equivalent). Listings
 * scoped by `projectId` (Project.locationHash) support fallback paths.
 */
@Service(Service.Level.APP)
class TabRegistry {

    private val log = Logger.getInstance(TabRegistry::class.java)

    data class TabEntry(
        val tabId: String,
        val projectId: String,
        val cwd: String,
        val createdAt: Long,
        @Volatile var contentRef: WeakReference<Content>? = null,
    )

    private val byTabId = ConcurrentHashMap<String, TabEntry>()
    private val byProjectId = ConcurrentHashMap<String, MutableSet<String>>()

    fun registerPending(tabId: String, projectId: String, cwd: String) {
        val entry = TabEntry(tabId, projectId, cwd, System.currentTimeMillis())
        byTabId[tabId] = entry
        byProjectId.computeIfAbsent(projectId) { ConcurrentHashMap.newKeySet() }.add(tabId)
        log.info("[TWatcher] Registered tab $tabId for project=$projectId cwd=$cwd")
    }

    /**
     * Binds the most recently registered (within [windowMs]) unbound entry for
     * [projectId] to [content]. Returns the bound entry or null if no candidate.
     *
     * Multiple terminals opened in close succession resolve in registration
     * order — youngest pending wins, matching JetBrains' tab-creation ordering.
     */
    fun bindNextPendingForProject(
        projectId: String,
        content: Content,
        windowMs: Long = DEFAULT_BIND_WINDOW_MS,
    ): TabEntry? {
        val now = System.currentTimeMillis()
        val candidate = (byProjectId[projectId] ?: return null)
            .asSequence()
            .mapNotNull { byTabId[it] }
            .filter { it.contentRef?.get() == null && now - it.createdAt <= windowMs }
            .maxByOrNull { it.createdAt }
            ?: return null
        candidate.contentRef = WeakReference(content)
        log.info("[TWatcher] Bound tab ${candidate.tabId} to content '${content.displayName}'")
        return candidate
    }

    /** Binds [content] to a specific [tabId]. Idempotent — re-binding refreshes the WeakReference. */
    fun bindByTabId(tabId: String, content: Content): TabEntry? {
        val entry = byTabId[tabId] ?: return null
        entry.contentRef = WeakReference(content)
        log.info("[TWatcher] Bound tab $tabId to content '${content.displayName}' (direct)")
        return entry
    }

    fun lookup(tabId: String): TabEntry? = byTabId[tabId]

    fun unbindByContent(content: Content) {
        val tabId = byTabId.values.firstOrNull { it.contentRef?.get() === content }?.tabId
        if (tabId != null) evict(tabId)
    }

    fun evict(tabId: String) {
        val entry = byTabId.remove(tabId) ?: return
        byProjectId[entry.projectId]?.remove(tabId)
        log.info("[TWatcher] Evicted tab $tabId")
    }

    /** Removes entries whose Content was GC'd or which sat unbound past [maxAgeMs]. */
    fun evictStale(maxAgeMs: Long = DEFAULT_STALE_MAX_AGE_MS) {
        val now = System.currentTimeMillis()
        byTabId.values.toList().forEach { entry ->
            val unboundExpired = entry.contentRef == null && now - entry.createdAt > maxAgeMs
            val contentGone = entry.contentRef?.let { it.get() == null } == true
            if (unboundExpired || contentGone) evict(entry.tabId)
        }
    }

    /**
     * Resolves a `projectId` (Project.locationHash) to the currently open
     * [Project], or null when no IDE window matches. Used by HookHttpServer
     * to enforce silent-drop on cross-IDE event leaks.
     */
    fun findProjectByProjectId(projectId: String): Project? =
        ProjectManager.getInstance().openProjects.firstOrNull { it.locationHash == projectId }

    companion object {
        private const val DEFAULT_BIND_WINDOW_MS = 5_000L
        private const val DEFAULT_STALE_MAX_AGE_MS = 60_000L
    }
}
