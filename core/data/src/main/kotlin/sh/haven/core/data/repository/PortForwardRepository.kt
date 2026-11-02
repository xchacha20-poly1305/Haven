package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.haven.core.data.db.PortForwardRuleDao
import sh.haven.core.data.db.entities.PortForwardRule
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PortForwardRepository @Inject constructor(
    private val dao: PortForwardRuleDao,
) {
    fun observeForProfile(profileId: String): Flow<List<PortForwardRule>> =
        dao.observeForProfile(profileId).map { rules -> rules.filterNot { it.isRetiredAgentForward() } }

    fun observeAll(): Flow<List<PortForwardRule>> = dao.observeAll()

    suspend fun getEnabledForProfile(profileId: String): List<PortForwardRule> =
        dao.getEnabledForProfile(profileId).filterNot { it.isRetiredAgentForward() }

    private fun PortForwardRule.isRetiredAgentForward(): Boolean =
        type == PortForwardRule.Type.REMOTE && bindPort == 8730 &&
            targetHost == "127.0.0.1" && targetPort == 8730

    suspend fun save(rule: PortForwardRule) = dao.upsert(rule)

    suspend fun delete(id: String) = dao.deleteById(id)
}
