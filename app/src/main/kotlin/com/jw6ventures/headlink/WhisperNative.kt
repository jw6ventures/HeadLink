package com.jw6ventures.headlink

/**
 * The JNI surface of `libwhisper_jni.so` (app/src/main/cpp/whisper_jni.cpp), whisper.cpp
 * built by tools/whisper-build.sh. Plain JVM, so the host test can load the x86_64 build.
 * Only [WhisperEngine] calls the `native*` functions; they are not thread-safe for one
 * handle, and [WhisperEngine] serialises them.
 *
 * Load the library through [loadError] only after [SpeechCpu.supported] said yes: the
 * arm64 build uses Armv8.2-A half-precision and dot-product instructions.
 */
object WhisperNative {
    /** Null once the library is loaded; otherwise why loading failed. Loads once. */
    val loadError: String? by lazy {
        try {
            System.loadLibrary("whisper_jni")
            nativeQuiet()
            null
        } catch (e: UnsatisfiedLinkError) {
            e.message ?: e.toString()
        }
    }

    /** whisper.cpp's version string, or null when the library is not loaded. */
    fun version(): String? = if (loadError == null) nativeVersion() else null

    @JvmStatic external fun nativeQuiet()

    /**
     * Reads a whole model from [fd], from its current position. Does not close [fd].
     * Returns a handle, or 0 when the file is not a whisper.cpp model it can load.
     */
    @JvmStatic external fun nativeLoad(fd: Int): Long

    /** `"<size> en"` or `"<size> multi"`; the size is whisper.cpp's name (tiny, base, small, ...). */
    @JvmStatic external fun nativeDescribe(handle: Long): String?

    /** The joined segments, or null on failure or cancel. */
    @JvmStatic external fun nativeTranscribe(
        handle: Long,
        samples: ShortArray,
        count: Int,
        prompt: String?,
        language: String,
        threads: Int,
    ): String?

    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeFree(handle: Long)

    @JvmStatic external fun nativeVersion(): String
}

/**
 * Whether this phone's processor runs the arm64 build: it needs the `asimdhp` (FP16
 * arithmetic) and `asimddp` (dot product) features, present on every core since
 * Cortex-A55/A75. Read from `/proc/cpuinfo` before the library is loaded, so an older core
 * gets a message rather than a crash.
 */
object SpeechCpu {
    private val REQUIRED = listOf("asimdhp", "asimddp")

    /** The features of [REQUIRED] missing from every `Features` line of [cpuinfo]; empty when supported. */
    fun missing(cpuinfo: String): List<String> {
        val lines = cpuinfo.lineSequence()
            .filter { it.substringBefore(':').trim().equals("Features", ignoreCase = true) }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            .toList()
        if (lines.isEmpty()) return REQUIRED
        // Every core must have them: a thread may run on any of them.
        return REQUIRED.filter { feature -> lines.any { feature !in it } }
    }
}
