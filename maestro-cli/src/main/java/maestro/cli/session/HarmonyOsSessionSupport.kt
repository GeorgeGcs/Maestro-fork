package maestro.cli.session

import maestro.Maestro
import maestro.cli.CliError
import maestro.cli.harmonyos.AgentError
import maestro.cli.harmonyos.AgentProtocolException
import maestro.drivers.HarmonyOSDriver
import maestro.harmonyos.driver.HarmonyOSDriverConfig
import maestro.harmonyos.driver.HarmonyOSArkWebTarget
import maestro.harmonyos.hdc.HdcClient
import maestro.harmonyos.hdc.HdcException
import maestro.harmonyos.hdc.HdcDeviceSelectionException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

internal object HarmonyOsSessionSupport {
    enum class DeviceLockStatus { AVAILABLE, BUSY, RECOVERY_REQUIRED, UNKNOWN }
    fun validateOptions(
        host: String? = null,
        port: Int? = null,
        driverHostPort: Int? = null,
        teamId: String? = null,
        deviceIndex: Int? = null,
        isStudio: Boolean = false,
        isHeadless: Boolean = false,
        screenSize: String? = null,
    ) {
        if (host != null || port != null || driverHostPort != null) {
            throw CliError("HarmonyOS uses the local HDC connection; --host, --port and --driver-host-port are not supported.")
        }
        if (teamId != null) throw CliError("--apple-team-id is not supported for HarmonyOS.")
        if (deviceIndex != null) throw CliError("Use --device <HDC target> instead of --device-index for HarmonyOS.")
        if (isStudio || isHeadless || screenSize != null) {
            throw CliError("Studio, headless browser mode and screen-size overrides are not supported for HarmonyOS.")
        }
    }

    fun selectDevice(deviceId: String?, hdc: HdcClient = HdcClient()): String {
        if (deviceId?.contains(',') == true) {
            throw CliError("Select one HarmonyOS device with --device <HDC target>.")
        }
        return try {
            hdc.selectDevice(deviceId)
        } catch (error: HdcDeviceSelectionException) {
            val code = if (deviceId == null) "DEVICE_SELECTION_REQUIRED" else "DEVICE_NOT_FOUND"
            throw CliError(error.message ?: "Unable to select a HarmonyOS device.").also {
                it.initCause(AgentProtocolException(AgentError("environment", code,
                    error.message ?: "No unambiguous connected device was selected.")).also { cause -> cause.initCause(error) })
            }
        } catch (error: HdcException) {
            throw CliError(error.message ?: "Unable to select a HarmonyOS device.").also { it.initCause(error) }
        } catch (error: IllegalArgumentException) {
            throw CliError(error.message ?: "Invalid HDC device identifier.").also { it.initCause(error) }
        }
    }

    fun selectTestDeviceIds(
        requestedIds: List<String>,
        shards: Int = 1,
        includesWeb: Boolean = false,
        hdc: HdcClient = HdcClient(),
    ): List<String> {
        if (shards != 1 || requestedIds.size > 1) {
            throw CliError("HarmonyOS currently runs one selected device per invocation; sharding is not supported.")
        }
        if (includesWeb) throw CliError("Web flows cannot run with --platform harmonyos.")
        return listOf(selectDevice(requestedIds.singleOrNull(), hdc))
    }

    fun driverConfig(environment: Map<String, String> = System.getenv()): HarmonyOSDriverConfig =
        HarmonyOSDriverConfig(
            abilityName = environment["MAESTRO_HARMONYOS_ABILITY"]?.trim()?.takeIf { it.isNotEmpty() },
            moduleName = environment["MAESTRO_HARMONYOS_MODULE"]?.trim()?.takeIf { it.isNotEmpty() },
            arkWebPort = environment["MAESTRO_HARMONYOS_ARKWEB_PORT"]?.let {
                it.toIntOrNull()?.takeIf { port -> port in 1024..65535 }
                    ?: throw CliError("MAESTRO_HARMONYOS_ARKWEB_PORT must be 1024..65535.")
            },
            arkWebBundle = environment["MAESTRO_HARMONYOS_ARKWEB_BUNDLE"],
            arkWebTarget = environment["MAESTRO_HARMONYOS_ARKWEB_WEBVIEW_ID"].let { id ->
                val url = environment["MAESTRO_HARMONYOS_ARKWEB_PAGE_URL"]
                if (id == null && url == null) null
                else if (id.isNullOrBlank() || url.isNullOrBlank()) {
                    throw CliError("Set both MAESTRO_HARMONYOS_ARKWEB_WEBVIEW_ID and MAESTRO_HARMONYOS_ARKWEB_PAGE_URL.")
                } else HarmonyOSArkWebTarget(id, url)
            },
            strictFaultDiagnostics = when (environment["MAESTRO_HARMONYOS_STRICT_FAULT_DIAGNOSTICS"]?.trim()) {
                null, "", "false" -> false
                "true" -> true
                else -> throw CliError("MAESTRO_HARMONYOS_STRICT_FAULT_DIAGNOSTICS must be true or false.")
            },
            enableScreenRecording = when (val backend = environment["MAESTRO_HARMONYOS_RECORDING"]?.trim()) {
                null, "", "off", "host-frames" -> false
                "huawei-system" -> true
                else -> throw CliError("Unknown HarmonyOS recording backend '$backend'; use host-frames, huawei-system or off.")
            },
            hostRecordingFfmpeg = if (environment["MAESTRO_HARMONYOS_RECORDING"]?.trim() == "host-frames") {
                val ffmpeg = environment["MAESTRO_HARMONYOS_FFMPEG"]?.takeIf(String::isNotBlank)?.let(Path::of)
                ffmpeg?.takeIf { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
                    ?: throw CliError("Set MAESTRO_HARMONYOS_FFMPEG to an absolute FFmpeg executable for host-frames.")
            } else null,
            recordingRetainMedia = when (val retain = environment["MAESTRO_HARMONYOS_RECORDING_RETAIN_MEDIA"]?.trim()) {
                null, "", "false" -> false
                "true" -> true
                else -> throw CliError("Unknown recording retention value '$retain'; use true or false.")
            },
            recordingMaxDuration = environment["MAESTRO_HARMONYOS_RECORDING_MAX_SECONDS"]?.let { value ->
                val seconds = value.trim().toLongOrNull()
                if (seconds == null || seconds !in 1L..600L) {
                    throw CliError("MAESTRO_HARMONYOS_RECORDING_MAX_SECONDS must be an integer from 1 to 600.")
                }
                Duration.ofSeconds(seconds)
            } ?: Duration.ofMinutes(3),
        ).let { withLayoutRpc(it, environment) }

    fun withLayoutRpc(config: HarmonyOSDriverConfig, environment: Map<String, String> = System.getenv()): HarmonyOSDriverConfig =
        config.copy(
            layoutRpcPort = environment["MAESTRO_HARMONYOS_LAYOUT_RPC_PORT"]?.let {
                it.toIntOrNull()?.takeIf { port -> port in 1024..65535 }
                    ?: throw CliError("MAESTRO_HARMONYOS_LAYOUT_RPC_PORT must be 1024..65535.")
            },
            layoutRpcDevice = environment["MAESTRO_HARMONYOS_LAYOUT_RPC_DEVICE"],
            automaticLayoutRpc = when (environment["MAESTRO_HARMONYOS_LAYOUT_RPC_AUTO"]?.trim()) {
                null, "", "true" -> true
                "false" -> false
                else -> throw CliError("MAESTRO_HARMONYOS_LAYOUT_RPC_AUTO must be true or false.")
            },
        )

    fun <T> withSession(
        deviceId: String,
        hdc: HdcClient = HdcClient(),
        config: HarmonyOSDriverConfig = driverConfig(),
        lockDirectory: Path = defaultLockDirectory(),
        ownerId: String = UUID.randomUUID().toString(),
        ciAttempt: HarmonyOsDevicePool.CiAttempt? = null,
        block: (MaestroSessionManager.MaestroSession) -> T,
    ): T {
        val run = {
            withDeviceLock(deviceId, lockDirectory) {
                runDeviceSession(deviceId, hdc, config, lockDirectory, ownerId, block)
            }
        }
        val stateDirectory = System.getenv(HarmonyOsDevicePool.STATE_DIRECTORY_ENV)?.takeIf(String::isNotBlank)
        return if (stateDirectory == null) run() else
            HarmonyOsDevicePool.withAllocationGate(deviceId, Path.of(stateDirectory), ciAttempt, run)
    }

    private fun <T> runDeviceSession(
        deviceId: String,
        hdc: HdcClient,
        config: HarmonyOSDriverConfig,
        lockDirectory: Path,
        ownerId: String,
        block: (MaestroSessionManager.MaestroSession) -> T,
    ): T {
        val lease = HarmonyOsSessionLease.begin(deviceId, ownerId, lockDirectory,
            requiresRecordingCleanup = config.enableScreenRecording, hdc = hdc)
        var session: MaestroSessionManager.MaestroSession? = null
        val closeGuard = Any()
        var closed = false
        var rememberedCloseFailure: Throwable? = null
        fun closeOnce() {
            synchronized(closeGuard) {
                if (!closed) {
                    closed = true
                    try { session?.close() } catch (error: Throwable) { rememberedCloseFailure = error }
                }
                rememberedCloseFailure?.let { throw it }
            }
        }
        val shutdownHook = Thread({ runCatching { closeOnce() } }, "maestro-harmonyos-close")
        var registered = false
        var failure: Throwable? = null
        try {
            val driver = HarmonyOSDriver(deviceId, hdc = hdc.withRecoveryJournal(lease.journal), config = config)
            val openedSession = MaestroSessionManager.MaestroSession(Maestro(driver))
            session = openedSession
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            registered = true
            driver.open()
            return block(openedSession)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (registered) runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            val closeError = try { closeOnce(); null } catch (error: Throwable) { error }
            val leaseError = try { lease.finish(closeError, hdc); null } catch (error: Throwable) { error }
            val original = failure
            if (original != null) {
                listOfNotNull(closeError, leaseError).filter { it !== original }.forEach(original::addSuppressed)
            } else if (closeError != null) {
                if (leaseError != null && leaseError !== closeError) closeError.addSuppressed(leaseError)
                throw closeError
            } else if (leaseError != null) throw leaseError
        }
    }

    internal fun <T> withDeviceLock(
        deviceId: String,
        directory: Path = defaultLockDirectory(),
        block: () -> T,
    ): T {
        Files.createDirectories(directory)
        require(directory.toRealPath() == directory.toAbsolutePath().normalize()) { "Device lock directory must not contain symbolic links." }
        val name = lockName(deviceId)
        require(!Files.isSymbolicLink(directory.resolve("$name.lock"))) { "Device lock file must not be a symbolic link." }
        // Keep an empty lock file rather than deleting it: unlinking a locked file can let a
        // second process create another inode and acquire a second lock for the same device.
        FileChannel.open(directory.resolve("$name.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw CliError("The selected HarmonyOS device is already in use by another Maestro session.").also {
                it.initCause(AgentProtocolException(AgentError("environment", "DEVICE_BUSY",
                    "The selected device is held by another Maestro session.")))
            }
            lock.use { return block() }
        }
    }

    /** A momentary observation, never a reservation. Does not create or remove lock files. */
    fun probeDeviceLock(deviceId: String, directory: Path = defaultLockDirectory()): DeviceLockStatus {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Device-lock probe interrupted")
        val path = directory.resolve("${lockName(deviceId)}.lock")
        return try {
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(path)) return DeviceLockStatus.UNKNOWN
            if (Files.notExists(path)) return if (HarmonyOsSessionLease.requiresRecovery(deviceId, directory))
                DeviceLockStatus.RECOVERY_REQUIRED else DeviceLockStatus.AVAILABLE
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return DeviceLockStatus.UNKNOWN
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) DeviceLockStatus.BUSY else lock.use {
                    if (HarmonyOsSessionLease.requiresRecovery(deviceId, directory)) DeviceLockStatus.RECOVERY_REQUIRED
                    else DeviceLockStatus.AVAILABLE
                }
            }
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Device-lock probe interrupted")
            DeviceLockStatus.UNKNOWN
        }
    }

    internal fun lockName(deviceId: String): String =
        MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    internal fun defaultLockDirectory(): Path =
        Path.of(System.getProperty("user.home"), ".maestro", "harmonyos-sessions")
}
