package com.trugath.k8086.video

internal expect class CgaHostUi(cga: Cga, showWindow: Boolean, exitOnClose: Boolean) {
    fun onFramebufferReady(width: Int, height: Int, pixels: IntArray)
    fun refreshCompositeChrome()
    fun updateTitle()
    fun dispose()
    fun writeFramePng(path: String, width: Int, height: Int, argb: IntArray)
}
