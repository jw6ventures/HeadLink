package org.openautoandroid.source

import org.oscim.backend.AssetAdapter
import java.io.InputStream

/**
 * Reads VTM's own assets — its GLSL shaders and the render theme's symbol SVGs — out of
 * the APK's Java resources.
 *
 * VTM ships [org.oscim.android.AndroidAssets] for this, and it is the wrong adapter for
 * how these files reach us. `vtm`, `vtm-android` and `vtm-themes` are published as plain
 * JARs rather than AARs, so AGP packages their asset entries as classpath
 * resources; nothing puts them into the `AssetManager` namespace, which is filled from
 * `src/main/assets` and from AAR assets only. With `AndroidAssets` installed every
 * shader load returns null and the map draws nothing at all — a failure that looks like
 * a broken GL context rather than a missing file, which is why it is worth stating here.
 */
object MapAssets : AssetAdapter() {
    /**
     * Null when the resource is absent, which is the contract VTM's callers expect.
     *
     * **The `assets/` prefix is the whole difficulty.** VTM asks for paths relative to
     * the assets root — `shaders/line_aa_proj.glsl`, not `assets/shaders/...` — because
     * `AndroidAssets` hands them to an `AssetManager` whose root is that directory. As
     * classpath resources the same files keep their `assets/` path segment, so the
     * prefix has to be put back. Without it VTM starts, loads its native library, builds
     * a framebuffer, and then throws `shader file not found` from deep inside
     * `RenderBuckets.initRenderer` — which is what the first emulator run did.
     *
     * The unprefixed path is tried as well, so an asset supplied by this app rather than
     * by VTM still resolves.
     *
     * Widened from VTM's `protected` so [MapEngine] can load the project's own render
     * themes (`oaa/dashboard-dark.xml` and, since P7.6, `oaa/dashboard-light.xml`) by
     * exactly the same rules VTM loads its shaders by, rather than a second
     * path-resolution scheme that could drift from this one.
     */
    public override fun openFileAsStream(file: String): InputStream? {
        val loader = MapAssets::class.java.classLoader ?: return null
        val relative = file.removePrefix("/")
        return loader.getResourceAsStream("$ASSET_ROOT$relative")
            ?: loader.getResourceAsStream(relative)
    }

    /** Installs this adapter as VTM's global one. Idempotent. */
    fun install() {
        init(MapAssets)
    }

    /**
     * Whether VTM's own shaders are actually reachable. Called once before the first map
     * is built so a packaging mistake is reported as itself, at a point where it can be
     * put in the session report, rather than as an empty map later.
     */
    fun shadersPresent(): Boolean = openFileAsStream("shaders/base_shader.glsl") != null

    /** Where AGP puts a plain JAR dependency's asset entries in the APK. */
    private const val ASSET_ROOT = "assets/"
}
