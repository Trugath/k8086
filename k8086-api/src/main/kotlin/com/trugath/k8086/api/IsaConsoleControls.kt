package com.trugath.k8086.api

/**
 * Host console chrome for a video card that owns the primary display window
 * (pause / turbo / mute / CAD / floppy), matching the built-in CGA toolbar.
 */
interface IsaConsoleControls {
    fun floppyDriveCount(): Int
    fun floppyPath(drive: Int): String?
    fun changeFloppy(drive: Int, path: String?)
    fun sendCtrlAltDelete()

    fun togglePause()
    fun isPaused(): Boolean

    fun toggleTurbo()
    fun isTurbo(): Boolean

    fun toggleAudioMute()
    /** User mute preference only (not focus/pause/turbo). */
    fun isUserAudioMuted(): Boolean

    /** XT make/break scan code into the keyboard buffer. */
    fun enqueueKeyScanCode(code: Int) {}

    /** Drive speaker auto-mute when the console window loses focus. */
    fun setConsoleFocused(focused: Boolean) {}
}
