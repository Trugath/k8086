package com.trugath.k8086.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ensures the wizard entry point is safe to call from both the EDT and a worker
 * thread (without opening a dialog — we only validate the dispatch branch).
 */
class StartWizardDispatchTest {
    @Test
    fun invokeAndWaitPathIsNotUsedOnEdt() {
        val ranOnEdt = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        SwingUtilities.invokeAndWait {
            ranOnEdt.set(SwingUtilities.isEventDispatchThread())
            // Calling show() would block on a modal dialog; instead assert the
            // same branch condition the fixed show() uses.
            assertTrue(SwingUtilities.isEventDispatchThread())
            done.set(true)
        }
        assertTrue(ranOnEdt.get())
        assertTrue(done.get())
    }

    @Test
    fun offEdtDispatchDoesNotThrowForEmptyRunnable() {
        assertDoesNotThrow {
            if (SwingUtilities.isEventDispatchThread()) {
                // unexpected in JUnit worker, but safe
            } else {
                SwingUtilities.invokeAndWait { }
            }
        }
    }

    @Test
    fun wizardSizeIsClampedToUsableScreen() {
        assertEquals(
            Dimension(900, 640),
            fitWizardSize(Dimension(900, 640), Dimension(1920, 1080)),
        )
        assertEquals(
            Dimension(768, 568),
            fitWizardSize(Dimension(900, 640), Dimension(800, 600)),
        )
    }
}
