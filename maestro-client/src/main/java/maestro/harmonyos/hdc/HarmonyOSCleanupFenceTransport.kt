package maestro.harmonyos.hdc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

data class CleanupFenceScope(
    val deviceId: String, val ownerId: String, val generation: String, val remoteRoot: String,
    val operationId: String, val operationSha256: String, val target: String,
    val operationIds: List<String>, val temporaryFiles: List<String>,
)

data class CleanupFenceObservation(val hostCallId: String?, val action: String, val nonce: String,
    val phase: String, val stdout: String, val stderr: String)
data class CleanupFenceCall(val hostCallId: String, val action: String, val nonce: String, val argumentsSha256: String)

/** Fixed filesystem operations. Never launches an application or releases a device lease. */
class HarmonyOSCleanupFenceTransport(private val hdc: HdcClient, private val calls: HarmonyOSRecoveryJournal? = null) {
    fun execute(scope: CleanupFenceScope, proof: String, action: String, timeout: Duration,
                beforeDispatch: (CleanupFenceCall) -> Unit = {}): CleanupFenceObservation {
        require(timeout > Duration.ZERO && timeout <= Duration.ofSeconds(30))
        require(calls != null || action in setOf("verify", "verify-root")) { "Mutating fence operations require a durable call journal." }
        calls?.let { require(it.deviceId == scope.deviceId) }
        val nonce = UUID.randomUUID().toString()
        val arguments = arguments(scope, proof, action, nonce)
        var callId: String? = null
        val result = if (calls == null) hdc.shell(scope.deviceId, arguments, timeout)
        else hdc.shellRecovery(scope.deviceId, arguments, timeout, calls) { id ->
            beforeDispatch(CleanupFenceCall(id, action, nonce, hash(mapper.writeValueAsBytes(arguments))))
            callId = id
        }
        val phase = confirmedPhase(result.stdout, result.stderr, nonce)
        require(action != "claim" || phase in setOf("claimed", "occupied"))
        require(action != "clean" || phase == "cleaned")
        return CleanupFenceObservation(callId, action, nonce, phase, result.stdout, result.stderr)
    }

    companion object {
        private val mapper = jacksonObjectMapper()
        private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun q(value: String) = HdcClient.quoteShellArgument(value)

        fun inspect(journal: HarmonyOSRecoveryJournal): CleanupFenceScope = synchronized(journal) {
            journal.maintenanceInventory() // Durable, sealed, finite, stopped, no recording resources.
            scope(mapper.readTree(Files.readAllBytes(journal.path)))
        }

        /** Also used when validating immutable snapshots; opening the real journal validates its full schema. */
        fun scope(node: JsonNode): CleanupFenceScope {
            require(node["version"].asInt() == 1 && node["sealed"].asBoolean() && !node["rootRemoved"].asBoolean())
            val owner = node["ownerId"].asText(); val generation = node["generation"].asText()
            require(uuid.matches(owner) && uuid.matches(generation))
            val device = node["deviceId"].asText()
            require(device.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")))
            val root = node["remoteRoot"].asText()
            require(root == "/data/local/tmp/maestro-session-$generation")
            val ops = node["operations"].toList()
            require(ops.size in 1..1024 && ops.all { uuid.matches(it["id"].asText()) })
            require(ops.map { it["id"].asText() }.distinct().size == ops.size)
            val pending = ops.filter { !it["completed"].asBoolean() }.single()
            require(pending["recoverable"].asBoolean() && !pending["markerRemoved"].asBoolean())
            require(node["hostCalls"].all { it["stopped"].asBoolean() })
            val paths = node["temporaryFiles"].map { it.asText() }
            require(paths.size in 1..512 && paths.distinct().size == paths.size)
            require(paths.all { it.matches(Regex(Regex.escape(root) + "/files/(?:layout-$uuid\\.json|screen-$uuid\\.png)")) })
            val target = paths.filter { it.endsWith(".json") && listOf(listOf("rm", "-f", it), listOf("uitest", "dumpLayout", "-p", it)).any { args -> hash(mapper.writeValueAsBytes(args)) == pending["sha256"].asText() } }.single()
            return CleanupFenceScope(device, owner, generation, root, pending["id"].asText(), pending["sha256"].asText(),
                target, ops.map { it["id"].asText() }, paths)
        }

        fun proof(checkpointSha256: String): String {
            require(checkpointSha256.matches(Regex("[0-9a-f]{64}")))
            return "MAESTRO_CLEANUP_FENCE_V1:$checkpointSha256"
        }

        fun confirmedPhase(stdout: String, stderr: String, nonce: String): String {
            require(uuid.matches(nonce) && stderr.isEmpty()) { "Fence command returned diagnostics." }
            // HDC can append CR; permit only its terminal newline framing, not arbitrary surrounding text.
            val output = stdout.removeSuffix("\n").removeSuffix("\r")
            return when (output) {
                "MAESTRO_FENCE:$nonce:claimed" -> "claimed"
                "MAESTRO_FENCE:$nonce:cleaned" -> "cleaned"
                "MAESTRO_FENCE:$nonce:occupied" -> "occupied"
                else -> throw HdcException("Cleanup fence completion proof is absent or invalid.")
            }
        }

        fun splitInventory(scope: CleanupFenceScope): Boolean = scope.operationIds.size > 16 || scope.temporaryFiles.size > 16

        fun arguments(scope: CleanupFenceScope, proof: String, action: String, nonce: String): List<String> {
            if (splitInventory(scope)) return compactArguments(scope, proof, action, nonce)
            require(action in setOf("claim", "clean", "verify") && uuid.matches(nonce))
            require(proof.matches(Regex("MAESTRO_CLEANUP_FENCE_V1:[0-9a-f]{64}")))
            require(scope.remoteRoot == "/data/local/tmp/maestro-session-${scope.generation}" && uuid.matches(scope.generation))
            require(scope.operationIds.size in 1..16 && scope.operationIds.all { uuid.matches(it) } && scope.operationId in scope.operationIds)
            val root = scope.remoteRoot; val files = "$root/files"; val operation = "$root/${scope.operationId}"
            require(scope.temporaryFiles.size in 1..16 && scope.temporaryFiles.all {
                it.matches(Regex(Regex.escape(root) + "/files/(?:layout-$uuid\\.json|screen-$uuid\\.png)"))
            })
            fun directory(path: String) = "[ -d ${q(path)} ] && [ ! -L ${q(path)} ] || exit 121; "
            fun allowedChildren(parent: String, allowed: List<String>, directoryChildren: Boolean = false): String {
                val cases = allowed.joinToString("|", transform = ::q).ifEmpty { "__no_allowed_children__" }
                return "for __f in ${q(parent)}/* ${q(parent)}/.[!.]* ${q(parent)}/..?*; do " +
                    "[ -e \"\$__f\" ] || [ -L \"\$__f\" ] || continue; " +
                    "[ ! -L \"\$__f\" ] || exit 121; case \"\$__f\" in $cases) ;; *) exit 121;; esac; " +
                    (if (directoryChildren) "[ -d \"\$__f\" ]" else "[ -f \"\$__f\" ]") + " || exit 121; done; "
            }
            val expanded = buildString {
                append("umask 077; ")
                append(directory(root)); append(directory(files))
                append(allowedChildren(root, listOf(files) + scope.operationIds.map { "$root/$it" }, true))
                append(allowedChildren(files, scope.temporaryFiles))
                // Completed proof directories are preserved, never removed by this recovery.
                val completed = scope.operationIds.filter { it != scope.operationId }
                if (completed.isNotEmpty()) {
                    append("for __d in ${completed.joinToString(" ") { q("$root/$it") }}; do ")
                    append("if [ -e \"\$__d\" ]; then [ -d \"\$__d\" ] && [ ! -L \"\$__d\" ] || exit 121; ")
                    append("for __f in \"\$__d\"/* \"\$__d\"/.[!.]* \"\$__d\"/..?*; do ")
                    append("[ -e \"\$__f\" ] || [ -L \"\$__f\" ] || continue; ")
                    append("[ -f \"\$__f\" ] && [ ! -L \"\$__f\" ] || exit 121; ")
                    append("case \"\$__f\" in \"\$__d/done\"|\"\$__d/done.pending\") ;; *) exit 121;; esac; done; fi; done; ")
                }
                if (action == "claim") {
                    append("mkdir ${q(operation)} 2>/dev/null || { printf '%s' ${q("MAESTRO_FENCE:$nonce:occupied")}; exit 0; }; ")
                    append("printf '%s' ${q(proof)} > ${q("$operation/fence.pending")} && ")
                    append("mv ${q("$operation/fence.pending")} ${q("$operation/fence")} || exit 124; ")
                }
                append(directory(operation))
                append(allowedChildren(operation, listOf("$operation/fence", "$operation/cleaned", "$operation/cleaned.pending")))
                append("[ -f ${q("$operation/fence")} ] && [ \"\$(cat ${q("$operation/fence")})\" = ${q(proof)} ] || exit 125; ")
                if (action == "clean") {
                    for (file in scope.temporaryFiles) append("rm -f ${q(file)} && [ ! -e ${q(file)} ] || exit 121; ")
                    append("printf '%s' ${q(proof)} > ${q("$operation/cleaned.pending")} && ")
                    append("mv ${q("$operation/cleaned.pending")} ${q("$operation/cleaned")} || exit 124; ")
                }
                append("if [ -e ${q("$operation/cleaned")} ]; then ")
                append("[ \"\$(cat ${q("$operation/cleaned")})\" = ${q(proof)} ] || exit 125; ")
                for (file in scope.temporaryFiles) append("[ ! -e ${q(file)} ] && [ ! -L ${q(file)} ] || exit 121; ")
                append("printf '%s' ${q("MAESTRO_FENCE:$nonce:cleaned")}; else ")
                append("printf '%s' ${q("MAESTRO_FENCE:$nonce:claimed")}; fi")
            }
            // Keep the bounded wire command small when a session has several completed operations.
            // All suffixes come from the UUID-only paths above; quote each expanded reference.
            val script = "__r=${q(root)}; __p=${q(proof)}; " + expanded
                .replace(Regex("'${Regex.escape(root)}([^']*)'")) { "\"\${__r}${it.groupValues[1]}\"" }
                .replace(q(proof), "\"\$__p\"")
            val args = listOf("sh", "-c", script)
            require(args.joinToString(" ", transform = ::q).toByteArray().size <= 6 * 1024) { "Fence script exceeds its fixed scope budget." }
            return args
        }
        // Large sessions split the immutable inventories between root and file verification.
        // No compression, device helper, wildcard deletion or trust in path absence is used.
        private fun compactArguments(scope: CleanupFenceScope, proof: String, action: String, nonce: String): List<String> {
            require(action in setOf("claim", "clean", "verify-root", "verify") && uuid.matches(nonce))
            require(proof.matches(Regex("MAESTRO_CLEANUP_FENCE_V1:[0-9a-f]{64}")))
            require(uuid.matches(scope.generation) && scope.remoteRoot == "/data/local/tmp/maestro-session-${scope.generation}")
            require(scope.operationIds.size in 1..1024 && scope.operationIds.all { uuid.matches(it) } &&
                scope.operationIds.distinct().size == scope.operationIds.size && scope.operationId in scope.operationIds)
            require(scope.temporaryFiles.size in 1..512 && scope.temporaryFiles.distinct().size == scope.temporaryFiles.size &&
                scope.temporaryFiles.all { it.matches(Regex(Regex.escape(scope.remoteRoot) + "/files/(?:layout-$uuid\\.json|screen-$uuid\\.png)")) })
            val roots = action == "claim" || action == "verify-root"
            val prefix = "__r=${q(scope.remoteRoot)}; __p=${q(proof)}; __o=${q(scope.operationId)}; __a=${q(action)}; __nonce=${q(nonce)}; " +
                "__ops=${q(if (roots) scope.operationIds.joinToString(" ") else "")}; " +
                "__files=${q(if (roots) "" else scope.temporaryFiles.joinToString(" ") { it.substringAfterLast('/') })}; "
            val body = """
                umask 077;
                [ -d "${'$'}__r" ] && [ ! -L "${'$'}__r" ] && [ -d "${'$'}__r/files" ] && [ ! -L "${'$'}__r/files" ] || exit 121;
                if [ "${'$'}__a" = claim ] || [ "${'$'}__a" = verify-root ]; then
                for __d in "${'$'}__r"/* "${'$'}__r"/.[!.]* "${'$'}__r"/..?*; do
                [ -e "${'$'}__d" ] || [ -L "${'$'}__d" ] || continue;
                [ -d "${'$'}__d" ] && [ ! -L "${'$'}__d" ] || exit 121;
                __n=${'$'}{__d##*/}; [ "${'$'}__n" != files ] || continue;
                case "${'$'}__n" in *[!0-9a-f-]*) exit 121;; esac;
                case " ${'$'}__ops " in *" ${'$'}__n "*) ;; *) exit 121;; esac;
                [ "${'$'}__n" != "${'$'}__o" ] || continue;
                for __f in "${'$'}__d"/* "${'$'}__d"/.[!.]* "${'$'}__d"/..?*; do
                [ -e "${'$'}__f" ] || [ -L "${'$'}__f" ] || continue;
                [ -f "${'$'}__f" ] && [ ! -L "${'$'}__f" ] || exit 121;
                case "${'$'}__f" in "${'$'}__d/done"|"${'$'}__d/done.pending") ;; *) exit 121;; esac;
                done; done;
                else
                for __f in "${'$'}__r/files"/* "${'$'}__r/files"/.[!.]* "${'$'}__r/files"/..?*; do
                [ -e "${'$'}__f" ] || [ -L "${'$'}__f" ] || continue;
                [ -f "${'$'}__f" ] && [ ! -L "${'$'}__f" ] || exit 121;
                __n=${'$'}{__f##*/}; case "${'$'}__n" in *[!a-z0-9.-]*) exit 121;; esac;
                case " ${'$'}__files " in *" ${'$'}__n "*) ;; *) exit 121;; esac;
                done; fi;
                __d="${'$'}__r/${'$'}__o";
                if [ "${'$'}__a" = claim ]; then
                mkdir "${'$'}__d" 2>/dev/null || { printf '%s' "MAESTRO_FENCE:${'$'}__nonce:occupied"; exit 0; };
                printf '%s' "${'$'}__p" > "${'$'}__d/fence.pending" && mv "${'$'}__d/fence.pending" "${'$'}__d/fence" || exit 124;
                fi;
                [ -d "${'$'}__d" ] && [ ! -L "${'$'}__d" ] || exit 121;
                for __f in "${'$'}__d"/* "${'$'}__d"/.[!.]* "${'$'}__d"/..?*; do
                [ -e "${'$'}__f" ] || [ -L "${'$'}__f" ] || continue;
                [ -f "${'$'}__f" ] && [ ! -L "${'$'}__f" ] || exit 121;
                case "${'$'}__f" in "${'$'}__d/fence"|"${'$'}__d/cleaned"|"${'$'}__d/cleaned.pending") ;; *) exit 121;; esac;
                done;
                [ -f "${'$'}__d/fence" ] && [ "${'$'}(cat "${'$'}__d/fence")" = "${'$'}__p" ] || exit 125;
                if [ "${'$'}__a" = clean ]; then
                set --; for __n in ${'$'}__files; do set -- "${'$'}@" "${'$'}__r/files/${'$'}__n"; done;
                rm -f "${'$'}@" || exit 121;
                for __n in ${'$'}__files; do [ ! -e "${'$'}__r/files/${'$'}__n" ] && [ ! -L "${'$'}__r/files/${'$'}__n" ] || exit 121; done;
                printf '%s' "${'$'}__p" > "${'$'}__d/cleaned.pending" && mv "${'$'}__d/cleaned.pending" "${'$'}__d/cleaned" || exit 124;
                fi;
                if [ -e "${'$'}__d/cleaned" ]; then
                [ "${'$'}(cat "${'$'}__d/cleaned")" = "${'$'}__p" ] || exit 125;
                for __n in ${'$'}__files; do [ ! -e "${'$'}__r/files/${'$'}__n" ] && [ ! -L "${'$'}__r/files/${'$'}__n" ] || exit 121; done;
                printf '%s' "MAESTRO_FENCE:${'$'}__nonce:cleaned";
                else printf '%s' "MAESTRO_FENCE:${'$'}__nonce:claimed"; fi
            """.trimIndent().lines().joinToString(" ") { it.trim() }
            val args = listOf("sh", "-c", prefix + body)
            require(args.joinToString(" ", transform = ::q).toByteArray().size <= 24 * 1024) { "Fence inventory exceeds the bounded command budget." }
            return args
        }

    }
}
