package sh.haven.core.data.navigation

/** Navigation requests from workspaces, launcher shortcuts and notification actions. */
sealed class UiCommand {
    data class NavigateToSftpPath(val profileId: String, val path: String) : UiCommand()
    data class FocusTerminalSession(val sessionId: String) : UiCommand()
    data class OpenTerminalSession(val profileId: String, val sessionName: String? = null) : UiCommand()
    data class OpenRemoteDesktop(val profileId: String) : UiCommand()
    data class RegenerateStepCaCert(val keyId: String) : UiCommand()
    data class ConnectProfile(val profileId: String, val sessionName: String? = null) : UiCommand()

    /** External links require confirmation before using saved credentials. */
    data class ConnectFromDeepLink(
        val profileId: String,
        val sessionName: String? = null,
        val startupCommand: String? = null,
    ) : UiCommand()

    data class PrefillNewConnection(
        val host: String,
        val username: String?,
        val port: Int?,
        val transport: String,
        val session: String?,
    ) : UiCommand()

    data object OpenBackupPasswordDialog : UiCommand()
}
