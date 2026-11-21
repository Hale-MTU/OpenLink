package tech.mmarca.openlink.host

import tech.mmarca.openlink.OpenLinkSettings

/**
 * Hands a caller's settings to the host app (version 3): only the valid ones, and only when there
 * are any. Returns whether the library must answer the caller itself, which it must when nothing
 * reached the app (every entry dropped, or the app threw). Otherwise the app's publish is the
 * answer. [log] gets counts and an exception's class, never a key or a value.
 */
internal fun handOverSettings(
    received: OpenLinkSettings,
    nowMillis: Long,
    log: (String) -> Unit,
    handOver: (OpenLinkSettings) -> Unit,
): Boolean {
    val valid = received.valid(nowMillis)
    val dropped = received.entries.size - valid.entries.size
    if (dropped > 0) log("dropped $dropped settings")
    if (valid.isEmpty) return true
    return runCatching { handOver(valid) }
        .onFailure { log("the host app refused settings: ${it.javaClass.simpleName}") }
        .isFailure
}
