package sh.haven.core.tunnel

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.db.entities.typeEnum
import sh.haven.core.data.repository.TunnelConfigRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TunnelManager"

/**
 * Constructs a live [Tunnel] from a stored [TunnelConfig]. Extracted
 * behind an interface so tests can swap in fakes — production code goes
 * through the native gomobile bridges, which can't be exercised from a
 * JVM-only unit test without Robolectric.
 */
interface TunnelFactory {
    fun create(config: TunnelConfig): Tunnel
}

@Singleton
class TunnelManager @Inject constructor(
    private val tunnelConfigRepository: TunnelConfigRepository,
    private val tunnelFactory: TunnelFactory,
) {
    private val mutex = Mutex()
    private val liveTunnels = mutableMapOf<String, Tunnel>()

    private val _live = MutableStateFlow<Map<String, Tunnel>>(emptyMap())
    val live: StateFlow<Map<String, Tunnel>> = _live.asStateFlow()

    /** Caller must hold [mutex]. */
    private fun publish() {
        _live.value = liveTunnels.toMap()
    }
    /** configId → set of profileIds currently depending on this tunnel. */
    private val dependents = mutableMapOf<String, MutableSet<String>>()
    /** profileId → the configId it last acquired. One tunnel per profile, mirroring the data model. */
    private val acquiredBy = mutableMapOf<String, String>()

    /**
     * Get-or-create the tunnel for [configId] and register [profileId] as
     * a dependent. Returns null if the config was deleted from storage —
     * callers should treat that as "fall through to direct dialling".
     *
     * Idempotent: re-acquiring with the same (configId, profileId) pair
     * returns the same tunnel and leaves the dependent set unchanged.
     *
     * If [profileId] previously acquired a *different* config, that prior
     * slot is auto-released first (and its tunnel torn down if it had no
     * other dependents). Profiles only ever hold one tunnel at a time.
     */
    suspend fun acquire(configId: String, profileId: String): Tunnel? {
        var staleToClose: Tunnel? = null
        var staleDead: Tunnel? = null
        val acquired = mutex.withLock {
            val previous = acquiredBy[profileId]
            if (previous != null && previous != configId) {
                Log.w(TAG, "Profile $profileId switching tunnel $previous → $configId without explicit release; auto-releasing prior")
                staleToClose = internalRelease(previous, profileId)
            }
            liveTunnels[configId]?.takeIf { it.isDead }?.let { dead ->
                liveTunnels.remove(configId)
                staleDead = dead
            }
            val tunnel = liveTunnels[configId] ?: run {
                val config = tunnelConfigRepository.getById(configId) ?: return@withLock null
                withContext(Dispatchers.IO) { tunnelFactory.create(config) }
                    .also { liveTunnels[configId] = it }
            }
            dependents.getOrPut(configId) { mutableSetOf() }.add(profileId)
            acquiredBy[profileId] = configId
            publish()
            tunnel
        }
        staleToClose?.close()
        staleDead?.close()
        return acquired
    }

    /**
     * Release whatever tunnel [profileId] currently holds. Tears down the
     * underlying tunnel only if this was its last dependent. No-op if the
     * profile has no live acquire.
     *
     * The actual tunnel close happens *outside* the manager's lock — close
     * may make a native call (gomobile) and we don't want to hold the lock
     * for that.
     */
    suspend fun release(profileId: String) {
        val toClose = mutex.withLock {
            val configId = acquiredBy[profileId] ?: return@withLock null
            internalRelease(configId, profileId)
        }
        // (internalRelease publishes when it removes a tunnel.)
        toClose?.close()
    }

    /**
     * Caller must hold [mutex]. Returns the [Tunnel] that should be closed
     * outside the lock if this drained the dependent set, or null otherwise.
     */
    private fun internalRelease(configId: String, profileId: String): Tunnel? {
        acquiredBy.remove(profileId)
        val deps = dependents[configId] ?: return null
        deps.remove(profileId)
        if (deps.isEmpty()) {
            dependents.remove(configId)
            if (liveTunnels[configId]?.lingerAfterRelease == true) return null
            return liveTunnels.remove(configId).also { publish() }
        }
        return null
    }

    suspend fun disconnect(configId: String, signOut: Boolean = false): Boolean {
        val tunnel = mutex.withLock {
            val t = liveTunnels.remove(configId) ?: return@withLock null
            dependents.remove(configId)?.forEach { acquiredBy.remove(it) }
            publish()
            t
        } ?: return false
        tunnel.close()
        return true
    }

    /** Test / introspection — number of live dependents on the given config. */
    suspend fun dependentCount(configId: String): Int = mutex.withLock {
        dependents[configId]?.size ?: 0
    }

    /**
     * Snapshot of the live tunnel state — every configId that currently
     * has at least one active dependent, paired with the set of profile
     * ids holding it. For agent / debug tooling that wants to verify
     * refcount semantics end-to-end (#149 integration tests).
     */
    suspend fun liveSnapshot(): List<LiveTunnelEntry> = mutex.withLock {
        dependents
            .filterValues { it.isNotEmpty() }
            .map { (configId, deps) -> LiveTunnelEntry(configId, deps.toSet()) }
    }
}

/** A live tunnel as visible to introspection: the config it came from, and the profiles depending on it. */
data class LiveTunnelEntry(
    val configId: String,
    val dependentProfileIds: Set<String>,
)

@Singleton
class DefaultTunnelFactory @Inject constructor(
    private val httpClient: OkHttpClient,
) : TunnelFactory {
    override fun create(config: TunnelConfig): Tunnel = when (config.typeEnum) {
        TunnelConfigType.CLOUDFLARE_ACCESS -> {
            val parsed = CloudflareAccessConfigBlob.parse(config.configText)
            CloudflareAccessTunnel(
                hostname = parsed.hostname,
                jwt = parsed.jwt,
                jumpDestination = parsed.jumpDestination,
                httpClient = httpClient,
            )
        }
        else -> throw IOException("Built-in VPN support has been removed. Edit this connection to use the system network or an external proxy.")
    }
}
