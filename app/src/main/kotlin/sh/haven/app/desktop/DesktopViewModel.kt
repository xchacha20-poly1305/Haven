package sh.haven.app.desktop

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.desktop.DesktopStatus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.knock.KnockSequence
import sh.haven.core.knock.PortKnocker
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.glkvm.GlkvmClient
import sh.haven.core.glkvm.GlkvmScancodes
import sh.haven.core.glkvm.GlkvmTrust
import sh.haven.core.rdp.RdpSession
import sh.haven.core.spice.SpiceSession
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshConnection
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ui.CursorOverlay
import sh.haven.core.tunnel.TunnelResolver
import sh.haven.core.tunnel.TunneledConnection
import sh.haven.core.tunnel.TunneledSocket
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncClient
import sh.haven.core.vnc.VncConfig
import sh.haven.feature.rdp.describeRdpError
import sh.haven.feature.vnc.VncViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import sh.haven.core.redact.LogRedact

private const val TAG = "DesktopViewModel"

private sealed class DesktopStartOutcome {
    object Ready : DesktopStartOutcome()
    object Timeout : DesktopStartOutcome()
    data class Error(val message: String) : DesktopStartOutcome()
}

@HiltViewModel
class DesktopViewModel @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val connectionLogRepository: ConnectionLogRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
    private val tunnelResolver: TunnelResolver,
    private val portKnocker: PortKnocker,
    private val uiCommandBus: sh.haven.core.data.navigation.UiCommandBus,

    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,

    private val tlsCertVerifier: sh.haven.core.data.security.TlsCertVerifier,

) : ViewModel() {

    init {

        // fires. The matching pager switch happens in HavenNavHost.
        // DesktopViewModel is hoisted to nav scope so emissions always
        // land regardless of which tab the user is currently viewing.
        viewModelScope.launch {
            uiCommandBus.commands.collect { command ->
                when (command) {
                    is sh.haven.core.data.navigation.UiCommand.OpenRemoteDesktop ->
                        openRemoteDesktopForProfile(command.profileId)
                    else -> { /* handled by other collectors */ }
                }
            }
        }
    }

    /**
     * Resolve [profileId] to a VNC or RDP profile and dispatch to the
     * matching `add*Session`. Used by the workspace launcher; for
     * tunneled profiles, picks the first connected SSH session for the
     * tunnel profile and lets `addVncSession` / `addRdpSession` throw
     * the existing "SSH session not found" error if none is up.
     */
    private fun openRemoteDesktopForProfile(profileId: String) {
        viewModelScope.launch {
            val profile = connectionRepository.getById(profileId)
            if (profile == null) {
                Log.w(TAG, "OpenRemoteDesktop: profile $profileId not found")
                return@launch
            }
            when {
                profile.isVnc -> {
                    val sshSessionId =
                        if (profile.vncSshForward && profile.vncSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.vncSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addVncSession(
                        host = profile.host,
                        port = profile.vncPort ?: 5900,
                        password = profile.vncPassword,
                        username = profile.vncUsername,
                        sshForward = profile.vncSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        colorDepth = profile.vncColorDepth,
                    )
                }
                profile.isRdp -> {
                    val sshSessionId =
                        if (profile.rdpSshForward && profile.rdpSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.rdpSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addRdpSession(
                        host = profile.host,
                        port = profile.rdpPort,
                        username = profile.rdpUsername.orEmpty(),
                        password = profile.rdpPassword.orEmpty(),
                        domain = profile.rdpDomain.orEmpty(),
                        sshForward = profile.rdpSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        useNla = profile.rdpUseNla,
                        colorDepth = profile.rdpColorDepth,
                    )
                }
                profile.isSpice -> {
                    val sshSessionId =
                        if (profile.spiceSshForward && profile.spiceSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.spiceSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addSpiceSession(
                        host = profile.host,
                        port = profile.spicePort ?: 5900,
                        password = profile.spicePassword,
                        sshForward = profile.spiceSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                    )
                }
                profile.isGlkvm -> {
                    val sshSessionId =
                        if (profile.glkvmSshForward && profile.glkvmSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.glkvmSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addGlkvmSession(
                        host = profile.host,
                        port = profile.glkvmPort ?: 443,
                        user = profile.username,
                        password = profile.glkvmPassword,
                        targetLayout = profile.glkvmTargetLayout,
                        sshForward = profile.glkvmSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                    )
                }
                else -> Log.w(
                    TAG,
                    "OpenRemoteDesktop: ${profile.label} is ${profile.connectionType}, not VNC/RDP/SPICE/GLKVM",
                )
            }
        }
    }

    /**
     * Transient user-facing messages from background tasks (desktop start
     * failures, etc.) that need to surface as a Toast/Snackbar. Collected
     * by DesktopScreen. SharedFlow with replay=0 so a screen rotation
     * doesn't re-fire the same message.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    // --- "Open USB drive" in a VM (#287) — keyed by busid, up to
    // QemuManager.MAX_CONCURRENT_DRIVES concurrently OPENING/READY.
    private val _tabs = MutableStateFlow<List<DesktopTab>>(emptyList())
    val tabs: StateFlow<List<DesktopTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    /** The currently active tab, derived for convenience. */
    val activeTab: StateFlow<DesktopTab?> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Whether the active tab is connected (used to disable pager swipe). */
    val activeTabConnected: StateFlow<Boolean> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)?.connected?.value == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Tab management ---

    /**
     * Test seam: install [tab] as the sole active tab without a live connect,
     * so the input-ordering of the typing verbs can be asserted against a mock
     * client (#700). Production tab creation goes through addVncSession et al.
     */
    @androidx.annotation.VisibleForTesting
    internal fun addTabForTest(tab: DesktopTab) {
        _tabs.value = listOf(tab)
        _activeTabIndex.value = 0
    }

    fun selectTab(index: Int) {
        val tabs = _tabs.value
        if (index in tabs.indices) {
            pauseAllExcept(index)
            _activeTabIndex.value = index
        }
    }

    fun moveTab(fromIndex: Int, direction: Int) {
        val tabs = _tabs.value.toMutableList()
        val toIndex = fromIndex + direction
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return
        val tab = tabs.removeAt(fromIndex)
        tabs.add(toIndex, tab)
        _tabs.value = tabs
        if (_activeTabIndex.value == fromIndex) _activeTabIndex.value = toIndex
        else if (_activeTabIndex.value == toIndex) _activeTabIndex.value = fromIndex
    }

    /**
     * User dismissed the bandwidth-suggestion banner — clear it; the
     * session-side bandwidthSuggestionFired flag stops it re-firing.
     */
    fun dismissBandwidthSuggestion(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } as? DesktopTab.Vnc ?: return
        tab._bandwidthSuggestion.value = null
    }

    /**
     * User accepted the bandwidth-suggestion banner — persist the new
     * colour depth on the profile (if any), close the existing tab, and
     * reconnect with the new depth.
     */
    fun acceptBandwidthSuggestion(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } as? DesktopTab.Vnc ?: return
        val newDepth = tab._bandwidthSuggestion.value ?: return
        val pid = tab.profileId
        viewModelScope.launch(Dispatchers.IO) {
            if (pid != null) {
                connectionRepository.getById(pid)?.let { existing ->
                    if (existing.vncColorDepth != newDepth) {
                        connectionRepository.save(existing.copy(vncColorDepth = newDepth))
                    }
                }
            }
            // Snapshot before close, since closeTab disposes the tab.
            val host = tab.originalHost.ifEmpty { return@launch }
            val port = tab.originalPort
            val username = tab.originalUsername
            val password = tab.originalPassword
            val sshForward = tab.sshForward
            val sshSessionId = tab.sshSessionId
            withContext(Dispatchers.Main) { closeTab(tabId) }
            addVncSession(
                host = host,
                port = port,
                password = password,
                username = username,
                sshForward = sshForward,
                sshSessionId = sshSessionId,
                profileId = pid,
                colorDepth = newDepth,
            )
        }
    }

    /**
     * Reconnect a tab that hit "connection lost" (e.g. no server listening),
     * from the inline Retry button — so a dead desktop isn't a long-press dead
     * end (#121, KoriKraut). Profile-backed tabs re-run the full connect via the
     * UiCommand bus (same path a tap uses), which re-establishes the SSH
     * tunnel with a fresh session instead of reusing the torn-down one. Ad-hoc
     * VNC tabs (no profile) fall back to re-dialling the saved original params,
     * mirroring [acceptBandwidthSuggestion].
     */
    fun retryTab(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } ?: return
        val profileId = when (tab) {
            is DesktopTab.Vnc -> tab.profileId
            is DesktopTab.Rdp -> tab.profileId
            is DesktopTab.Spice -> tab.profileId
            is DesktopTab.Glkvm -> tab.profileId
            else -> null
        }
        if (profileId != null) {
            closeTab(tabId)
            uiCommandBus.emit(
                sh.haven.core.data.navigation.UiCommand.ConnectProfile(profileId),
            )
            return
        }
        // Ad-hoc VNC tab with no backing profile — re-dial the original params.
        if (tab is DesktopTab.Vnc) {
            val host = tab.originalHost.ifEmpty { return }
            val port = tab.originalPort
            val username = tab.originalUsername
            val password = tab.originalPassword
            val sshForward = tab.sshForward
            val sshSessionId = tab.sshSessionId
            val colorDepth = tab.colorDepth
            closeTab(tabId)
            addVncSession(
                host = host,
                port = port,
                password = password,
                username = username,
                sshForward = sshForward,
                sshSessionId = sshSessionId,
                profileId = null,
                colorDepth = colorDepth,
            )
        }
    }

    fun closeTab(tabId: String) {
        val tabs = _tabs.value.toMutableList()
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        disconnectTab(tab)
        _tabs.value = tabs
        if (_activeTabIndex.value >= tabs.size && tabs.isNotEmpty()) {
            _activeTabIndex.value = tabs.size - 1
        }
        pauseAllExcept(_activeTabIndex.value)
    }

    // --- Tab deduplication ---

    /**
     * Find an existing tab matching a connection. Matches by profileId first,
     * then by host:port for VNC or host:port:username for RDP.
     * Returns the tab index, or -1 if not found.
     */
    private fun findExistingTab(
        profileId: String?,
        host: String,
        port: Int,
        protocol: String,
        username: String? = null,
    ): Int {
        val tabs = _tabs.value
        // Match by profileId if available
        if (profileId != null) {
            val idx = tabs.indexOfFirst { tab ->
                when (tab) {
                    is DesktopTab.Vnc -> tab.profileId == profileId
                    is DesktopTab.Rdp -> tab.profileId == profileId
                    is DesktopTab.Spice -> tab.profileId == profileId
                    is DesktopTab.Glkvm -> tab.profileId == profileId
                    else -> false
                }
            }
            if (idx >= 0) return idx
        }
        // Match by host+port (and username for RDP)
        return tabs.indexOfFirst { tab ->
            when {
                protocol == "VNC" && tab is DesktopTab.Vnc && tab.profileId == null ->
                    tab.label == "$host:$port"
                protocol == "RDP" && tab is DesktopTab.Rdp && tab.profileId == null ->
                    tab.label == "$host:$port"
                protocol == "SPICE" && tab is DesktopTab.Spice && tab.profileId == null ->
                    tab.label == "$host:$port"
                protocol == "GLKVM" && tab is DesktopTab.Glkvm && tab.profileId == null ->
                    tab.label == "$host:$port"
                else -> false
            }
        }
    }

    // --- VNC sessions ---

    fun addVncSession(
        host: String,
        port: Int,
        password: String?,
        username: String? = null,
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
        colorDepth: String = "BPP_24_TRUE",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Deduplicate: if a tab for the same connection exists, reuse or replace
            val existingIdx = findExistingTab(profileId, host, port, "VNC")
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    // Already connected — just switch to it
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                // Disconnected/errored — close old tab before creating new one
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            // Per-tab live state, declared up front so the tab can be shown in
            // a connecting state (connected=false, no frame) before the
            // synchronous handshake runs — mirroring RDP, which previously was
            // the only protocol to show "connecting".
            val connected = MutableStateFlow(false)
            val frame = MutableStateFlow<Bitmap?>(null)
            val error = MutableStateFlow<String?>(null)
            val cursor = MutableStateFlow<CursorOverlay?>(null)
            val pointerPos = MutableStateFlow(0 to 0)
            val bandwidthSuggestion = MutableStateFlow<String?>(null)
            val remoteClipboard = MutableStateFlow<String?>(null)

            // Hoisted out of try so the catch / onError can clean it up
            // when the dial fails (#121). tunnelLease owns the forward +
            // dependent release + the parent-gone teardown callback.
            var tunnelLease: SshSessionManager.TunnelLease? = null
            var tabAdded = false
            try {
                val actualHost: String
                val actualPort: Int
                // WireGuard / Tailscale TunneledConnection — non-null when
                // the profile has tunnelConfigId set and we're not going
                // through SSH RemoteForward instead.
                var tunneledConn: TunneledConnection? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    // Forward target as seen FROM the SSH server. When the VNC
                    // server is the jump host itself (#104: wayvnc binds
                    // loopback only, and dialing the host's own external
                    // address back through sshd hits ECONNREFUSED), the target
                    // must be 127.0.0.1 — detected by the VNC host matching
                    // the jump profile's host. Any OTHER value is a distinct
                    // machine behind the jump host and must be honoured
                    // verbatim; hardcoding loopback here sent every jump-host
                    // topology to the wrong machine (#538). RDP/SPICE below
                    // already pass the host through.
                    val jumpHost = profileId?.let { pid ->
                        connectionRepository.getById(pid)?.vncSshProfileId
                            ?.let { connectionRepository.getById(it)?.host }
                    }
                    val target = vncForwardTarget(host, jumpHost)
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, target, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "VNC SSH tunnel: localhost:$lp -> ${LogRedact.of(target)}:$port (via ${LogRedact.of(host)})")
                    // Tie this tab to the SSH session: if the SSH is torn down
                    // for any reason (Connections-list disconnect, network
                    // death, jump cascade), the lease fires and closes the tab
                    // instead of leaving it over a dead pipe (#121).
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                    // Try the WireGuard / Tailscale path. Returns null when
                    // the profile has no tunnelConfigId — caller falls
                    // through to a direct kernel-socket dial in client.start.
                    if (profileId != null) {
                        val profile = connectionRepository.getById(profileId)
                        if (profile != null) {
                            tunneledConn = tunnelResolver.dial(profile, actualHost, actualPort, 30_000)
                            if (tunneledConn != null) {
                                Log.d(TAG, "VNC dialed via tunnel ${profile.tunnelConfigId} -> $actualHost:$actualPort")
                            }
                        }
                    }
                }

                val isLocalGuest = profileId == null &&
                    !sshForward &&
                    (host == "localhost" || host == "127.0.0.1")

                val config = VncConfig().apply {
                    this.colorDepth = runCatching { ColorDepth.valueOf(colorDepth) }
                        .getOrDefault(ColorDepth.BPP_24_TRUE)
                    shared = true
                    if (!password.isNullOrEmpty()) passwordSupplier = { password }
                    if (!username.isNullOrEmpty()) usernameSupplier = { username }
                    onScreenUpdate = { bitmap -> frame.value = bitmap }
                    onCursorUpdate = { bmp, hx, hy ->
                        cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
                    }
                    onBandwidthSuggestion = { suggested ->
                        // Only surface if the global preference is on. If
                        // the user's already dismissed the banner this
                        // session, the StateFlow is set to null and the
                        // session-side flag (bandwidthSuggestionFired)
                        // prevents re-firing.
                        viewModelScope.launch {
                            if (preferencesRepository.bandwidthAutoSuggest.first()) {
                                bandwidthSuggestion.value = suggested.name
                            }
                        }
                    }
                    onError = { e ->
                        Log.e(TAG, "VNC error on tab $tabId", e)
                        error.value = VncViewModel.describeError(e, host, port)
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                        // VncClient.start() catches setup failures (e.g. the
                        // handshake EOF when no VNC server is listening behind
                        // the SSH forward) and routes them here instead of
                        // throwing, so the catch block below never runs for
                        // them — and a mid-session drop lands here too. Release
                        // the SSH tunnel (lease) + any WG/Tailscale dependent so
                        // nothing is left orphaned with a green dot (#121).
                        // Idempotent, so the later disconnectTab stays safe.
                        tunnelLease?.close()
                        releaseSshTunnelDependent(profileId)
                    }
                    onRemoteClipboard = { text ->
                        // Length only. A clipboard is where a password manager puts
                        // the password, and this line put 50 characters of it in
                        // logcat for anyone who reads a bug report (#518).
                        Log.d(TAG, "VNC clipboard ($tabId): ${text.length} chars")
                        if (text.isNotEmpty()) {
                            if (isLocalGuest) {
                                remoteClipboard.value = text
                            } else {
                                viewModelScope.launch {
                                    if (preferencesRepository.remoteClipboardToLocalEnabled.first()) {
                                        remoteClipboard.value = text
                                    }
                                }
                            }
                        }
                    }
                }

                val client = VncClient(config)

                // Add the tab now (connected=false) so the Desktop screen
                // shows a connecting state during the (synchronous) handshake,
                // like RDP does via onConnected. The handshake below flips
                // _connected in place. A connected tab keeps its lease so it's
                // torn down with the SSH session; a failed handshake leaves the
                // error visible (onError already released the lease, and that
                // release removed the lease before any SSH teardown could fire
                // its parent-gone callback, so the error tab isn't auto-closed).
                val newTab = DesktopTab.Vnc(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    client = client,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    _bandwidthSuggestion = bandwidthSuggestion,
                    _remoteClipboard = remoteClipboard,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                    originalHost = host,
                    originalPort = port,
                    originalUsername = username,
                    originalPassword = password,
                    sshForward = sshForward,
                    sshSessionId = sshSessionId,
                    colorDepth = colorDepth,
                )
                _tabs.value = _tabs.value.toMutableList().apply { add(newTab) }
                tabAdded = true
                pauseAllExcept(_tabs.value.size - 1)
                _activeTabIndex.value = _tabs.value.size - 1
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)



                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }

                val tc = tunneledConn
                if (tc != null) {
                    client.start(TunneledSocket(tc, actualHost, actualPort), actualHost)
                } else {
                    // Knock only on the direct path. SSH-forward goes via
                    // a localhost tunnel (handled at the SSH connect site)
                    // and a userspace WG/Tailscale tunnel doesn't expose
                    // raw kernel sockets for knockd to see.
                    if (!sshForward && profileId != null) {
                        runVncKnockIfConfigured(profileId, actualHost)
                    }
                    client.start(actualHost, actualPort)
                }
                // start() runs the handshake synchronously and swallows setup
                // failures into onError (above), which has flagged the error +
                // set the registry to ERROR. Only flip to connected on success.
                if (error.value == null) {
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VNC connect failed", e)
                // Connect-failure cleanup (#121): release the SSH tunnel lease
                // (removes the forward + releases the dependent, tearing down
                // the SSH iff it was opened solely for this tunnel) and any
                // WG/Tailscale dependent. Idempotent / no-op if the failure
                // happened before the lease was acquired.
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                error.value = VncViewModel.describeError(e, host, port)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                // If the failure happened before the tab was added (e.g. tunnel
                // setup threw), surface an error tab so the user sees why.
                if (!tabAdded) {
                    val errorTab = DesktopTab.Vnc(
                        id = tabId,
                        label = label,
                        colorTag = colorTag,
                        client = VncClient(VncConfig()),
                        _connected = connected,
                        _frame = frame,
                        _error = error,
                        _cursor = cursor,
                        _pointerPos = pointerPos,
                        _bandwidthSuggestion = bandwidthSuggestion,
                        profileId = profileId,
                    )
                    val tabs = _tabs.value.toMutableList()
                    tabs.add(errorTab)
                    _tabs.value = tabs
                    _activeTabIndex.value = tabs.size - 1
                }
            }
        }
    }

    // --- RDP sessions ---

    fun addRdpSession(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String = "",
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
        useNla: Boolean = true,
        colorDepth: Int = 16,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Deduplicate: if a tab for the same connection exists, reuse or replace
            val existingIdx = findExistingTab(profileId, host, port, "RDP", username)
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            // Hoisted out of try so the catch / onError can clean it up
            // when the dial fails (#121). tunnelLease owns the forward +
            // dependent release + the parent-gone teardown callback.
            var tunnelLease: SshSessionManager.TunnelLease? = null
            try {
                val actualHost: String
                val actualPort: Int

                // SOCKS5 endpoint of any WireGuard / Tailscale tunnel the
                // profile selected (#149 step 4 + 9). Only consulted when
                // not going through SSH RemoteForward — that already
                // tunnels the connection via 127.0.0.1:<localPort>.
                var rdpSocksProxy: sh.haven.rdp.SocksProxyConfig? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "RDP SSH tunnel: localhost:$lp -> ${LogRedact.host(host, port)}")
                    // Tie this tab to the SSH session so it closes if the SSH
                    // is torn down for any reason (#121).
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                    if (profileId != null) {
                        val profile = connectionRepository.getById(profileId)
                        if (profile != null) {
                            tunnelResolver.socksEndpoint(profile)?.let { addr ->
                                rdpSocksProxy = sh.haven.rdp.SocksProxyConfig(
                                    host = addr.hostString,
                                    port = addr.port.toUShort(),
                                )
                                Log.d(TAG, "RDP routed via SOCKS5 ${LogRedact.of(addr.hostString)}:${addr.port} -> ${LogRedact.host(host, port)}")
                            }
                        }
                    }
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)
                val cursor = MutableStateFlow<CursorOverlay?>(null)
                val pointerPos = MutableStateFlow(0 to 0)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                // Was hardcoded to the RdpSession defaults (1920x1080) with no
                // way to change it, so a server drawing anything else had its
                // updates discarded (#422 — VirtualBox defaults to 2560x1600
                // and never announces a resize).
                val rdpWidth = preferencesRepository.rdpDesktopWidth.first()
                val rdpHeight = preferencesRepository.rdpDesktopHeight.first()

                val session = RdpSession(
                    sessionId = "rdp-$tabId",
                    host = actualHost,
                    port = actualPort,
                    width = rdpWidth,
                    height = rdpHeight,
                    username = username,
                    password = password,
                    domain = domain,
                    useNla = useNla,
                    colorDepth = colorDepth,
                    verboseBuffer = verboseBuffer,
                    socksProxy = rdpSocksProxy,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onCursorUpdate = { bmp, hx, hy ->
                    cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
                }
                session.onCursorPosition = { x, y -> pointerPos.value = x to y }
                session.onError = { e ->
                    Log.e(TAG, "RDP error on tab $tabId", e)
                    error.value = describeRdpError(e, host, port)
                    connected.value = false
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                    // RdpSession.start() reports connect failures via this
                    // callback rather than throwing, so the catch block never
                    // runs for them — release the SSH tunnel lease + any WG
                    // dependent so nothing lingers with a green dot (#121,
                    // same shape as the VNC path). Idempotent with disconnectTab.
                    tunnelLease?.close()
                    releaseSshTunnelDependent(profileId)
                }
                session.onConnected = { _, _ ->
                    // Real handshake complete — only now flip the tab to
                    // "connected". Before this the UI stays on a Connecting
                    // state rather than a misleading empty framebuffer.
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }
                session.onDisconnected = {
                    // Session ended without a surfaced error (server logoff /
                    // transport death). Skip if the tab was already closed by
                    // the user — disconnectTab has cleared the registry and a
                    // late status write would resurrect a ghost entry (#437).
                    if (_tabs.value.any { it.id == tabId }) {
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.DISCONNECTED)
                        if (profileId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(
                                    profileId,
                                    ConnectionLog.Status.DISCONNECTED,
                                    details = "Session ended by server",
                                    verboseLog = session.drainVerboseLog(),
                                )
                            }
                        }
                    }
                }

                // Knock only on the direct path. SSH-forward goes via a
                // localhost tunnel (knock happened at the SSH connect)
                // and the SOCKS path runs through a userspace tunnel
                // that knockd can't observe.
                if (!sshForward && rdpSocksProxy == null && profileId != null) {
                    runVncKnockIfConfigured(profileId, actualHost)
                }

                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                session.start()
                // NB: intentionally no `connected.value = true` here — that
                // happens in session.onConnected once the Rust worker
                // thread completes the handshake.

                val tab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )

                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1



                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RDP connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                // Connect-failure cleanup — same reasoning as the VNC
                // path above (#121): release the lease + WG dependent.
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                // Show error in a temporary tab (no session to close)
                val errorTab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = RdpSession("err", host, port, username, password, domain),
                    _error = MutableStateFlow(describeRdpError(e, host, port)),
                    profileId = profileId,
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(errorTab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- SPICE sessions ---

    fun addSpiceSession(
        host: String,
        port: Int,
        password: String?,
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingIdx = findExistingTab(profileId, host, port, "SPICE")
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            var tunnelLease: SshSessionManager.TunnelLease? = null
            try {
                val actualHost: String
                val actualPort: Int
                // SPICE's Rust client does its own TCP dial — no socket/SOCKS
                // injection like VNC/RDP — so SSH-forward goes via a local
                // -L forward and we hand it 127.0.0.1:<localPort>.
                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "SPICE SSH tunnel: localhost:$lp -> ${LogRedact.host(host, port)}")
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)
                val cursor = MutableStateFlow<CursorOverlay?>(null)
                val pointerPos = MutableStateFlow(0 to 0)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                val session = SpiceSession(
                    sessionId = "spice-$tabId",
                    host = actualHost,
                    port = actualPort,
                    password = password,
                    verboseBuffer = verboseBuffer,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onCursorUpdate = { bmp, hx, hy ->
                    cursor.value = if (bmp == null) null else CursorOverlay(bmp, hx, hy)
                }
                session.onCursorPosition = { x, y -> pointerPos.value = x to y }
                session.onError = { e ->
                    Log.e(TAG, "SPICE error on tab $tabId", e)
                    error.value = e.message ?: "SPICE connection to $host:$port failed"
                    connected.value = false
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                    tunnelLease?.close()
                    releaseSshTunnelDependent(profileId)
                }
                session.onConnected = { _, _ ->
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }
                session.onDisconnected = {
                    // Session ended without a surfaced error — mirror the RDP
                    // path so the tab never claims "connected" past death (#437).
                    if (_tabs.value.any { it.id == tabId }) {
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.DISCONNECTED)
                        if (profileId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(
                                    profileId,
                                    ConnectionLog.Status.DISCONNECTED,
                                    details = "Session ended by server",
                                    verboseLog = session.drainVerboseLog(),
                                )
                            }
                        }
                    }
                }

                // Create + show the tab before start() — SPICE's connect()
                // blocks until established, so the UI sits on "Connecting"
                // until onConnected flips it (or onError sets the error).
                val tab = DesktopTab.Spice(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1

                // no tunnel lease to cascade through (direct connections, #437).
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }

                // Knock only on the direct path (SSH-forward knocked at SSH connect).
                if (!sshForward && profileId != null) {
                    runVncKnockIfConfigured(profileId, actualHost)
                }

                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                session.start() // blocks until established; fires onConnected/onError
            } catch (e: Exception) {
                // SpiceSession.start() invokes onError (which sets the tab's
                // error state) before rethrowing, so just clean up here.
                Log.e(TAG, "SPICE connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
            }
        }
    }

    // --- GLKVM sessions ---

    /**
     * GLKVM (kvmd HTTPS API) viewer: snapshot-poll framebuffer + HID send.
     * Tunnel/topology mirrors [addSpiceSession]; TLS trust is TOFU pinned
     * through [tlsCertVerifier] keyed on the ORIGINAL host:port even when
     * dialling the SSH-forward loopback.
     */
    fun addGlkvmSession(
        host: String,
        port: Int,
        user: String?,
        password: String?,
        targetLayout: String? = null,
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingIdx = findExistingTab(profileId, host, port, "GLKVM")
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()
            // Declared outside try: the catch path builds the error tab from them.
            val connected = MutableStateFlow(false)
            val frame = MutableStateFlow<Bitmap?>(null)
            val error = MutableStateFlow<String?>(null)
            val pointerPos = MutableStateFlow(0 to 0)

            var tunnelLease: SshSessionManager.TunnelLease? = null
            var routeSocketFactory: javax.net.SocketFactory? = null
            try {
                val actualHost: String
                val actualPort: Int
                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "GLKVM SSH tunnel: localhost:$lp -> ${LogRedact.host(host, port)}")
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                }

                // TLS: pinned cert (or first-use pin) for the original host:port.
                // Like the VNC/RDP policy, the newly observed cert is recorded
                // only AFTER the connection actually works — a pin observed
                // during a failing session is never trusted into the store.
                val pinned = if (profileId != null) {
                    runCatching { tlsCertVerifier.pinnedFingerprint(host, port) }.getOrNull()
                } else null
                val pinObserved = java.util.concurrent.atomic.AtomicReference<String?>(null)
                val (sslFactory, tm) = GlkvmTrust.tofu(pinned) { sha -> pinObserved.set(sha) }

                val client = GlkvmClient.Builder().apply {
                    this.host = host
                    this.port = port
                    this.user = user?.ifBlank { "admin" } ?: "admin"
                    this.password = password.orEmpty()
                    sslSocketFactory = sslFactory
                    trustManager = tm
                    baseUrlOverride = "https://$actualHost:$actualPort"
                    plainSocketFactory = routeSocketFactory
                }.build()

                val session = GlkvmDesktopSession(
                    client = client,
                    targetLayout = targetLayout,
                    onFrame = { bitmap ->
                        frame.value = bitmap
                        // Keep the tab's mirrored pointer inside bounds on a
                        // resolution switch (mode change rotates the target).
                        val (px, py) = pointerPos.value
                        if (px > bitmap.width || py > bitmap.height) {
                            pointerPos.value = px.coerceAtMost(bitmap.width) to py.coerceAtMost(bitmap.height)
                        }
                    },
                    onConnected = {
                        connected.value = true
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                        val observed = pinObserved.get()
                        if (profileId != null && observed != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED)
                                runCatching { tlsCertVerifier.accept(host, port, observed) }
                                    .onFailure { t -> Log.w(TAG, "GLKVM cert pin store failed: ${t.message}") }
                            }
                        }
                    },
                    onError = { e ->
                        Log.e(TAG, "GLKVM error on tab $tabId", e)
                        error.value = describeGlkvmError(e, host, port)
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                        if (profileId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                            }
                        }
                        tunnelLease?.close()
                        releaseSshTunnelDependent(profileId)
                    },
                    onDisconnected = {
                        if (_tabs.value.any { it.id == tabId }) {
                            connected.value = false
                            desktopSessionRegistry.setStatus(profileId, DesktopStatus.DISCONNECTED)
                            if (profileId != null) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    connectionLogRepository.logEvent(
                                        profileId,
                                        ConnectionLog.Status.DISCONNECTED,
                                        details = "GLKVM snapshot stream ended",
                                    )
                                }
                            }
                        }
                    },
                )

                // Tab exists before the first poll lands, so the Desktop screen
                // shows "Connecting" until the frame callback flips connected.
                val tab = DesktopTab.Glkvm(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _pointerPos = pointerPos,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )
                _tabs.value = _tabs.value.toMutableList().apply { add(tab) }
                _activeTabIndex.value = _tabs.value.size - 1
                pauseAllExcept(_tabs.value.size - 1)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                desktopSessionRegistry.registerCloseHandle(profileId) {
                    viewModelScope.launch { closeTab(tabId) }
                }

                // Knock only on the direct path (SSH-forward knocked at SSH connect).
                if (!sshForward && profileId != null) {
                    runVncKnockIfConfigured(profileId, actualHost)
                }

                session.start()
            } catch (e: Exception) {
                Log.e(TAG, "GLKVM connect failed", e)
                error.value = describeGlkvmError(e, host, port)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                // Tab with the error visible even when the client build fails.
                if (_tabs.value.none { it.id == tabId }) {
                    _tabs.value = _tabs.value.toMutableList().apply {
                        add(
                            DesktopTab.Glkvm(
                                id = tabId,
                                label = label,
                                colorTag = colorTag,
                                session = GlkvmDesktopSession(
                                    client = GlkvmClient.Builder().apply {
                                        this.host = host; this.port = port
                                        this.user = user?.ifBlank { "admin" } ?: "admin"
                                        this.password = password.orEmpty()
                                        baseUrlOverride = "https://$host:$port"
                                    }.build(),
                                    onFrame = {}, onConnected = {},
                                    onError = { }, onDisconnected = { },
                                ),
                                _connected = MutableStateFlow(false),
                                _frame = frame,
                                _error = error,
                                _pointerPos = MutableStateFlow(0 to 0),
                                tunnelLease = null,
                                profileId = profileId,
                            ),
                        )
                    }
                    _activeTabIndex.value = _tabs.value.size - 1
                }
            }
        }
    }

    private fun describeGlkvmError(e: Throwable, host: String, port: Int): String = when {
        e is sh.haven.core.glkvm.GlkvmCertMismatch -> e.message ?: "TLS certificate changed"
        e.message?.contains("login failed") == true -> "GLKVM login failed (check web UI password). ${e.message}"
        else -> "GLKVM connection to $host:$port failed: ${e.message}"
    }

    fun sendGlkvmKey(scancode: Int, pressed: Boolean) {
        val tab = activeTab.value as? DesktopTab.Glkvm ?: return
        val code = GlkvmScancodes.codeFor(scancode)
        if (code != null) {
            inputLane.submit { tab.session.sendKey(code, pressed) }
        }
    }

    fun typeGlkvmChar(ch: Char, targetLayout: String?) {
        val tab = activeTab.value as? DesktopTab.Glkvm ?: return
        val session = tab.session
        val seq = sh.haven.core.glkvm.GlkvmLayout.keySequenceFor(ch, targetLayout)
        if (seq == null) {
            inputLane.submit { session.typeText(ch.toString()) }
            return
        }
        // Sequence layout: optional ShiftLeft down, the key tap, optional
        // ShiftLeft up — positional access, not a first-match scan.
        val withShift = seq.size == 3
        val tapCode = seq.first { it.first != "ShiftLeft" }.first
        inputLane.submit {
            if (withShift) session.sendKey("ShiftLeft", true)
            session.sendKey(tapCode, true)
            session.sendKey(tapCode, false)
            if (withShift) session.sendKey("ShiftLeft", false)
        }
    }

    /**
     * Type a string sequentially via the active GLKVM tab. Single-character
     * commits take the fast un-delayed [typeGlkvmChar] path; multi-character
     * commits (paste / word completion) run as one paced [GlkvmDesktopSession.typeText]
     * task on [inputLane] so kvmd is not flooded with un-paced HTTPS POSTs.
     */
    fun typeGlkvmText(text: String, targetLayout: String?) {
        if (text.isEmpty()) return
        if (text.length == 1) {
            typeGlkvmChar(text[0], targetLayout)
            return
        }
        val tab = activeTab.value as? DesktopTab.Glkvm ?: return
        val session = tab.session
        inputLane.submit {
            session.typeText(text)
        }
    }

    // --- Input forwarding (operates on active tab) ---

    /** Every remote-desktop input event goes through this, in order. See [RemoteInputLane]. */
    private val inputLane = RemoteInputLane(viewModelScope)

    /** Submit [event] for the active tab's session, resolved now rather than when it runs. */

    private fun activeTabIsViewOnly(): Boolean =
        (activeTab.value as? DesktopTab.Vnc)?._viewOnly?.value == true

    /** #723: flip view-only on the active VNC tab; no-op for other protocols. */
    fun toggleVncViewOnly() {
        val tab = activeTab.value as? DesktopTab.Vnc ?: return
        tab._viewOnly.value = !tab._viewOnly.value
    }

    private fun sendToActive(event: (RemoteDesktopSession) -> Unit) {
        if (activeTabIsViewOnly()) return
        val session = activeTab.value?.remoteDesktop ?: return
        inputLane.submit { event(session) }
    }

    private fun mirrorPointer(x: Int, y: Int) {
        // Mirror the latest pointer into per-tab state so the UI overlay
        // (cursor / virtual cursor seed) repaints immediately, without
        // waiting for the IO dispatch round-trip.
        when (val tab = activeTab.value) {
            is DesktopTab.Vnc -> tab._pointerPos.value = x to y
            is DesktopTab.Rdp -> tab._pointerPos.value = x to y
            is DesktopTab.Spice -> tab._pointerPos.value = x to y
            is DesktopTab.Glkvm -> tab._pointerPos.value = x to y
            else -> {}
        }
    }

    fun sendPointer(x: Int, y: Int) {
        mirrorPointer(x, y)
        sendToActive { it.sendMouseMove(x, y) }
    }

    fun pressButton(button: Int = 1) {
        sendToActive { it.sendMouseButton(button, pressed = true) }
    }

    fun releaseButton(button: Int = 1) {
        sendToActive { it.sendMouseButton(button, pressed = false) }
    }

    fun sendClick(x: Int, y: Int, button: Int = 1) {
        mirrorPointer(x, y)
        val kind = when (button) {
            2 -> "middle_click"
            3 -> "right_click"
            else -> "click"
        }
        sendToActive { it.sendMouseClick(x, y, button) }
    }

    fun scrollUp() {
        val (px, py) = activePointerPos()
        sendToActive { it.sendMouseWheel(deltaY = 1) }
    }

    fun scrollDown() {
        val (px, py) = activePointerPos()
        sendToActive { it.sendMouseWheel(deltaY = -1) }
    }

    private fun activePointerPos(): Pair<Int?, Int?> = when (val tab = activeTab.value) {
        is DesktopTab.Vnc -> tab._pointerPos.value.let { it.first to it.second }
        is DesktopTab.Rdp -> tab._pointerPos.value.let { it.first to it.second }
        is DesktopTab.Spice -> tab._pointerPos.value.let { it.first to it.second }
        is DesktopTab.Glkvm -> tab._pointerPos.value.let { it.first to it.second }
        else -> null to null
    }

    fun sendVncKey(keySym: Int, pressed: Boolean) {
        if (activeTabIsViewOnly()) return
        val client = (activeTab.value as? DesktopTab.Vnc)?.client ?: return
        inputLane.submit { client.updateKey(keySym, pressed) }
    }

    fun typeVncKey(keySym: Int) {
        if (activeTabIsViewOnly()) return
        val client = (activeTab.value as? DesktopTab.Vnc)?.client ?: return
        inputLane.submit { client.type(keySym) }
    }

    /**
     * Type a string sequentially via the active VNC tab — see
     * [VncClient.typeText].
     *
     * Deliberately does NOT push [text] to the remote clipboard (#700).
     * This path carries whatever the IME commits, including partial
     * composition deltas, so copying it stamped intermediate text onto the
     * remote clipboard. The explicit clipboard action
     * ([VncDesktopSession.sendClipboardText]) is the only route that
     * writes the remote clipboard.
     */
    fun typeVncText(text: String) {
        if (activeTabIsViewOnly()) return
        val client = (activeTab.value as? DesktopTab.Vnc)?.client ?: return
        inputLane.submit {
            client.typeText(text)
        }
    }

    /**
     * Push [text] to the active VNC tab's remote clipboard via RFB ClientCutText (#718).
     */
    fun sendVncClipboardText(text: String) {
        if (activeTabIsViewOnly()) return
        val client = (activeTab.value as? DesktopTab.Vnc)?.client ?: return
        inputLane.submit {
            client.copyText(text)
        }
    }

    fun sendRdpKey(scancode: Int, pressed: Boolean) {
        val session = (activeTab.value as? DesktopTab.Rdp)?.session ?: return
        inputLane.submit { session.sendKey(scancode, pressed) }
    }

    fun typeRdpUnicode(codepoint: Int) {
        val session = (activeTab.value as? DesktopTab.Rdp)?.session ?: return
        inputLane.submit {
            session.sendUnicodeKey(codepoint, true)
            session.sendUnicodeKey(codepoint, false)
        }
    }

    fun typeRdpText(text: String) {
        val session = (activeTab.value as? DesktopTab.Rdp)?.session ?: return
        inputLane.submit {
            for (ch in text) {
                sh.haven.feature.rdp.typeRdpChar(
                    ch = ch,
                    sendKey = { sc, pressed -> session.sendKey(sc, pressed) },
                    sendUnicode = { codepoint ->
                        session.sendUnicodeKey(codepoint, true)
                        session.sendUnicodeKey(codepoint, false)
                    },
                )
            }
        }
    }

    fun sendRdpClipboardText(text: String) {
        val tab = activeTab.value as? DesktopTab.Rdp ?: return
        inputLane.submit { tab.remoteDesktop.sendClipboardText(text) }
    }

    fun sendRdpTouchFrame(contacts: List<sh.haven.core.rdp.RdpTouchContactSample>) {
        val session = (activeTab.value as? DesktopTab.Rdp)?.session ?: return
        inputLane.submit { session.sendTouchFrame(contacts) }
    }

    fun cancelRdpTouches() {
        val session = (activeTab.value as? DesktopTab.Rdp)?.session ?: return
        inputLane.submit { session.cancelTouches() }
    }

    fun sendSpiceKey(scancode: Int, pressed: Boolean) {
        val session = (activeTab.value as? DesktopTab.Spice)?.session ?: return
        inputLane.submit { session.sendKey(scancode, pressed) }
    }

    fun typeSpiceText(text: String) {
        val session = (activeTab.value as? DesktopTab.Spice)?.session ?: return
        inputLane.submit {
            for (ch in text) {
                sh.haven.feature.rdp.typeRdpChar(
                    ch = ch,
                    sendKey = { sc, pressed -> session.sendKey(sc, pressed) },
                    sendUnicode = { /* SPICE has no unicode-key verb */ },
                )
            }
        }
    }

    /**
     * Per-Desktop-view orientation override. Stored in the ViewModel
     * so it survives any composable subtree recreation that happens
     * when the conditional tab-bar in DesktopScreen comes/goes during
     * an orientation change — that recreation reset a Composable-
     * local `remember` state to its initial value and the
     * LaunchedEffect immediately wrote that back, so the lock never
     * took effect.
     *
     * Default is UNSPECIFIED (auto / follow system) so the Desktop
     * tab behaves like its neighbours when no desktop session is
     * active and the user hasn't explicitly chosen landscape —
     * the previous LANDSCAPE default rotated the activity as soon
     * as the user landed on the empty Desktop tab, breaking the
     * left/right swipe-to-change-tab gesture for anyone using
     * Haven without a desktop connection. Users who want landscape
     * cycle to it via the orientation toolbar button.
     *
     * Values are the raw `ActivityInfo.SCREEN_ORIENTATION_*`
     * constants since the enum is private to feature modules. Cycle
     * order from the toolbar button: Auto -> Landscape -> Portrait
     * -> Auto.
     */
    private val _desktopOrientation = MutableStateFlow(
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    )
    val desktopOrientation: StateFlow<Int> = _desktopOrientation.asStateFlow()

    fun cycleDesktopOrientation() {
        _desktopOrientation.value = when (_desktopOrientation.value) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    /** Knock against the VNC/RDP host using the profile's saved sequence,
     *  if any. Failures are logged but not thrown; the real socket open
     *  surfaces the actual symptom. */
    private suspend fun runVncKnockIfConfigured(profileId: String, host: String) {
        val profile = connectionRepository.getById(profileId) ?: return
        val seq = KnockSequence.parse(
            profile.portKnockSequence,
            delayMs = profile.portKnockDelayMs,
        ).getOrNull() ?: return
        val result = portKnocker.knock(host, seq)
        Log.d(
            TAG,
            if (result.ok) "[knock] ${seq.format()} -> ok in ${result.totalDurationMs}ms"
            else "[knock] ${seq.format()} -> failed after ${result.totalDurationMs}ms: ${result.error?.message}"
        )
    }

    private fun findSshClient(sessionId: String): SshConnection? {
        sshSessionManager.getSession(sessionId)?.let { return it.client }
        moshSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        etSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        return null
    }

    // --- Lifecycle ---

    private fun disconnectTab(tab: DesktopTab) {
        viewModelScope.launch(Dispatchers.IO) {
            fun clearProfileStateIfNoReplacement(profileId: String?) {
                if (profileId != null && _tabs.value.any { it.id != tab.id && it.profileId == profileId }) {
                    return
                }
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.clear(profileId)
                desktopSessionRegistry.clearCloseHandle(profileId)
            }
            when (tab) {
                is DesktopTab.Vnc -> {
                    tab.client.stop()
                    // SSH side via the lease (removes the forward + releases
                    // the dependent, tearing the SSH down if it was opened
                    // solely for this tunnel). No-op if already closed by a
                    // parent-gone cascade. releaseSshTunnelDependent also
                    // covers the WG/Tailscale dependent (and is idempotent).
                    tab.tunnelLease?.close()
                    clearProfileStateIfNoReplacement(tab.profileId)
                }
                is DesktopTab.Rdp -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tab.tunnelLease?.close()
                    clearProfileStateIfNoReplacement(tab.profileId)
                }
                is DesktopTab.Spice -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tab.tunnelLease?.close()
                    clearProfileStateIfNoReplacement(tab.profileId)
                }
                is DesktopTab.Glkvm -> {
                    tab.session.close()
                    tab.tunnelLease?.close()
                    clearProfileStateIfNoReplacement(tab.profileId)
                }
            }
        }
    }

    private fun releaseSshTunnelDependent(profileId: String?) {
        if (profileId == null) return
        sshSessionManager.releaseTunnelDependent(profileId)
        viewModelScope.launch { tunnelResolver.release(profileId) }
    }

    private fun pauseAllExcept(activeIndex: Int) {
        _tabs.value.forEachIndexed { index, tab ->
            val rd = tab.remoteDesktop ?: return@forEachIndexed
            if (index == activeIndex) rd.resume() else rd.pause()
        }
    }

    private suspend fun resolveLabel(profileId: String?): String? {
        if (profileId == null) return null
        return try {
            connectionRepository.getById(profileId)?.label
        } catch (_: Exception) { null }
    }

    private suspend fun resolveColorTag(profileId: String?): Int {
        if (profileId == null) return 0
        return try {
            connectionRepository.getById(profileId)?.colorTag ?: 0
        } catch (_: Exception) { 0 }
    }

    override fun onCleared() {
        super.onCleared()
        _tabs.value.forEach { disconnectTab(it) }
    }
}

/**
 * The remote target for a VNC-over-SSH local forward, as dialled FROM the
 * SSH server. The configured VNC host is honoured verbatim (#538: a distinct
 * machine behind the jump host) except when it names the jump host itself —
 * then loopback, because the server there typically binds 127.0.0.1 only and
 * sshd dialling its own external address refuses (#104). Blank falls back to
 * loopback too.
 */
internal fun vncForwardTarget(vncHost: String?, jumpHost: String?): String = when {
    vncHost.isNullOrBlank() -> "127.0.0.1"
    !jumpHost.isNullOrBlank() && vncHost.trim() == jumpHost.trim() -> "127.0.0.1"
    else -> vncHost.trim()
}
