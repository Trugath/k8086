package com.trugath.k8086.cards.vga

import kotlin.test.Test
import kotlin.test.assertEquals

class CapturedMouseMotionTest {
    @Test
    fun motionHasSameScaleAtDifferentWindowSizes() {
        assertEquals(40 to -20, CapturedMouseMotion().move(40, -20, 640, 400))
        assertEquals(40 to -20, CapturedMouseMotion().move(80, -40, 1280, 800))
    }

    @Test
    fun slowMovementSurvivesScalingAndResetDropsFractionalMotion() {
        val motion = CapturedMouseMotion()
        assertEquals(0 to 0, motion.move(1, -1, 1280, 800))
        assertEquals(1 to -1, motion.move(1, -1, 1280, 800))
        motion.move(1, -1, 1280, 800)
        motion.reset()
        assertEquals(0 to 0, motion.move(1, -1, 1280, 800))
    }
}
