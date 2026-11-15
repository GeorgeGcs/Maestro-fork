package maestro.harmonyos.hdc

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.harmonyos.driver.HarmonyOSCommandException
import maestro.harmonyos.driver.HdcHarmonyOSDevice
import maestro.utils.TempFileHandler
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Long-lived recorder resources. The containing recovery journal and device lease own the lock. */
class HarmonyOSRecordingResourceJournal private constructor(val path: Path, private val state: State) {
    data class Archive(val name: String, val size: Long, val sha256: String)
    data class RejectedStart(val errorCode: Int, val failureFingerprint: String,
        val firstObservedAt: String, val secondObservedAt: String)
    data class RejectedStartPermission(val recordingId: String, val failureFingerprint: String, val errorCode: Int)
    data class Resource(
        val id: String,
        val fileName: String,
        val remotePath: String,
        var startRequested: Boolean = false,
        var startAcknowledged: Boolean = false,
        var ownerPid: Int? = null,
        var stopRequested: Boolean = false,
        var captureEnded: Boolean = false,
        var uri: String? = null,
        var archiveName: String? = null,
        var archiveSize: Long? = null,
        var archiveSha256: String? = null,
        var mediaRemoved: Boolean = false,
        var clean: Boolean = false,
        var failureFingerprint: String? = null,
        val archives: MutableList<Archive> = mutableListOf(),
        @field:JsonInclude(JsonInclude.Include.NON_NULL) var rejectedStart: RejectedStart? = null,
    )
    data class State(var schemaVersion: Int = 1, val deviceId: String, val ownerId: String,
        val generation: String, val remoteRoot: String, val resources: MutableList<Resource> = mutableListOf())

    inner class Recording internal constructor(private val resource: Resource) {
        val id get() = resource.id
        val fileName get() = resource.fileName
        val remotePath get() = resource.remotePath
        fun starting() = update { check(!resource.startRequested); resource.startRequested = true }
        fun started(pid: Int) = update { check(resource.startRequested && pid > 0); resource.startAcknowledged = true; resource.ownerPid = pid }
        fun stopping() = update { check(!resource.stopRequested); resource.stopRequested = true }
        fun ended() = update { check(resource.startAcknowledged); resource.captureEnded = true }
        fun media(uri: String) = update {
            require(mediaUri(resource.fileName, uri))
            check(resource.uri == null || resource.uri == uri) { "Recording media identity changed." }
            resource.uri = uri
        }
        fun archive(source: Path, timeout: Duration = Duration.ofSeconds(30)) = archive(source, timeout, System::nanoTime)
        internal fun archive(source: Path, timeout: Duration, nanoTime: () -> Long) =
            archiveResource(resource, source, deadline(timeout, nanoTime))
        /** In-memory metadata, not a fresh validation. Read only after the archive call returned successfully. */
        fun latestArchive(): Archive? = synchronized(this@HarmonyOSRecordingResourceJournal) {
            resource.archives.lastOrNull()?.copy()
        }
        fun removed() = update { check(resource.captureEnded && resource.archiveName != null); resource.mediaRemoved = true }
        fun cleaned() = update { check(!resource.startRequested || resource.mediaRemoved); resource.clean = true }
        fun failed(error: Throwable) {
            val fingerprint = failureFingerprint(error)
            update { resource.failureFingerprint = fingerprint }
            error.addSuppressed(RecordingResourceFailure(resource.id, fingerprint))
        }
    }

    @Synchronized fun register(remotePath: String): Recording {
        require(remotePath.matches(Regex(Regex.escape(state.remoteRoot) + "/files/recording-[0-9a-f-]{36}\\.mp4")))
        val id = UUID.randomUUID().toString()
        val resource = Resource(id, "maestro-recording-${id.replace("-", "")}.mp4", remotePath)
        state.resources += resource
        persist()
        return Recording(resource)
    }

    @Synchronized fun isComplete(): Boolean = state.resources.all { it.clean }
    @Synchronized fun hasResources(): Boolean = state.resources.isNotEmpty()
    @Synchronized fun resolvesFailure(id: String, fingerprint: String): Boolean =
        state.resources.any { it.id == id && it.clean && it.failureFingerprint == fingerprint }
    @Synchronized fun ownsFailure(id: String, fingerprint: String): Boolean =
        state.resources.any { it.id == id && it.failureFingerprint == fingerprint }

    /** Never toggles a recorder. Only acknowledged starts can acquire fresh capture-end evidence. */
    @Synchronized fun recover(hdc: HdcClient, journal: HarmonyOSRecoveryJournal, timeout: Duration,
        rejectedStart: RejectedStartPermission? = null): List<RecoveryIssue> {
        if (journal.recordings !== this || journal.deviceId != state.deviceId || journal.ownerId != state.ownerId) {
            return listOf(RecoveryIssue("RECORDING_OWNER_MISMATCH", "The recording resources do not belong to this recovery owner."))
        }
        val readiness = journal.snapshot().issues.filter { it.code != "OWNED_RESOURCES_REMAIN" }
        if (readiness.isNotEmpty()) return readiness
        val deadline = ArchiveDeadline(timeout, System::nanoTime)
        fun remaining(): Duration {
            val left = deadline.expiresAt - deadline.nanoTime()
            if (left <= 0) throw HdcTimeoutException(timeout)
            return Duration.ofNanos(left)
        }
        val recoveryClient = hdc.forResourceRecovery(journal)
        val device = HdcHarmonyOSDevice(state.deviceId, recoveryClient)
        val issues = mutableListOf<RecoveryIssue>()
        for (resource in state.resources.filterNot { it.clean }) {
            val pending = journal.snapshot().issues.filter { it.code != "OWNED_RESOURCES_REMAIN" }
            if (pending.isNotEmpty()) { issues += pending; break }
            try {
                if (!resource.startRequested) { resource.clean = true; persist(); continue }
                if (!resource.startAcknowledged || resource.ownerPid == null) {
                    if (rejectedStart?.recordingId != resource.id || rejectedStart.errorCode != 10106102 ||
                        resource.failureFingerprint != rejectedStart.failureFingerprint || resource.ownerPid != null ||
                        resource.stopRequested || resource.captureEnded || resource.uri != null || resource.archives.isNotEmpty() ||
                        resource.archiveName != null || resource.archiveSize != null || resource.archiveSha256 != null) {
                        issues += RecoveryIssue("RECORDING_CAPTURE_UNCONFIRMED", "Recording ${resource.id} has no confirmed start ownership; recovery never sends a stop toggle.")
                        continue
                    }
                    check(journal.ownsTemporaryFile(resource.remotePath)) { "The rejected recording scratch is not registered to this journal." }
                    fun absent(): Boolean = recorderPids(device.execute(listOf("ps", "-ef"), remaining())).isEmpty() &&
                        capturePids(device.execute(listOf("hidumper", "-s", "3002", "-a", "-h"), remaining())).isEmpty() &&
                        query(device, resource, remaining()) == null &&
                        !scratchExists(recoveryClient, journal, resource, ::remaining)
                    if (!absent()) {
                        issues += RecoveryIssue("RECORDING_CAPTURE_UNCONFIRMED", "Recording ${resource.id} still has a process, capture, media, or scratch file.")
                        continue
                    }
                    val first = Instant.now()
                    check(remaining() > Duration.ofSeconds(2)) { "Insufficient time to independently recheck a rejected recording start." }
                    Thread.sleep(1000)
                    if (!absent()) {
                        issues += RecoveryIssue("RECORDING_CAPTURE_UNCONFIRMED", "Recording ${resource.id} changed during the independent absence check.")
                        continue
                    }
                    val second = Instant.now()
                    check(journal.snapshot().issues.none { it.code != "OWNED_RESOURCES_REMAIN" })
                    state.schemaVersion = 2
                    resource.rejectedStart = RejectedStart(10106102, rejectedStart.failureFingerprint, first.toString(), second.toString())
                    resource.clean = true
                    persist()
                    continue
                }
                check(journal.ownsTemporaryFile(resource.remotePath)) { "The recording scratch is not registered to this journal." }
                val neverArchived = resource.archiveName == null && resource.archiveSize == null &&
                    resource.archiveSha256 == null && resource.archives.isEmpty()
                if (!neverArchived && !archiveValid(resource, deadline)) {
                    issues += RecoveryIssue("RECORDING_ARCHIVE_UNCONFIRMED", "Recording ${resource.id} has no intact retained original.")
                    continue
                }
                if (!resource.captureEnded || neverArchived) {
                    val uri = confirmEndedMedia(device, resource, ::remaining)
                    resource.captureEnded = true
                    resource.uri = uri
                    persist()
                    if (neverArchived) {
                        preserveInitialArchive(device, recoveryClient, journal, resource, uri, deadline, ::remaining)
                        issues += RecoveryIssue("RECORDING_ARCHIVED_RECHECK_REQUIRED", "Recording ${resource.id} was preserved; a later explicit recovery must independently compare its media before deletion.")
                        continue
                    }
                }
                if (!capturePids(device.execute(listOf("hidumper", "-s", "3002", "-a", "-h"), remaining())).isEmpty()) {
                    issues += RecoveryIssue("RECORDING_CAPTURE_ACTIVE", "A capture is active; retained recording recovery was deferred.")
                    continue
                }
                val scratchPresent = scratchExists(recoveryClient, journal, resource, ::remaining)
                if (scratchPresent) {
                    val preserved = receiveScratch(device, recoveryClient, journal, resource, deadline, ::remaining,
                        allowEmpty = resource.archives.any { it.size == 0L }) { local, size, hash ->
                        val matches = resource.archives.any { it.size == size && it.sha256 == hash }
                        if (!matches) {
                            if (capturePids(device.execute(listOf("hidumper", "-s", "3002", "-a", "-h"), remaining())).isNotEmpty()) throw CaptureActive()
                            archiveResource(resource, local, deadline)
                        }
                        matches
                    }
                    if (!preserved) {
                        issues += RecoveryIssue("RECORDING_SCRATCH_ARCHIVED_RECHECK_REQUIRED", "An unpreserved recording export was archived; a later explicit recovery must independently compare its media before cleanup.")
                        continue
                    }
                }
                val uri = query(device, resource, remaining())
                if (uri != null) {
                    check(uri == resource.uri) { "The retained recording URI changed." }
                    if (scratchPresent) {
                        // This exact export was received completely and matched a verified
                        // archive above. An interrupted later export must pass that check again.
                        check(device.execute(listOf("rm", "-f", resource.remotePath), remaining()).isBlank()) {
                            "Recording export scratch removal returned unexpected output."
                        }
                    }
                    exportMedia(device, resource, uri, ::remaining)
                    val unchanged = receiveScratch(device, recoveryClient, journal, resource, deadline, ::remaining,
                        allowEmpty = resource.archives.any { it.size == 0L }) { local, size, hash ->
                        val matches = size == resource.archiveSize && hash == resource.archiveSha256
                        if (!matches) {
                            if (capturePids(device.execute(listOf("hidumper", "-s", "3002", "-a", "-h"), remaining())).isNotEmpty()) throw CaptureActive()
                            archiveResource(resource, local, deadline)
                        }
                        matches
                    }
                    if (!unchanged) {
                        issues += RecoveryIssue("RECORDING_MEDIA_CHANGED", "The retained media differs from its archive; both versions were kept and the device original was not deleted.")
                        continue
                    }
                    check(query(device, resource, remaining()) == uri) { "Recording media identity changed during comparison." }
                    check(capturePids(device.execute(listOf("hidumper", "-s", "3002", "-a", "-h"), remaining())).isEmpty()) { "A capture started during media comparison." }
                    val deletion = device.execute(listOf("mediatool", "delete", uri), remaining())
                    check(deletion.trim() == "[SUCCESS] delete success.") { "Media deletion was not acknowledged." }
                    check(query(device, resource, remaining()) == null) { "Retained recording media still exists." }
                }
                if (uri == null && capturePids(device.execute(listOf("hidumper", "-s", "3002", "-a", "-h"), remaining())).isNotEmpty()) throw CaptureActive()
                resource.mediaRemoved = true
                persist()
                // Remote scratch remains in the ordinary journal until the final cleanup phase.
                resource.clean = true
                persist()
            } catch (error: CaptureActive) {
                issues += RecoveryIssue("RECORDING_CAPTURE_ACTIVE", error.message!!)
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                issues += RecoveryIssue("RECORDING_RECOVERY_UNCONFIRMED", error.message ?: error.javaClass.simpleName)
            }
        }
        return issues
    }

    private class CaptureActive : IllegalStateException("A capture is active; recording recovery was deferred.")

    private fun confirmEndedMedia(device: HdcHarmonyOSDevice, resource: Resource, remaining: () -> Duration): String {
        fun observe(): String {
            val processes = recorderPids(device.execute(listOf("ps", "-ef"), remaining()))
            check(processes.isEmpty() || processes == setOf(resource.ownerPid)) { "A foreign recorder process is present." }
            if (capturePids(device.execute(listOf("hidumper", "-s", "3002", "-a", "-h"), remaining())).isNotEmpty()) throw CaptureActive()
            val uri = checkNotNull(query(device, resource, remaining())) { "The owned recording media has not been found." }
            check(resource.uri == null || resource.uri == uri) { "The retained recording URI changed." }
            return uri
        }
        val uri = observe()
        check(observe() == uri) { "Recording media identity changed during the capture-end check." }
        return uri
    }

    private fun preserveInitialArchive(
        device: HdcHarmonyOSDevice, client: HdcClient, journal: HarmonyOSRecoveryJournal,
        resource: Resource, uri: String, deadline: ArchiveDeadline, remaining: () -> Duration,
    ) {
        if (!scratchExists(client, journal, resource, remaining)) exportMedia(device, resource, uri, remaining)
        receiveScratch(device, client, journal, resource, deadline, remaining) { local, _, _ ->
            check(confirmEndedMedia(device, resource, remaining) == uri)
            archiveResource(resource, local, deadline)
        }
    }

    private fun scratchTest(client: HdcClient, journal: HarmonyOSRecoveryJournal, remote: String, flag: String, remaining: () -> Duration): Boolean {
        check(journal.ownsTemporaryFile(remote)) { "Recording scratch is not registered to this journal." }
        val result = try {
            client.shell(state.deviceId, listOf("test", flag, remote), remaining())
        } catch (error: HdcCommandException) {
            if (error.confirmedRemoteExitCode != 1 || error.result.exitCode != 1 ||
                error.result.stdout.isNotEmpty() || error.result.stderr.isNotEmpty()) throw error
            return false
        }
        check(result.exitCode == 0 && result.stdout.isEmpty() && result.stderr.isEmpty()) { "Recording scratch check was not confirmed." }
        return true
    }

    private fun scratchExists(client: HdcClient, journal: HarmonyOSRecoveryJournal, resource: Resource, remaining: () -> Duration): Boolean {
        check(!scratchTest(client, journal, resource.remotePath, "-L", remaining)) { "Recording scratch is a symbolic link." }
        if (!scratchTest(client, journal, resource.remotePath, "-e", remaining)) return false
        check(scratchTest(client, journal, resource.remotePath, "-f", remaining)) { "Recording scratch is not a regular file." }
        return true
    }

    private fun exportMedia(device: HdcHarmonyOSDevice, resource: Resource, uri: String, remaining: () -> Duration) {
        val exported = device.execute(listOf("mediatool", "recv", uri, resource.remotePath), remaining())
        check(exported.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() ==
            listOf("Table Name: Photos", resource.remotePath)) { "Recording export was not confirmed." }
    }

    private fun <T> receiveScratch(
        device: HdcHarmonyOSDevice, client: HdcClient, journal: HarmonyOSRecoveryJournal,
        resource: Resource, deadline: ArchiveDeadline, remaining: () -> Duration, allowEmpty: Boolean = false, consume: (Path, Long, String) -> T,
    ): T = TempFileHandler().use { temporary ->
            val local = temporary.createTempDirectory("maestro-recording-recovery-").toPath().resolve(resource.fileName)
            val before = device.execute(listOf("stat", "-c", "%s", resource.remotePath), remaining()).trim().toLong()
            require(before >= (if (allowEmpty) 0 else 1) && before <= MAX_ARCHIVE_BYTES) { "Recording scratch is empty or exceeds the recovery byte limit." }
            client.receiveFile(state.deviceId, resource.remotePath, local, remaining())
            val after = device.execute(listOf("stat", "-c", "%s", resource.remotePath), remaining()).trim().toLong()
            require(before == after && Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS) && Files.size(local) == before) {
                "Recording scratch changed or its transfer was incomplete."
            }
            check(!scratchTest(client, journal, resource.remotePath, "-L", remaining) &&
                scratchTest(client, journal, resource.remotePath, "-f", remaining)) { "Recording scratch type changed during transfer." }
            consume(local, before, digest(local, deadline))
        }

    private fun recorderPids(output: String): Set<Int> {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        check(lines.firstOrNull()?.split(Regex("\\s+")) == listOf("UID", "PID", "PPID", "C", "STIME", "TTY", "TIME", "CMD")) {
            "Recorder process status is unrecognized."
        }
        return lines.drop(1).mapNotNull { line ->
            val columns = line.split(Regex("\\s+"), limit = 8)
            check(columns.size == 8) { "Recorder process status is incomplete." }
            if (columns[7] == "com.huawei.hmos.screenrecorder" || columns[7].startsWith("com.huawei.hmos.screenrecorder:")) {
                columns[1].toIntOrNull()?.takeIf { it > 0 } ?: error("Recorder process PID is invalid.")
            } else null
        }.toSet()
    }

    @Synchronized fun verifyArchives(timeout: Duration = Duration.ofSeconds(30)): Boolean {
        val deadline = deadline(timeout)
        return state.resources.all { !it.startRequested || it.rejectedStart != null || archiveValid(it, deadline) }
    }

    private fun query(device: HdcHarmonyOSDevice, resource: Resource, timeout: Duration): String? {
        val output = try { device.execute(listOf("mediatool", "query", resource.fileName, "-u"), timeout) }
        catch (error: HarmonyOSCommandException) {
            if (error.remoteExitCode == 255 && error.stdout.trim() == "find 0 result" && error.stderr.isEmpty()) return null
            throw error
        }
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines == listOf("find 0 result")) return null
        check(lines.size == 3 && lines[0] == "find 1 result" && lines[1] == "uri" &&
            lines[2].startsWith('"') && lines[2].endsWith('"')) { "Recording media query is ambiguous." }
        return lines[2].substring(1, lines[2].length - 1).also { check(mediaUri(resource.fileName, it)) }
    }

    @Synchronized private fun archiveResource(resource: Resource, source: Path, deadline: ArchiveDeadline) {
        check(resource.captureEnded && resource.uri != null) { "Capture end and media identity must precede archival." }
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
        val archives = path.parent.resolve("recording-originals")
        Files.createDirectories(archives)
        require(archives.toRealPath() == archives)
        val destination = archives.resolve("${resource.id}-${UUID.randomUUID()}.mp4")
        val originalSize = Files.size(source)
        require(originalSize <= MAX_ARCHIVE_BYTES) { "Recording archive exceeds the byte limit." }
        // Retained originals belong to the durable recovery owner, never to a temporary file handler.
        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileOutputStream(destination.toFile()).use { output ->
                val buffer = ByteArray(65536)
                var copied = 0L
                while (true) {
                    checkDeadline(deadline)
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    check(copied <= originalSize && copied <= MAX_ARCHIVE_BYTES) { "Recording changed during archival." }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        check(Files.size(destination) == originalSize && Files.size(source) == originalSize)
        val hash = digest(destination, deadline)
        check(hash == digest(source, deadline)) { "Recording changed while preserving its original." }
        resource.archiveName = destination.fileName.toString()
        resource.archiveSize = originalSize
        resource.archiveSha256 = hash
        resource.archives += Archive(destination.fileName.toString(), originalSize, hash)
        persist()
    }

    private fun archiveValid(resource: Resource, deadline: ArchiveDeadline = deadline(Duration.ofSeconds(30))): Boolean {
        val name = resource.archiveName ?: return false
        val archives = path.parent.resolve("recording-originals")
        return try {
            require(resource.archives.lastOrNull() == Archive(name, resource.archiveSize!!, resource.archiveSha256!!))
            archives.toRealPath() == archives && resource.archives.all { archive ->
                require(archive.name.matches(Regex("${Regex.escape(resource.id)}-[0-9a-f-]{36}\\.mp4")))
                val file = archives.resolve(archive.name)
                Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.toRealPath() == file &&
                    Files.size(file) == archive.size && digest(file, deadline) == archive.sha256
            }
        } catch (_: Exception) { false }
    }

    @Synchronized private fun update(action: () -> Unit) { action(); persist() }
    private fun persist() {
        require(path.parent.toRealPath() == path.parent && !Files.isSymbolicLink(path))
        val staging = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(staging.toFile()).use { output -> output.write(mapper.writeValueAsBytes(state)); output.fd.sync() }
            Files.move(staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staging) }
    }

    private class ArchiveDeadline(val timeout: Duration, val nanoTime: () -> Long) {
        val expiresAt = nanoTime() + timeout.toNanos()
    }

    companion object {
        private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        fun create(path: Path, deviceId: String, ownerId: String, generation: String, remoteRoot: String): HarmonyOSRecordingResourceJournal {
            require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return HarmonyOSRecordingResourceJournal(path, State(deviceId = deviceId, ownerId = ownerId,
                generation = generation, remoteRoot = remoteRoot)).also { it.persist() }
        }
        fun open(path: Path, deviceId: String, ownerId: String, generation: String, remoteRoot: String): HarmonyOSRecordingResourceJournal {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath() == path && Files.size(path) <= 4 * 1024 * 1024)
            val tree = mapper.readTree(path.toFile())
            require(tree.fieldNames().asSequence().toSet() == setOf("schemaVersion", "deviceId", "ownerId", "generation", "remoteRoot", "resources"))
            require(tree["schemaVersion"].isIntegralNumber && tree["schemaVersion"].intValue() in 1..2 && tree["resources"].isArray)
            require(listOf("deviceId", "ownerId", "generation", "remoteRoot").all { tree[it].isTextual })
            tree["resources"].forEach { node ->
                val required = setOf("id", "fileName", "remotePath", "startRequested", "startAcknowledged",
                    "ownerPid", "stopRequested", "captureEnded", "uri", "archiveName", "archiveSize", "archiveSha256", "mediaRemoved", "clean", "failureFingerprint", "archives")
                val fields = node.fieldNames().asSequence().toSet()
                require(fields == required || tree["schemaVersion"].intValue() == 2 && fields == required + "rejectedStart")
                require(listOf("startRequested", "startAcknowledged", "stopRequested", "captureEnded", "mediaRemoved", "clean").all { node[it].isBoolean })
                require(listOf("id", "fileName", "remotePath").all { node[it].isTextual })
                require(listOf("uri", "archiveName", "archiveSha256", "failureFingerprint").all { node[it].isNull || node[it].isTextual })
                require(node["ownerPid"].isNull || node["ownerPid"].isIntegralNumber && node["ownerPid"].canConvertToInt() && node["ownerPid"].intValue() > 0)
                require(node["archiveSize"].isNull || node["archiveSize"].isIntegralNumber && node["archiveSize"].canConvertToLong() && node["archiveSize"].longValue() >= 0)
                require(node["archives"].isArray)
                node["archives"].forEach { archive ->
                    require(archive.fieldNames().asSequence().toSet() == setOf("name", "size", "sha256") &&
                        archive["name"].isTextual && archive["sha256"].isTextual && archive["size"].isIntegralNumber &&
                        archive["size"].canConvertToLong() && archive["size"].longValue() in 0..MAX_ARCHIVE_BYTES)
                }
            }
            val state = mapper.treeToValue(tree, State::class.java)
            require(state.deviceId == deviceId && state.ownerId == ownerId && state.generation == generation && state.remoteRoot == remoteRoot)
            require(state.resources.map { it.id }.distinct().size == state.resources.size)
            state.resources.forEach { r ->
                require(UUID.fromString(r.id).toString() == r.id && r.fileName == "maestro-recording-${r.id.replace("-", "")}.mp4")
                require(r.remotePath.matches(Regex(Regex.escape(remoteRoot) + "/files/recording-[0-9a-f-]{36}\\.mp4")))
                require(!r.startAcknowledged || r.startRequested && r.ownerPid != null)
                require(!r.captureEnded || r.startAcknowledged)
                require(!r.stopRequested || r.startAcknowledged)
                require(r.uri == null || mediaUri(r.fileName, r.uri!!))
                if (r.rejectedStart != null) {
                    val rejection = r.rejectedStart!!
                    require(state.schemaVersion == 2 && r.startRequested && !r.startAcknowledged && r.ownerPid == null &&
                        !r.stopRequested && !r.captureEnded && r.uri == null && r.archiveName == null &&
                        r.archiveSize == null && r.archiveSha256 == null && r.archives.isEmpty() &&
                        !r.mediaRemoved && r.clean && rejection.errorCode == 10106102 &&
                        rejection.failureFingerprint == r.failureFingerprint &&
                        Instant.parse(rejection.secondObservedAt).isAfter(Instant.parse(rejection.firstObservedAt)))
                } else {
                    require(!r.mediaRemoved || r.captureEnded && r.archiveName != null)
                    require(!r.clean || !r.startRequested || r.mediaRemoved)
                }
                require((r.archiveName == null) == (r.archiveSize == null) && (r.archiveName == null) == (r.archiveSha256 == null))
                require(r.archiveSha256 == null || r.archiveSha256!!.matches(Regex("[0-9a-f]{64}")))
                require(r.failureFingerprint == null || r.failureFingerprint!!.matches(Regex("[0-9a-f]{64}")))
                require(r.archives.all { it.name.matches(Regex("${Regex.escape(r.id)}-[0-9a-f-]{36}\\.mp4")) && it.sha256.matches(Regex("[0-9a-f]{64}")) })
                require(r.archives.map { it.name }.distinct().size == r.archives.size)
                require(r.archiveName == null && r.archives.isEmpty() || r.archives.lastOrNull() == Archive(r.archiveName!!, r.archiveSize!!, r.archiveSha256!!))
            }
            return HarmonyOSRecordingResourceJournal(path, state)
        }
        fun capturePids(output: String): Set<Int> {
            val marker = "--ScreenCaptureServer--:"
            check(output.contains("PlayerDistributedService") && output.split(marker).size == 2) { "Capture status is unrecognized." }
            val tail = output.substringAfter(marker)
            val end = Regex("(?m)^-{2,}[A-Za-z]").find(tail) ?: error("Capture status is incomplete.")
            val entries = tail.substring(0, end.range.first)
            val pattern = Regex("-----#[0-9]+: pid = ([0-9]+)-----")
            check(pattern.replace(entries, "").isBlank()) { "Capture status is ambiguous." }
            return pattern.findAll(entries).map { it.groupValues[1].toInt().also { pid -> check(pid > 0) } }.toSet()
        }
        private fun mediaUri(name: String, uri: String) = uri.matches(Regex("file://media/Photo/[0-9]+/[A-Za-z0-9_.-]+/${Regex.escape(name)}"))
        private const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
        private fun deadline(timeout: Duration, nanoTime: () -> Long = System::nanoTime): ArchiveDeadline {
            require(timeout > Duration.ZERO)
            return ArchiveDeadline(timeout, nanoTime)
        }
        private fun checkDeadline(deadline: ArchiveDeadline) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Recording archival interrupted")
            if (deadline.nanoTime() >= deadline.expiresAt) {
                throw HdcTimeoutException(deadline.timeout, "Recording archive or hash exceeded the shared operation deadline.")
            }
        }
        private fun digest(file: Path, deadline: ArchiveDeadline): String {
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(65536)
                var bytes = 0L
                while (true) {
                    checkDeadline(deadline)
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_ARCHIVE_BYTES) { "Recording archive exceeds the byte limit." }
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun failureFingerprint(error: Throwable): String {
            fun describe(e: Throwable, depth: Int): String = if (depth >= 8) "depth-limit" else buildString {
                append(e.javaClass.name).append(':').append(e.message).append('\n')
                e.cause?.let { append(describe(it, depth + 1)) }
                e.suppressed.filterNot { it is RecordingResourceFailure }.forEach { append(describe(it, depth + 1)) }
            }
            return MessageDigest.getInstance("SHA-256").digest(describe(error, 0).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}

class RecordingResourceFailure(val recordingId: String, val fingerprint: String) :
    RuntimeException("Recording resource $recordingId requires recovery; original execution error is retained.")
