package com.trugath.k8086.video

internal actual class CgaHostUi actual constructor(
    private val cga: Cga,
    private val showWindow: Boolean,
    private val exitOnClose: Boolean,
) {
    actual fun onFramebufferReady(width: Int, height: Int, pixels: IntArray) {}
    actual fun refreshCompositeChrome() {}
    actual fun updateTitle() {}
    actual fun dispose() {}
    actual fun writeFramePng(path: String, width: Int, height: Int, argb: IntArray) {}
}
