package sh.haven.app.tasker

import android.util.Log
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.isSshNetworkError
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.fido.FidoAuthenticator
import javax.inject.Inject
import javax.inject.Singleton
import sh.haven.core.redact.LogRedact


@Singleton
class HeadlessSshExec @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val sshSessionManager: SshSessionManager,
    private val sshKeyRepository: SshKeyRepository,
    private val sshIdentityRepository: SshIdentityRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val hostRediscovery: sh.haven.feature.connections.HostRediscovery,
    // SK key on the profile's own auth must work on the headless exec path too
    // (#732); default null keeps explicit test constructions unchanged.
    private val fidoAuthenticator: FidoAuthenticator? = null,
    // Security-chip keys sign via the Keystore + biometric ceremony; same
    // default-null convention as fidoAuthenticator.
    private val keystoreSshKeySigner: sh.haven.core.ssh.KeystoreSshKeySigner? = null,
    // #777: honour SOCKS/HTTP proxy and tunnel routing on headless exec too;
    // default null keeps existing unit-test constructors unchanged.
    private val tunnelResolver: sh.haven.core.tunnel.TunnelResolver? = null,
) {
    
    internal var clientFactory: () -> SshClient = {
        SshClient().apply {
            fidoAuthenticator = this@HeadlessSshExec.fidoAuthenticator
            hardwareKeySigner = this@HeadlessSshExec.keystoreSshKeySigner
        }
    }

    data class Outcome(val exec: ExecResult, val reusedLiveConnection: Boolean)

    
    suspend fun run(profileId: String, command: String, timeoutMs: Long): Outcome {
        val stored = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("Unknown connection profile: $profileId (see list_connections)")
        if (!stored.isSupported || !stored.isSsh) {
            throw IllegalArgumentException("Profile '${stored.label}' is ${stored.connectionType}, not SSH — run_command needs an SSH profile")
        }

        sshSessionManager.getSshClientForProfile(profileId)?.let { live ->
            try {
                return Outcome(live.execCommand(command, timeoutMs), reusedLiveConnection = true)
            } catch (e: IllegalStateException) {
                // "Not connected": the session status raced the transport
                // (e.g. a mosh session whose bootstrap SSH client is gone).
                // Fall through to a fresh headless connect.
                Log.i(TAG, "Live client for '${LogRedact.of(stored.label)}' not usable (${e.message}) — trying headless connect")
            }
        }

        val profile = sshIdentityRepository.applyTo(stored)
        val auth = HeadlessSshAuth.resolve(profile, sshKeyRepository)
            ?: throw IllegalStateException("Profile '${profile.label}' has no headless-usable credential (needs a stored password or an " +
                    "unencrypted non-FIDO2 key). Connect it in Haven first — run_command reuses a live connection.",
            )
        val config = ConnectionConfig(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMethod = auth,
            sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
            addressFamily = ConnectionConfig.AddressFamily.valueOf(profile.addressFamilyEnum.name),
            bindAddress = profile.bindAddress,
            reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = false),
        )
        val client = clientFactory()
        val proxy = tunnelResolver?.havenProxy(profile)
        try {
            val hostKeyEntry = try {
                client.connect(config, proxy = proxy, trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys())
            } catch (e: Exception) {
                // #376: a network failure on a private address may just mean
                // DHCP moved the device — follow its host key once, so
                // automations (MacroDroid run_command against a hotspot
                // client) survive address rotation without macro changes.
                val newHost = if (e.isSshNetworkError()) {
                    runCatching { hostRediscovery.rediscover(profile) }.getOrNull()
                } else null
                if (newHost == null) {
                    throw IllegalStateException("Connect to '${profile.label}' failed: ${e.message ?: e.cause?.message ?: e.javaClass.simpleName}")
                }
                Log.i(TAG, "'${LogRedact.of(profile.label)}' host rediscovered ${LogRedact.of(profile.host)} → ${LogRedact.of(newHost)} — retrying")
                try {
                    client.connect(config.copy(host = newHost), proxy = proxy, trustedHostCaKeys = hostKeyVerifier.trustedHostCaKeys())
                } catch (e2: Exception) {
                    throw IllegalStateException("Connect to '${profile.label}' failed after host rediscovery ($newHost): ${e2.message ?: e2.javaClass.simpleName}")
                }
            }
            when (if (hostKeyEntry == null) HostKeyResult.Trusted else hostKeyVerifier.verify(hostKeyEntry)) {
                is HostKeyResult.Trusted -> { /* ok */ }
                is HostKeyResult.NewHost -> throw IllegalStateException("Unknown host key for '${profile.label}' — refusing (fail-closed TOFU). " +
                        "Connect the profile interactively once to trust the host, then retry.",
                )
                is HostKeyResult.KeyChanged -> throw IllegalStateException("HOST KEY CHANGED for '${profile.label}' — refusing (possible MITM). " +
                        "Reconnect interactively in Haven to review the new key.",
                )
            }
            return Outcome(client.execCommand(command, timeoutMs), reusedLiveConnection = false)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private companion object {
        const val TAG = "HeadlessSshExec"
    }
}


internal object HeadlessSshAuth {
    suspend fun resolve(
        profile: ConnectionProfile,
        sshKeyRepository: SshKeyRepository,
    ): ConnectionConfig.AuthMethod? {
        val keyId = profile.keyId
        if (keyId != null) {
            val key = sshKeyRepository.getById(keyId) ?: return null
            // Hardware-resident keys join the exclusion set: their signature
            // demands a per-op biometric ceremony with the app foreground,
            // which a headless connect cannot guarantee (same reasoning as
            // sk- keys' touch).
            if (key.hardwareAlias != null ||
                key.keyType.startsWith("sk-") ||
                key.isEncrypted
            ) return null
            val keyBytes = sshKeyRepository.getDecryptedKeyBytes(keyId) ?: return null
            val certBytes = sshKeyRepository.getCertificateBytes(keyId)
            return ConnectionConfig.AuthMethod.PrivateKey(
                keyBytes = SshKeyExporter.toPem(keyBytes, key.keyType),
                certificateBytes = certBytes,
            )
        }
        // No explicit key — try every stored unencrypted, non-FIDO key.
        val usableKeys = sshKeyRepository.getAllDecrypted()
            .filter { !it.isEncrypted && !it.keyType.startsWith("sk-") }
        if (usableKeys.isNotEmpty()) {
            return ConnectionConfig.AuthMethod.PrivateKeys(
                keys = usableKeys.map {
                    ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                        label = it.label,
                        keyBytes = SshKeyExporter.toPem(it.privateKeyBytes, it.keyType),
                        certificateBytes = it.certificateBytes, // CA-only servers (#185)
                    )
                },
            )
        }
        val password = profile.sshPassword
        if (!password.isNullOrEmpty()) {
            return ConnectionConfig.AuthMethod.Password(password)
        }
        return null
    }
}
