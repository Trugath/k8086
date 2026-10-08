from pathlib import Path

src = Path(r"c:/Users/ellio/workspace/personal/k8086/k8086-emulator/src/commonMain/kotlin/com/trugath/k8086/video/Cga.kt")
text = src.read_text(encoding="utf-8")

old_imports = """package com.trugath.k8086.video

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.chipset.XtCharScanCodes
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.REG_CS

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.Cursor
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter
"""
new_imports = """package com.trugath.k8086.video

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.platform.platformLock
"""
if old_imports not in text:
    raise SystemExit("IMPORT BLOCK NOT FOUND")
text = text.replace(old_imports, new_imports, 1)

old_fields = """    private var window: JFrame? = null
    private var panel: CgaPanel? = null
    private var image: BufferedImage? = null
    /** Steady EDT paint so live-resize cannot starve frame updates. */
    private var refreshTimer: Timer? = null
    /** Composite colour-mix toggle; visible only in APA graphics modes. */
    private var compositeButton: JButton? = null
"""
new_fields = """    private val hostUi = CgaHostUi(this, showWindow, exitOnClose)
    private val fbLock = Any()
    private var fbWidth = 0
    private var fbHeight = 0
    private var pixels: IntArray = IntArray(0)
"""
if old_fields not in text:
    raise SystemExit("FIELDS NOT FOUND")
text = text.replace(old_fields, new_fields, 1)

marker = "    // Composes the current frame and writes it as a PNG (used by tests/tools)."
idx = text.find(marker)
comp = text.find("        // Standard 16-color CGA/RGBI palette.")
if idx < 0 or comp < 0:
    raise SystemExit(f"cut markers missing {idx} {comp}")
companion = text.rfind("    companion object {", 0, comp)
head = text[:idx]
tail = text[companion:]
middle = Path(__file__).with_name("cga_middle.kt").read_text(encoding="utf-8")
ridx = head.find("    fun renderFrame()")
if ridx >= 0:
    head = head[:ridx]
head = head.replace("        refreshCompositeButton()", "        hostUi.refreshCompositeChrome()")
head = head.replace("        updateWindowTitle()", "        hostUi.updateTitle()")
src.write_text(head + middle + "\n" + tail, encoding="utf-8")
print("wrote", src, "lines", len((head + middle + tail).splitlines()))
