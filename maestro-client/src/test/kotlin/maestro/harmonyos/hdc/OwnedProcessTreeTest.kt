package maestro.harmonyos.hdc

import com.google.common.truth.Truth.assertThat
import maestro.harmonyos.hdc.HarmonyOSRecoveryJournal.HostProcess
import org.junit.jupiter.api.Test
import java.time.Instant

class OwnedProcessTreeTest {
    private fun process(pid: Long, second: Long) = HostProcess(pid, Instant.ofEpochSecond(second).toString())

    @Test fun `reused parent PID cannot claim an older unrelated process or its descendants`() {
        val root = process(10, 100)
        val child = process(11, 101)
        val grandchild = process(12, 102)
        val old = process(20, 50)
        val unrelated = process(21, 103)
        val access = Processes().apply {
            add(root, null); add(child, root); add(grandchild, child)
            add(old, root); add(unrelated, old)
        }
        val owned = OwnedProcessTree(root, access)
        owned.refresh()
        assertThat(owned.identities()).containsExactly(root, child, grandchild)
        owned.terminate()
        assertThat(access.terminated).containsExactly(root, child, grandchild)
        assertThat(access.snapshot(old.pid)).isNotNull()
        assertThat(access.snapshot(unrelated.pid)).isNotNull()
    }

    @Test fun `every parent edge requires its creation identity and child chronological order`() {
        val root = process(10, 100)
        val child = process(11, 110)
        val olderGrandchild = process(12, 105)
        val wrongParentIdentity = process(13, 120)
        val access = Processes().apply {
            add(root, null); add(child, root); add(olderGrandchild, child)
            add(wrongParentIdentity, process(root.pid, 99))
        }
        val owned = OwnedProcessTree(root, access)
        owned.refresh()
        assertThat(owned.identities()).containsExactly(root, child)
    }

    @Test fun `PID reuse between observation and termination never kills the new process`() {
        val root = process(10, 100)
        val child = process(11, 101)
        val access = Processes().apply { add(root, null); add(child, root) }
        val owned = OwnedProcessTree(root, access)
        owned.refresh()
        val replacement = process(child.pid, 200)
        access.add(replacement, null)
        owned.terminate()
        assertThat(access.terminated).containsExactly(root)
        assertThat(access.snapshot(child.pid)?.identity).isEqualTo(replacement)
        assertThat(owned.hasUnconfirmedProcesses()).isFalse()
    }

    @Test fun `missing creation identity prevents termination and stays unconfirmed`() {
        val root = process(10, 100)
        val unknown = HostProcess(11, null)
        val access = Processes().apply { add(root, null); add(unknown, root) }
        val owned = OwnedProcessTree(root, access)
        owned.refresh()
        owned.terminate()
        assertThat(access.terminated).containsExactly(root)
        assertThat(owned.hasUnconfirmedProcesses()).isTrue()
        assertThat(access.snapshot(unknown.pid)).isNotNull()
    }

    @Test fun `a missing root identity cannot authorize any child termination`() {
        val root = HostProcess(10, null)
        val child = process(11, 101)
        val access = Processes().apply { add(root, null); add(child, root) }
        val owned = OwnedProcessTree(root, access)
        owned.refresh()
        owned.terminate()
        assertThat(access.terminated).isEmpty()
        assertThat(owned.hasUnconfirmedProcesses()).isTrue()
    }

    @Test fun `an unidentified live child stays unconfirmed until a lookup proves it has exited`() {
        val root = process(10, 100)
        val unknown = HostProcess(11, null)
        val access = Processes().apply { add(root, null); add(unknown, root) }
        val owned = OwnedProcessTree(root, access)
        owned.refresh()
        owned.terminate()
        assertThat(owned.hasUnconfirmedProcesses()).isTrue()
        assertThat(access.terminated).containsExactly(root)

        access.exited(unknown.pid)
        assertThat(owned.hasUnconfirmedProcesses()).isFalse()
        assertThat(access.terminated).containsExactly(root)
    }

    private class Processes : NativeProcessAccess {
        private val values = linkedMapOf<Long, NativeProcessSnapshot>()
        val terminated = mutableListOf<HostProcess>()
        fun add(identity: HostProcess, parent: HostProcess?) { values[identity.pid] = NativeProcessSnapshot(identity, parent) }
        fun exited(pid: Long) { values.remove(pid) }
        override fun snapshot(pid: Long) = values[pid]
        override fun children(pid: Long) = values.values.filter { it.parent?.pid == pid }.map { it.identity.pid }
        override fun destroyIfMatches(identity: HostProcess) {
            if (identity.startedAt != null && values[identity.pid]?.identity == identity) {
                terminated += identity
                values.remove(identity.pid)
            }
        }
    }
}
