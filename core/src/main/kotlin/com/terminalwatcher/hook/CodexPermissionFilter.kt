package com.terminalwatcher.hook

/**
 * Codex runs PermissionRequest hooks before its own full-access auto-approval, so a hook
 * still fires for requests that never reach the user. Codex reports approval policy
 * "never" as `bypassPermissions`; under that policy it never shows an approval prompt.
 */
internal fun isCodexAutoApprovedPermissionRequest(tool: String, payload: HookPayload): Boolean =
    tool == "codex" &&
        payload.hookEventName == "PermissionRequest" &&
        payload.permissionMode == "bypassPermissions"
