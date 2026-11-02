package sh.haven.feature.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.TunnelConfigRepository
import sh.haven.core.tunnel.CloudflareAccessTunnel
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val repository: TunnelConfigRepository,
    private val connectionLogRepository: ConnectionLogRepository,
    private val httpClient: OkHttpClient,

) : ViewModel() {

    /**
     * Result of a [testCloudflareAccess] call, surfaced inline next to
     * the "Test connection" button. Typealiased to the top-level
     * [CloudflareTunnelTestResult] so the same composable
     * ([CloudflareInlineFields]) renders results produced either here
     * or in another ViewModel (the SSH profile editor, GH #154).
     */
    @Deprecated(
        "Use CloudflareTunnelTestResult directly",
        ReplaceWith("CloudflareTunnelTestResult", "sh.haven.feature.tunnel.CloudflareTunnelTestResult"),
    )
    sealed class CloudflareAccessTestResult {
        data object Idle : CloudflareAccessTestResult()
        data object Running : CloudflareAccessTestResult()
        data class Success(val message: String) : CloudflareAccessTestResult()
        data class Failure(val message: String) : CloudflareAccessTestResult()
    }

    private val _cfTestResult = MutableStateFlow<CloudflareTunnelTestResult>(
        CloudflareTunnelTestResult.Idle,
    )
    val cfTestResult: StateFlow<CloudflareTunnelTestResult> = _cfTestResult.asStateFlow()

    fun resetCfTestResult() {
        _cfTestResult.value = CloudflareTunnelTestResult.Idle
    }

    /**
     * Build a transient [CloudflareAccessTunnel] from the form's current
     * state and try a single dial+close against the Cloudflare Tunnel
     * endpoint.
     *
     * Success means the WebSocket upgrade completed. Failure carries the
     * diagnostic detail produced by `CloudflareAccessTunnel.mapFailure` —
     * typically status code + `cf-ray` + a body excerpt for Cloudflare
     * error pages. The 302→`/cdn-cgi/access/login` case is mapped to a
     * "this route needs Access auth, sign in" hint.
     *
     * JWT is optional — unprotected Tunnel routes need only a hostname.
     *
     * Writes a row to the Connection Log either way so the user has a
     * persistent record after the inline UI clears. The synthetic
     * profileId `cf-tunnel-test:<hostname>` keeps the row out of the
     * per-profile log lookups but visible in the global summary view.
     */
    fun testCloudflareAccess(hostname: String, jwt: String, jumpDestination: String = "") {
        val trimmedHost = hostname.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        if (trimmedHost.isEmpty()) {
            _cfTestResult.value = CloudflareTunnelTestResult.Failure(
                "Hostname required to test the Cloudflare Tunnel connection",
            )
            return
        }
        _cfTestResult.value = CloudflareTunnelTestResult.Running
        viewModelScope.launch {
            val syntheticId = "cf-tunnel-test:$trimmedHost"
            val started = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    val tunnel = CloudflareAccessTunnel.forTest(
                        hostname = trimmedHost,
                        jwt = jwt.trim(),
                        jumpDestination = jumpDestination.trim(),
                        httpClient = httpClient.newBuilder()
                            .callTimeout(8, TimeUnit.SECONDS)
                            .build(),
                    )
                    try {
                        tunnel.dial(trimmedHost, 22, 8_000).close()
                    } finally {
                        tunnel.close()
                    }
                }
                val msg = if (jwt.isBlank()) {
                    "Cloudflare Tunnel reachable; WebSocket upgrade succeeded (no Access auth)."
                } else {
                    "Cloudflare Tunnel reachable; WebSocket upgrade succeeded with Access JWT."
                }
                _cfTestResult.value = CloudflareTunnelTestResult.Success(msg)
                connectionLogRepository.logEvent(
                    profileId = syntheticId,
                    status = ConnectionLog.Status.CONNECTED,
                    durationMs = System.currentTimeMillis() - started,
                    details = "Cloudflare Tunnel test: $msg",
                )
            } catch (e: Throwable) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _cfTestResult.value = CloudflareTunnelTestResult.Failure(msg)
                connectionLogRepository.logEvent(
                    profileId = syntheticId,
                    status = ConnectionLog.Status.FAILED,
                    durationMs = System.currentTimeMillis() - started,
                    details = "Cloudflare Tunnel test failed: $msg",
                )
            }
        }
    }

    /**
     * Standalone tunnels only — Cloudflare Tunnels embedded on an SSH
     * profile (GH #154) are hidden from this list; the user manages those
     * through the SSH profile editor.
     */

}
