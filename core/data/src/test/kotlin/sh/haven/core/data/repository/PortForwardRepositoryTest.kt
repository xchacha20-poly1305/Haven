package sh.haven.core.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.data.db.PortForwardRuleDao
import sh.haven.core.data.db.entities.PortForwardRule

class PortForwardRepositoryTest {
    @Test
    fun `old agent exposure is excluded while user forwards remain available`() = runTest {
        val retired = PortForwardRule(
            profileId = "server", type = PortForwardRule.Type.REMOTE,
            bindPort = 8730, targetHost = "127.0.0.1", targetPort = 8730,
        )
        val localForward = retired.copy(id = "local", type = PortForwardRule.Type.LOCAL)
        val otherTarget = retired.copy(id = "user-remote", targetPort = 8080)
        val rules = listOf(retired, localForward, otherTarget)
        val dao = mockk<PortForwardRuleDao> {
            every { observeForProfile("server") } returns flowOf(rules)
            coEvery { getEnabledForProfile("server") } returns rules
        }
        val repository = PortForwardRepository(dao)
        val expected = listOf(localForward, otherTarget)

        assertEquals(expected, repository.observeForProfile("server").first())
        assertEquals(expected, repository.getEnabledForProfile("server"))
    }
}
