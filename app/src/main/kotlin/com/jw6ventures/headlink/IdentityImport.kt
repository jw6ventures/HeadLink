package com.jw6ventures.headlink

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Validates a supplied identity before atomically replacing the active one. Caller serializes access. */
object IdentityImport {
    const val PEM_LIMIT = 64 * 1024

    @Throws(IOException::class)
    fun install(directory: File, input: InputStream): String {
        val candidate = File(directory, "import-candidate")
        if (!candidate.isDirectory && !candidate.mkdirs()) throw IOException("cannot create identity staging directory")
        val staged = File(candidate, "source-identity.pem")
        try {
            val bytes = input.readNBytes(PEM_LIMIT + 1)
            try {
                if (bytes.isEmpty() || bytes.size > PEM_LIMIT) throw IOException("identity must contain 1–65536 bytes")
                FileOutputStream(staged).use { stream ->
                    if (!staged.setReadable(false, false) || !staged.setWritable(false, false) ||
                        !staged.setReadable(true, true) || !staged.setWritable(true, true)
                    ) throw IOException("cannot restrict identity file permissions")
                    stream.write(bytes)
                    stream.fd.sync()
                }
            } finally {
                bytes.fill(0)
            }
            val report = NativeBridge.ensureIdentity(candidate.absolutePath, false)
            if (!report.lineSequence().any { it.startsWith("tls_self_test=pass ") } ||
                report.lineSequence().any { it.startsWith("identity_error=") }
            ) throw IOException("identity validation failed: ${report.trim()}")
            if (!staged.renameTo(File(directory, "source-identity.pem"))) throw IOException("cannot install identity")
            return report
        } finally {
            staged.delete()
            candidate.delete()
        }
    }
}
