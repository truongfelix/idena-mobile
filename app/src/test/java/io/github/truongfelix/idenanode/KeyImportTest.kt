package io.github.truongfelix.idenanode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyImportTest {
    @Test
    fun aStoppedNodeTakesTheKeyAtItsNextStart() {
        val imported = keyImported(nodeRuns = false, risk = null)
        assertFalse(imported.restart)
        assertEquals("Key imported.", imported.message)
    }

    @Test
    fun aRunningNodeRestartsWithTheKey() {
        val imported = keyImported(nodeRuns = true, risk = null)
        assertTrue(imported.restart)
        assertTrue(imported.message.startsWith("Key imported. The node restarts with it"))
    }

    @Test
    fun beforeTheValidationTheRestartSaysItsRisk() {
        val risk = RestartRisk("The validation starts in 2 hours: the node may still be looking for peers then.", canRestart = true)
        val imported = keyImported(nodeRuns = true, risk = risk)
        assertTrue(imported.restart)
        assertTrue(imported.message.endsWith(" ${risk.message}"))
    }

    @Test
    fun duringTheValidationTheNodeKeepsRunning() {
        val risk = RestartRisk("The validation is running: restart the node after it.", canRestart = false)
        val imported = keyImported(nodeRuns = true, risk = risk)
        assertFalse(imported.restart)
        assertEquals("Key imported. The validation is running: switch sync off and on after it to use the key.", imported.message)
    }
}
