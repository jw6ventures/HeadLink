package com.jw6ventures.headlink

import java.io.File
import java.io.IOException

/**
 * Head-unit certificate pins for the certificate trials (spec §7.4: C2 first use, C3
 * repeat), kept app-private beside the source identity and excluded from backup.
 * Fingerprints are nonsecret SHA-256 hex. First-use trust itself is never stored: an
 * attempt armed for first use reports the fingerprint it accepted, and only an attempt
 * whose TLS handshake completed adds it here. Pure JVM, so tools/jni-host-test runs it.
 */
class TrustStore(val directory: File) {
    private val pinFile = File(directory, PIN_FILE)

    /** Pinned fingerprints, oldest first. Unreadable or malformed lines are ignored. */
    fun pins(): List<String> =
        try {
            if (pinFile.isFile) {
                pinFile.readLines(Charsets.US_ASCII).map { it.trim() }.filter(FINGERPRINT::matches).distinct()
                    .takeLast(PIN_LIMIT)
            } else {
                emptyList()
            }
        } catch (e: IOException) {
            emptyList()
        }

    /** Adds [fingerprint], keeping the newest [PIN_LIMIT]. Returns whether it was new. */
    fun addPin(fingerprint: String): Boolean {
        require(FINGERPRINT.matches(fingerprint)) { "invalid fingerprint" }
        val current = pins()
        if (fingerprint in current) return false
        write((current + fingerprint).takeLast(PIN_LIMIT))
        return true
    }

    fun clearPins() {
        write(emptyList())
    }

    private fun write(pins: List<String>) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")
        val temporary = File(directory, "$PIN_FILE.tmp")
        temporary.writeText(pins.joinToString("") { "$it\n" }, Charsets.US_ASCII)
        if (!temporary.renameTo(pinFile)) {
            temporary.delete()
            throw IOException("rename failed for $PIN_FILE")
        }
    }

    companion object {
        const val PIN_FILE = "head-unit-pins.txt"
        const val PIN_LIMIT = 8
        val FINGERPRINT = Regex("[0-9a-f]{64}")

        /**
         * The head-unit fingerprint a finished native report says first-use trust accepted,
         * or null unless the TLS handshake also completed (the peer proved possession of
         * the certificate's key).
         */
        fun acceptedFirstUse(report: Map<String, String>): String? {
            if (report["tls_established"] != "true") return null
            val parts = report["tls_peer_decision"]?.split(' ') ?: return null
            return parts.getOrNull(1)?.takeIf { parts.size == 2 && parts[0] == "first-use-accepted" && FINGERPRINT.matches(it) }
        }
    }
}
