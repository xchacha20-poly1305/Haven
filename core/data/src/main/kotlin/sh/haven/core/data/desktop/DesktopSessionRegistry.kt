package sh.haven.core.data.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class DesktopStatus { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

/** Shares desktop connection status and close actions with the connections list. */
@Singleton
class DesktopSessionRegistry @Inject constructor() {
    private val _statuses = MutableStateFlow<Map<String, DesktopStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, DesktopStatus>> = _statuses.asStateFlow()
    private val closeHandles = ConcurrentHashMap<String, () -> Unit>()

    fun setStatus(profileId: String?, status: DesktopStatus) {
        if (profileId == null) return
        _statuses.update { it + (profileId to status) }
    }

    fun clear(profileId: String?) {
        if (profileId == null) return
        _statuses.update { it - profileId }
    }

    fun registerCloseHandle(profileId: String?, handle: () -> Unit) {
        if (profileId == null) return
        closeHandles[profileId] = handle
    }

    fun clearCloseHandle(profileId: String?) {
        if (profileId == null) return
        closeHandles.remove(profileId)
    }

    fun closeHandle(profileId: String): (() -> Unit)? = closeHandles[profileId]
}
