package btools.router

/**
 * Reads the command of a BRouter [VoiceHint], which the vendored tree keeps package-private
 * (P6.5). BRouter's numbering is the one `com.jw6ventures.headlink.TurnCommand` pins.
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
    /** The command BRouter derived for [hint], in `TurnCommand`'s numbering. */
    fun command(hint: VoiceHint): Int = hint.cmd
}
