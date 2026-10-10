package com.trugath.k8086.cards.vga

import com.trugath.k8086.api.IsaConsoleControls
import java.awt.Cursor
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.JPanel

/** Relative mouse input with recentering, so desktop edges cannot stop motion. */
internal class VgaMouseCapture(
    private val panel: JPanel,
    private val controls: IsaConsoleControls?,
    private val captureChanged: (Boolean) -> Unit,
) {
    private var robot: Robot? = null
    private var captured = false
    private var warpTarget: Point? = null
    private val motion = CapturedMouseMotion()

    fun install() {
        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                panel.requestFocusInWindow()
                if (!captured) capture()
                if (captured) controls?.enqueueMouseEvent(0, 0, buttons(e))
            }

            override fun mouseReleased(e: MouseEvent) {
                if (captured) controls?.enqueueMouseEvent(0, 0, buttons(e))
            }
        })
        panel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) = mouseMoved(e)
            override fun mouseMoved(e: MouseEvent) {
                if (!captured) return
                val screen = e.locationOnScreen
                // Recentring produces a synthetic motion event. It must never
                // become a large movement in the opposite direction in DOS.
                val target = warpTarget
                if (target != null) {
                    if (screen == target) warpTarget = null
                    return
                }
                val center = centerOnScreen()
                val (dx, dy) = motion.move(screen.x - center.x, center.y - screen.y,
                    panel.width, panel.height)
                if (dx != 0 || dy != 0) controls?.enqueueMouseEvent(dx, dy, buttons(e))
                if (screen != center) recenter(center)
            }
        })
    }

    private fun capture() {
        if (controls == null || !panel.isShowing) return
        try {
            val pointerRobot = robot ?: Robot(panel.graphicsConfiguration.device).also { robot = it }
            val cursor = Toolkit.getDefaultToolkit().createCustomCursor(
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "Captured mouse")
            motion.reset()
            captured = true
            panel.cursor = cursor
            captureChanged(true)
            val center = centerOnScreen()
            // A click is away from the centre in general; establish the anchor
            // before accepting relative movement from subsequent host events.
            val alreadyCentered = java.awt.MouseInfo.getPointerInfo()?.location == center
            warpTarget = if (alreadyCentered) null else center
            pointerRobot.mouseMove(center.x, center.y)
        } catch (_: Exception) {
            release()
        }
    }

    private fun centerOnScreen(): Point = panel.locationOnScreen.let {
        Point(it.x + panel.width / 2, it.y + panel.height / 2)
    }

    private fun recenter(center: Point) {
        warpTarget = center
        robot?.mouseMove(center.x, center.y)
    }

    fun release() {
        if (!captured) return
        captured = false
        warpTarget = null
        motion.reset()
        panel.cursor = Cursor.getDefaultCursor()
        controls?.enqueueMouseEvent(0, 0, 0)
        captureChanged(false)
    }

    private fun buttons(e: MouseEvent): Int =
        (if ((e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) != 0) 1 else 0) or
        (if ((e.modifiersEx and MouseEvent.BUTTON3_DOWN_MASK) != 0) 2 else 0)
}

/** Fractional motion survives display scaling and is discarded on release. */
internal class CapturedMouseMotion {
    private var remainderX = 0.0
    private var remainderY = 0.0

    fun reset() { remainderX = 0.0; remainderY = 0.0 }

    fun move(dx: Int, dy: Int, width: Int, height: Int): Pair<Int, Int> {
        remainderX += dx * 640.0 / width.coerceAtLeast(1)
        remainderY += dy * 400.0 / height.coerceAtLeast(1)
        val x = remainderX.toInt()
        val y = remainderY.toInt()
        remainderX -= x
        remainderY -= y
        return x to y
    }
}
