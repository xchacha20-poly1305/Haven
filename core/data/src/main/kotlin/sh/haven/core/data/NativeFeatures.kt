package sh.haven.core.data

import android.content.Context
import java.io.File

class NativeFeatures(private val context: Context) {

    // Platform type: Android never leaves this null for a real installed
    // package, but it is null for a mocked Context — and answering "no native
    // features" is the truthful response to not being able to look, where
    // throwing would take get_app_info down with it.
    private val nativeLibDir: String?
        get() = context.applicationInfo?.nativeLibraryDir

    private fun has(vararg libs: String): Boolean {
        val dir = nativeLibDir ?: return false
        return libs.all { File(dir, it).canExecute() }
    }

    /** RDP client — `librdp_transport.so`. */
    val rdp: Boolean get() = has("librdp_transport.so")

    /** SPICE client — `libspice_transport.so`. */
    val spice: Boolean get() = has("libspice_transport.so")

    /**
     * Media conversion, preview and streaming. Both binaries are required:
     * the executables are tiny wrappers and the codec code lives in the
     * shared `libav*` libraries, so a partial set is worse than none.
     */

    /**
     * VNC has no native library of its own — the client is Kotlin. It is
     * listed here so callers have one place to ask, and because the *server*
     * side (a desktop worth connecting to) depends on the compositor.
     */
    val vnc: Boolean get() = true

    /** True when this build ships any remote-desktop or compositor payload. */
    val anyDesktop: Boolean get() = rdp || spice

}
