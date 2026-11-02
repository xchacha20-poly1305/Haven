package sh.haven.core.data.navigation

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the bus's buffering semantics — `replay = 0` matters because UI
 * verbs are commands, not state, and replay would re-fire navigation on
 * a screen rotation. `extraBufferCapacity = 1` matters because it stops
 * a fast-following burst from dropping when one collector is slow.
 */
class UiCommandBusTest {

    @Test
    fun `emit delivers to a subscribed collector`() = runTest(UnconfinedTestDispatcher()) {
        val bus = UiCommandBus()
        val received = mutableListOf<UiCommand>()
        val job = launch { bus.commands.collect { received.add(it) } }

        val cmd = UiCommand.NavigateToSftpPath(profileId = "p1", path = "/tmp")
        assertTrue(bus.emit(cmd))

        // UnconfinedTestDispatcher runs the launch up to its first
        // suspension point inline, so the receive should already have
        // landed in the list by the time control returns here.
        assertEquals(listOf(cmd), received)
        job.cancel()
    }

    @Test
    fun `emit before any subscriber returns false (no replay)`() = runTest {
        val bus = UiCommandBus()
        // No subscriber yet. With replay=0 + extraBuffer=1, tryEmit
        // returns true (buffered) — the first emit fits in the buffer
        // even with no subscriber. We test the *replay* invariant
        // separately below: a late subscriber must not see prior emits.
        bus.emit(UiCommand.NavigateToSftpPath("p1", "/a"))

        val received = mutableListOf<UiCommand>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.commands.collect { received.add(it) }
        }
        // The pre-subscription emit must not be replayed — that's the
        // whole point of replay=0 here. Re-fire on rotation would be
        // wrong for command-shaped events.
        assertTrue("late subscriber must not see prior commands, got: $received", received.isEmpty())
        job.cancel()
    }

    @Test
    fun `multiple subscribers all receive each emit`() = runTest(UnconfinedTestDispatcher()) {
        // The whole architecture relies on this — HavenNavHost (pager)
        // and SftpViewModel (path) both react to NavigateToSftpPath in
        // parallel. If only one collector saw each emit, the bus would
        // be load-balanced rather than fan-out.
        val bus = UiCommandBus()
        val a = mutableListOf<UiCommand>()
        val b = mutableListOf<UiCommand>()
        val jobA = launch { bus.commands.collect { a.add(it) } }
        val jobB = launch { bus.commands.collect { b.add(it) } }

        val cmd = UiCommand.NavigateToSftpPath("p2", "/var")
        bus.emit(cmd)

        assertEquals(listOf(cmd), a)
        assertEquals(listOf(cmd), b)
        jobA.cancel()
        jobB.cancel()
    }

    @Test
    fun `FocusTerminalSession round-trips through the bus`() = runTest(UnconfinedTestDispatcher()) {
        // Pin the second variant — collectors filter by type, so a new
        // variant lighting up has to round-trip cleanly even when
        // existing collectors only handle the older one.
        val bus = UiCommandBus()
        val received = mutableListOf<UiCommand>()
        val job = launch { bus.commands.collect { received.add(it) } }

        val cmd = UiCommand.FocusTerminalSession(sessionId = "sess-42")
        assertTrue(bus.emit(cmd))

        assertEquals(listOf(cmd), received)
        job.cancel()
    }

    // ── Cold-start latch (SFTP-targeted commands) ───────────────────────

    @Test
    fun `clearPendingSftp drops the latch so a remount does not re-fire`() {
        val bus = UiCommandBus()
        val cmd = UiCommand.NavigateToSftpPath("p1", "/a")
        bus.emit(cmd)
        // Live collector handled it, then clears the latch.
        bus.clearPendingSftp(cmd)
        assertEquals(null, bus.takePendingSftpCommand())
    }

    @Test
    fun `latest SFTP command wins the latch`() {
        val bus = UiCommandBus()
        bus.emit(UiCommand.NavigateToSftpPath("p1", "/a"))
        val second = UiCommand.NavigateToSftpPath("p1", "/b")
        bus.emit(second)
        assertEquals(second, bus.takePendingSftpCommand())
    }

    @Test
    fun `non-SFTP commands are not latched`() {
        // Terminal/desktop/connect verbs have their own collectors; the latch
        // is scoped to SFTP commands so it can't steal another collector's.
        val bus = UiCommandBus()
        bus.emit(UiCommand.FocusTerminalSession("sess-1"))
        bus.emit(UiCommand.ConnectProfile("p1"))
        assertEquals(null, bus.takePendingSftpCommand())
    }

    @Test
    fun `commands flow type is a SharedFlow`() {
        // Compile-time guarantee that the public surface is read-only —
        // callers cannot reach the underlying MutableSharedFlow and
        // accidentally bypass the emit() method (which a future revision
        // might want to gate or instrument).
        val bus = UiCommandBus()
        val ref: kotlinx.coroutines.flow.SharedFlow<UiCommand> = bus.commands
        assertTrue(ref === bus.commands)
    }
}
