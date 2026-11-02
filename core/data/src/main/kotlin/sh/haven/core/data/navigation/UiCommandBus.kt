package sh.haven.core.data.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Broadcasts navigation requests without replaying actions on screen rotation. */
@Singleton
class UiCommandBus @Inject constructor() {
    private val _commands = MutableSharedFlow<UiCommand>(extraBufferCapacity = 1)
    val commands: SharedFlow<UiCommand> = _commands.asSharedFlow()

    // The Files screen may mount after navigation. Retain its destination until consumed.
    private val pendingSftpCommand = AtomicReference<UiCommand?>(null)

    fun emit(command: UiCommand): Boolean {
        if (command is UiCommand.NavigateToSftpPath) pendingSftpCommand.set(command)
        return _commands.tryEmit(command)
    }

    fun takePendingSftpCommand(): UiCommand? = pendingSftpCommand.getAndSet(null)
    fun peekPendingSftpCommand(): UiCommand? = pendingSftpCommand.get()
    fun clearPendingSftp(command: UiCommand) {
        pendingSftpCommand.compareAndSet(command, null)
    }
}
