package btools.router

/**
 * Reads the parts of a BRouter [VoiceHint] that the vendored tree keeps package-private
 * (P6.5): which command it is, and BRouter's own names for each command.
 *
 * **This file is ours, not BRouter's.** It sits in package `btools.router` because that is
 * the only way to read `cmd` without editing the vendored source, which is held
 * byte-identical to its pin (`vendor/brouter/README.md`). The other way in, parsing
 * `FormatJson` or `FormatGpx` output, reads the same field through a string format and
 * fails at run time if upstream changes it; this one fails at **compile** time, which is
 * where a re-sync should find out. Android has no module system to object to a split
 * package, and the host tests put the vendored jar and this class on one classpath.
 *
 * Only reads. Nothing here changes what BRouter computed.
 */
object VoiceHintReader {
    const val CONTINUE = VoiceHint.C
    const val TURN_LEFT = VoiceHint.TL
    const val TURN_SLIGHT_LEFT = VoiceHint.TSLL
    const val TURN_SHARP_LEFT = VoiceHint.TSHL
    const val TURN_RIGHT = VoiceHint.TR
    const val TURN_SLIGHT_RIGHT = VoiceHint.TSLR
    const val TURN_SHARP_RIGHT = VoiceHint.TSHR
    const val KEEP_LEFT = VoiceHint.KL
    const val KEEP_RIGHT = VoiceHint.KR
    const val U_TURN_LEFT = VoiceHint.TLU
    const val U_TURN_RIGHT = VoiceHint.TRU
    const val OFF_ROUTE = VoiceHint.OFFR
    const val ROUNDABOUT = VoiceHint.RNDB
    const val ROUNDABOUT_LEFT = VoiceHint.RNLB
    const val U_TURN_180 = VoiceHint.TU
    const val BEELINE = VoiceHint.BL
    const val EXIT_LEFT = VoiceHint.EL
    const val EXIT_RIGHT = VoiceHint.ER
    const val END = VoiceHint.END

    /** The command BRouter derived for [hint]: one of the constants above. */
    fun command(hint: VoiceHint): Int = hint.cmd
}
