package com.jw6ventures.headlink

import java.io.File

/** What a file in the drop folder is. */
enum class DropKind { MAP, ROUTING }

/** What a sweep does with one file in the drop folder. */
enum class DropAction {
    /** Not map or routing data; left where it is. */
    IGNORE,

    /** Changed too recently to be trusted as complete; the next sweep looks again. */
    WAIT,

    /** Already installed from this very file; only the delete is still owed. */
    ALREADY,

    /** Copy it in, check it, and remove it from the folder. */
    IMPORT,
}

/**
 * The drop folder's rules, apart from Android's document API so the host tests run them.
 *
 * A file dropped in the folder is moved into app-private storage: copied, checked by the
 * same import the connection screen uses, and then deleted from the folder, so nothing
 * is stored twice. A file that fails its check stays in the folder for the operator to
 * see, and is tried again next time.
 */
object DropRules {
    /**
     * A file modified this recently may still be arriving — a browser download, a sync
     * app, a USB copy — so it waits for the next sweep. Both imports would refuse a
     * truncated file anyway; this saves copying hundreds of megabytes to find out.
     */
    const val QUIET_MS = 30_000L

    fun kind(name: String): DropKind? = when {
        name.endsWith(MapData.SUFFIX, ignoreCase = true) -> DropKind.MAP
        name.endsWith(RoutingData.SUFFIX, ignoreCase = true) -> DropKind.ROUTING
        else -> null
    }

    /** The name [name] installs under, or null when its import would refuse it outright. */
    fun installedName(kind: DropKind, name: String): String? = when (kind) {
        DropKind.MAP -> MapData.safeName(name)
        DropKind.ROUTING -> RoutingData.tileName(name)
    }

    /**
     * [installed] is the file the drop would replace, if there is one. [modifiedMs] is
     * the folder's own modification time for the drop, or 0 when it does not say.
     */
    fun decide(name: String, size: Long, modifiedMs: Long, nowMs: Long, installed: File?): DropAction {
        if (kind(name) == null) return DropAction.IGNORE
        if (modifiedMs > 0 && nowMs - modifiedMs < QUIET_MS) return DropAction.WAIT
        // A copy that succeeded and a delete that did not — a provider that refuses
        // deletes, say — must not cost a full copy on every sweep. Same length and
        // installed no earlier than the drop was last changed means it is this file.
        if (installed != null && installed.isFile && installed.length() == size &&
            modifiedMs > 0 && installed.lastModified() >= modifiedMs
        ) {
            return DropAction.ALREADY
        }
        return DropAction.IMPORT
    }
}
