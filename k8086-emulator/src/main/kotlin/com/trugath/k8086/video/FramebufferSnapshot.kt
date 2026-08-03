package com.trugath.k8086.video

/** Immutable host-facing copy of the current video framebuffer. */
data class FramebufferSnapshot(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val graphicsMode: Boolean = false,
    val compositeMode: CgaComposite.Mode = CgaComposite.Mode.AUTO,
    val compositeActive: Boolean = false,
)
