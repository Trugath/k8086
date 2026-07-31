package com.trugath.k8086.client

import java.awt.event.KeyEvent

/** IBM XT scan-code set 1 make codes for console key events. */
internal object XtScanCodes {
    fun makeCode(e: KeyEvent): Int? = when (e.keyCode) {
        KeyEvent.VK_ESCAPE -> 0x01
        KeyEvent.VK_1 -> 0x02; KeyEvent.VK_2 -> 0x03; KeyEvent.VK_3 -> 0x04; KeyEvent.VK_4 -> 0x05
        KeyEvent.VK_5 -> 0x06; KeyEvent.VK_6 -> 0x07; KeyEvent.VK_7 -> 0x08; KeyEvent.VK_8 -> 0x09
        KeyEvent.VK_9 -> 0x0A; KeyEvent.VK_0 -> 0x0B; KeyEvent.VK_MINUS -> 0x0C; KeyEvent.VK_EQUALS -> 0x0D
        KeyEvent.VK_BACK_SPACE -> 0x0E; KeyEvent.VK_TAB -> 0x0F
        KeyEvent.VK_Q -> 0x10; KeyEvent.VK_W -> 0x11; KeyEvent.VK_E -> 0x12; KeyEvent.VK_R -> 0x13
        KeyEvent.VK_T -> 0x14; KeyEvent.VK_Y -> 0x15; KeyEvent.VK_U -> 0x16; KeyEvent.VK_I -> 0x17
        KeyEvent.VK_O -> 0x18; KeyEvent.VK_P -> 0x19; KeyEvent.VK_OPEN_BRACKET -> 0x1A; KeyEvent.VK_CLOSE_BRACKET -> 0x1B
        KeyEvent.VK_ENTER -> 0x1C; KeyEvent.VK_CONTROL -> 0x1D
        KeyEvent.VK_A -> 0x1E; KeyEvent.VK_S -> 0x1F; KeyEvent.VK_D -> 0x20; KeyEvent.VK_F -> 0x21
        KeyEvent.VK_G -> 0x22; KeyEvent.VK_H -> 0x23; KeyEvent.VK_J -> 0x24; KeyEvent.VK_K -> 0x25
        KeyEvent.VK_L -> 0x26; KeyEvent.VK_SEMICOLON -> 0x27; KeyEvent.VK_QUOTE -> 0x28; KeyEvent.VK_BACK_QUOTE -> 0x29
        KeyEvent.VK_SHIFT -> if (e.keyLocation == KeyEvent.KEY_LOCATION_RIGHT) 0x36 else 0x2A
        KeyEvent.VK_BACK_SLASH -> 0x2B
        KeyEvent.VK_Z -> 0x2C; KeyEvent.VK_X -> 0x2D; KeyEvent.VK_C -> 0x2E; KeyEvent.VK_V -> 0x2F
        KeyEvent.VK_B -> 0x30; KeyEvent.VK_N -> 0x31; KeyEvent.VK_M -> 0x32; KeyEvent.VK_COMMA -> 0x33
        KeyEvent.VK_PERIOD -> 0x34; KeyEvent.VK_SLASH -> 0x35
        KeyEvent.VK_ALT -> 0x38; KeyEvent.VK_SPACE -> 0x39; KeyEvent.VK_CAPS_LOCK -> 0x3A
        KeyEvent.VK_F1 -> 0x3B; KeyEvent.VK_F2 -> 0x3C; KeyEvent.VK_F3 -> 0x3D; KeyEvent.VK_F4 -> 0x3E
        KeyEvent.VK_F5 -> 0x3F; KeyEvent.VK_F6 -> 0x40; KeyEvent.VK_F7 -> 0x41; KeyEvent.VK_F8 -> 0x42
        KeyEvent.VK_F9 -> 0x43; KeyEvent.VK_F10 -> 0x44
        KeyEvent.VK_HOME -> 0x47; KeyEvent.VK_UP -> 0x48; KeyEvent.VK_PAGE_UP -> 0x49
        KeyEvent.VK_LEFT -> 0x4B; KeyEvent.VK_RIGHT -> 0x4D; KeyEvent.VK_END -> 0x4F
        KeyEvent.VK_DOWN -> 0x50; KeyEvent.VK_PAGE_DOWN -> 0x51; KeyEvent.VK_INSERT -> 0x52; KeyEvent.VK_DELETE -> 0x53
        else -> null
    }
}
