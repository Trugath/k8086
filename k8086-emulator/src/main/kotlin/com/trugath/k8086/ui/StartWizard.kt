package com.trugath.k8086.ui

import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.CpuModel
import com.trugath.k8086.config.CardCatalog
import com.trugath.k8086.config.CardSelection
import com.trugath.k8086.config.ConfigValidator
import com.trugath.k8086.config.FloppyControllerConfig
import com.trugath.k8086.config.GraphicsAdapter
import com.trugath.k8086.config.HardDiskControllerConfig
import com.trugath.k8086.config.InitialVideoMode
import com.trugath.k8086.config.MachineSetup
import com.trugath.k8086.config.MotherboardConfig
import com.trugath.k8086.config.ValidationSeverity
import com.trugath.k8086.cpu.XT_HARD_DISK_BYTES
import com.trugath.k8086.protocol.NetworkApi
import com.trugath.k8086.protocol.NetworkDefinition
import com.trugath.k8086.protocol.SystemRomDefaults
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Result of [StartWizard.show]: machine setup plus chosen system ROM sources.
 * Workstation snapshots copy U18/U19 (and fdrom beside them) into the VM directory.
 */
data class WizardResult(
    val setup: MachineSetup,
    val u18RomPath: String,
    val u19RomPath: String,
)

/**
 * VM-style setup wizard: system ROMs, adapters (graphics / FDC / HD controller / COM1),
 * virtual networks, drive media, ISA expansion cards, then Review → finish.
 *
 * @param finishButtonLabel label on the Review step (CLI uses "Start"; the
 *   workstation New-VM flow uses "Create").
 * @param defaultU18 initial U18 path (defaults to [SystemRomDefaults]).
 * @param defaultU19 initial U19 path.
 */
object StartWizard {
    fun show(
        networks: com.trugath.k8086.protocol.NetworkApi? = null,
        finishButtonLabel: String = "Start",
        defaultU18: String = SystemRomDefaults.resolve().first,
        defaultU19: String = SystemRomDefaults.resolve().second,
    ): WizardResult? {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
        }
        var result: WizardResult? = null
        val showDialog = Runnable {
            val dialog = WizardDialog(networks, finishButtonLabel, defaultU18, defaultU19)
            dialog.isVisible = true
            result = dialog.result
            dialog.catalog.close()
        }
        // CLI calls from the main thread; the workstation manager calls from the EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            showDialog.run()
        } else {
            SwingUtilities.invokeAndWait(showDialog)
        }
        return result
    }

    /**
     * Non-modal walk of every wizard page for documentation screenshots.
     * Creates the dialog on the EDT; [onStep] runs on the calling thread after each page.
     * Return false from [onStep] to stop early.
     */
    fun captureSteps(
        networks: com.trugath.k8086.protocol.NetworkApi? = null,
        finishButtonLabel: String = "Create",
        defaultU18: String = SystemRomDefaults.resolve().first,
        defaultU19: String = SystemRomDefaults.resolve().second,
        onStep: (stepName: String, dialog: JDialog) -> Boolean,
    ) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
        }
        lateinit var dialog: WizardDialog
        SwingUtilities.invokeAndWait {
            dialog = WizardDialog(networks, finishButtonLabel, defaultU18, defaultU19)
            dialog.isModal = false
            dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            dialog.isVisible = true
        }
        try {
            for (step in WizardStep.entries) {
                SwingUtilities.invokeAndWait {
                    dialog.showStepForCapture(step.ordinal)
                    dialog.toFront()
                    dialog.repaint()
                }
                if (!onStep(step.name.lowercase(), dialog)) break
            }
        } finally {
            SwingUtilities.invokeAndWait {
                dialog.dispose()
                dialog.catalog.close()
            }
        }
    }
}

private const val STEP_MARKER_ACTIVE = "\u25CF"
private const val STEP_MARKER_DONE = "\u2713"
private const val STEP_MARKER_PENDING = " "
private const val SIDEBAR_TEXT_SLACK = 16

private enum class WizardStep(val title: String, val subtitle: String) {
    WELCOME("Welcome", "Create an IBM 5155 / XT virtual machine"),
    ROMS("ROMs", "U18 / U19 system BIOS images"),
    SYSTEM("System", "Memory, 8087, and motherboard switches"),
    ADAPTERS("Adapters", "Graphics, serial, floppy & hard-disk controllers"),
    DRIVES("Drives", "Floppy and hard-disk media"),
    NETWORK("Network", "Virtual NAT networks (gateway / DHCP)"),
    CARDS("Expansion", "ISA plugin cards"),
    REVIEW("Review", "Confirm configuration"),
}

private class WizardDialog(
    private val networks: com.trugath.k8086.protocol.NetworkApi?,
    private val finishButtonLabel: String = "Start",
    defaultU18: String,
    defaultU19: String,
) : JDialog(null as JFrame?, "k8086 — Create Virtual Machine", true) {
    val catalog = CardCatalog().also { it.refresh() }
    var result: WizardResult? = null

    private var stepIndex = 0
    private val steps = WizardStep.entries

    // --- System ROMs ---
    private val u18Field = JTextField(defaultU18, 28)
    private val u19Field = JTextField(defaultU19, 28)

    // --- System (motherboard) ---
    private val cpuCombo = JComboBox(CpuModel.entries.map { it.label }.toTypedArray()).also {
        it.selectedIndex = CpuModel.I8088.ordinal
    }
    private val memoryCombo = JComboBox(MotherboardConfig.MEMORY_PRESETS_KB.map { "$it KB" }.toTypedArray()).also {
        it.selectedIndex = MotherboardConfig.MEMORY_PRESETS_KB.indexOf(640).coerceAtLeast(0)
    }
    private val coprocessorCheck = JCheckBox("8087 math coprocessor (socket U4)", false)
    private val videoModeCombo = JComboBox(InitialVideoMode.entries.map { it.label }.toTypedArray()).also {
        it.selectedIndex = InitialVideoMode.CGA_80x25.ordinal
    }
    private val postLoopCheck = JCheckBox("Continuous POST loop (factory test)", false)

    // --- Adapters ---
    private val graphicsCga = JRadioButton("CGA (IBM Color/Graphics Adapter)", true)
    private val graphicsNone = JRadioButton("None")
    private val showVideoCheck = JCheckBox("Open CGA display window", true)
    private val com1Check = JCheckBox("COM1 serial (8250 @ 0x3F8 / IRQ4)", true)
    private val fdcCheck = JCheckBox("Floppy disk controller (uPD765 @ 0x3F0 / IRQ6 / DMA2)", true)
    private val hdCheck = JCheckBox("Hard disk controller (XT fixed disk)", false)

    // HD controller resources + media (shown when HD enabled)
    private val hdIoField = JTextField("0x320", 8)
    private val hdIrqSpinner = JSpinner(SpinnerNumberModel(5, 2, 7, 1))
    private val hdDmaSpinner = JSpinner(SpinnerNumberModel(3, 0, 3, 1))
    private val hdImageField = JTextField("disks/hd.img", 24)
    private val bootHdCheck = JCheckBox("Boot from hard disk")
    private val size10 = JRadioButton("10 MB (XT default)", true)
    private val size20 = JRadioButton("20 MB")
    private val size40 = JRadioButton("40 MB")
    private val sizeCustom = JRadioButton("Custom MB:")
    private val customMb = JSpinner(SpinnerNumberModel(10, 1, 2048, 1))
    private val hdConfigPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    // --- Floppies ---
    private val floppyFields = mutableListOf<JTextField>()
    private val floppyListPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val floppySection = JPanel(BorderLayout(4, 4))

    // --- Networks ---
    private val networkListModel = DefaultListModel<String>()
    private val networkList = JList(networkListModel)

    // --- Expansion ---
    private val cardRows = mutableListOf<CardRow>()
    private val cardsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    private val summaryArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    private val stepLabels = mutableListOf<JLabel>()
    private val cardLayout = CardLayout()
    private val pages = JPanel(cardLayout)
    private val stepTitle = JLabel()
    private val stepSubtitle = JLabel()
    private val backButton = JButton("Back")
    private val nextButton = JButton("Next")
    private val cancelButton = JButton("Cancel")

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE

        ButtonGroup().also { it.add(graphicsCga); it.add(graphicsNone) }
        ButtonGroup().also { it.add(size10); it.add(size20); it.add(size40); it.add(sizeCustom) }
        customMb.isEnabled = false
        sizeCustom.addChangeListener { customMb.isEnabled = sizeCustom.isSelected && hdCheck.isSelected }

        graphicsCga.addActionListener {
            showVideoCheck.isEnabled = graphicsCga.isSelected
            if (graphicsCga.isSelected && videoModeCombo.selectedIndex == InitialVideoMode.SPECIAL_OR_NONE.ordinal) {
                videoModeCombo.selectedIndex = InitialVideoMode.CGA_80x25.ordinal
            }
        }
        graphicsNone.addActionListener {
            showVideoCheck.isEnabled = false
            showVideoCheck.isSelected = false
            videoModeCombo.selectedIndex = InitialVideoMode.SPECIAL_OR_NONE.ordinal
        }
        fdcCheck.addActionListener { updateDriveSections() }
        hdCheck.addActionListener { updateDriveSections() }

        preferredFloppy()?.let { addFloppyRow(it) }
        buildHdConfigPanel()
        updateDriveSections()
        refreshNetworkList()

        addPage(WizardStep.WELCOME, buildWelcomePage())
        addPage(WizardStep.ROMS, buildRomsPage())
        addPage(WizardStep.SYSTEM, buildSystemPage())
        addPage(WizardStep.ADAPTERS, buildAdaptersPage())
        addPage(WizardStep.DRIVES, buildDrivesPage())
        addPage(WizardStep.NETWORK, buildNetworkPage())
        addPage(WizardStep.CARDS, buildCardsPage())
        addPage(WizardStep.REVIEW, buildReviewPage())

        val sidebar = buildSidebar()
        val header = JPanel(BorderLayout()).apply {
            border = EmptyBorder(12, 16, 8, 16)
            stepTitle.font = stepTitle.font.deriveFont(Font.BOLD, stepTitle.font.size2D * 1.3f)
            stepSubtitle.foreground = Color.GRAY
            add(stepTitle, BorderLayout.NORTH)
            add(stepSubtitle, BorderLayout.SOUTH)
        }
        val center = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(pages, BorderLayout.CENTER)
        }
        val footer = JPanel(BorderLayout()).apply {
            border = EmptyBorder(8, 12, 10, 12)
            add(cancelButton, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                add(backButton)
                add(nextButton)
            }, BorderLayout.EAST)
        }

        contentPane.layout = BorderLayout()
        add(sidebar, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        backButton.addActionListener { goTo(stepIndex - 1) }
        nextButton.addActionListener { onNextOrStart() }
        cancelButton.addActionListener { onCancel() }

        rebuildCardRows()
        showStep(0)
        pack()
        fitToUsableScreen()
    }

    private fun addPage(step: WizardStep, component: Component) {
        pages.add(
            JScrollPane(
                component,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply {
                border = null
                viewport.background = UIManager.getColor("Panel.background")
                verticalScrollBar.unitIncrement = 16
            },
            step.name,
        )
    }

    private fun fitToUsableScreen() {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val desired = Dimension(maxOf(width, 900), maxOf(height, 640))
        size = fitWizardSize(desired, bounds.size)
        setLocation(
            bounds.x + (bounds.width - width) / 2,
            bounds.y + (bounds.height - height) / 2,
        )
    }

    private fun preferredFloppy(): String? =
        File("disks/fd.img").takeIf { it.isFile }?.path

    private fun updateDriveSections() {
        floppySection.isVisible = fdcCheck.isSelected
        hdConfigPanel.isVisible = hdCheck.isSelected
        listOf(hdIoField, hdIrqSpinner, hdDmaSpinner, hdImageField, bootHdCheck, size10, size20, size40, sizeCustom).forEach {
            it.isEnabled = hdCheck.isSelected
        }
        customMb.isEnabled = hdCheck.isSelected && sizeCustom.isSelected
        if (!hdCheck.isSelected) bootHdCheck.isSelected = false
        floppySection.revalidate()
        hdConfigPanel.revalidate()
    }

    private fun buildHdConfigPanel() {
        hdConfigPanel.border = BorderFactory.createTitledBorder("Hard disk controller configuration")
        hdConfigPanel.add(JLabel("<html><body style='width:480px'>" +
            "Emulation uses a BIOS <b>INT 13h</b> shim today. I/O / IRQ / DMA match classic XT WD1003 " +
            "defaults and are reserved so other cards cannot collide; a port-level controller can use them later." +
            "</body></html>").also { it.alignmentX = LEFT_ALIGNMENT })
        hdConfigPanel.add(Box.createVerticalStrut(8))

        fun labeled(label: String, comp: java.awt.Component): JPanel =
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).also {
                it.alignmentX = LEFT_ALIGNMENT
                it.add(JLabel(label))
                it.add(comp)
            }

        hdConfigPanel.add(labeled("I/O base:", hdIoField))
        hdConfigPanel.add(labeled("IRQ:", hdIrqSpinner))
        hdConfigPanel.add(labeled("DMA:", hdDmaSpinner))
        hdConfigPanel.add(Box.createVerticalStrut(6))
        hdConfigPanel.add(JPanel(BorderLayout(6, 0)).also {
            it.alignmentX = LEFT_ALIGNMENT
            it.add(JLabel("Image:"), BorderLayout.WEST)
            it.add(hdImageField, BorderLayout.CENTER)
            it.add(JButton("Browse…").also { b ->
                b.addActionListener {
                    chooseFile(hdImageField, "Hard disk image", arrayOf("img", "raw"), open = false)
                }
            }, BorderLayout.EAST)
            it.maximumSize = Dimension(Int.MAX_VALUE, it.preferredSize.height)
        })
        hdConfigPanel.add(bootHdCheck.also { it.alignmentX = LEFT_ALIGNMENT })
        hdConfigPanel.add(JPanel().also { sizes ->
            sizes.layout = BoxLayout(sizes, BoxLayout.Y_AXIS)
            sizes.alignmentX = LEFT_ALIGNMENT
            sizes.border = BorderFactory.createTitledBorder("New image size (if missing/empty)")
            sizes.add(size10); sizes.add(size20); sizes.add(size40)
            sizes.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
                it.add(sizeCustom); it.add(customMb)
            })
        })
    }

    private fun addFloppyRow(path: String = "") {
        if (floppyFields.size >= ConfigValidator.MAX_FLOPPY_DRIVES) return
        floppyFields += JTextField(path, 24)
        rebuildFloppyList()
    }

    private fun removeFloppyRow(index: Int) {
        if (index !in floppyFields.indices) return
        floppyFields.removeAt(index)
        rebuildFloppyList()
    }

    private fun rebuildFloppyList() {
        floppyListPanel.removeAll()
        if (floppyFields.isEmpty()) {
            floppyListPanel.add(JLabel("No drives — Add floppy drive for A:/B:/…").also {
                it.alignmentX = LEFT_ALIGNMENT
            })
        }
        floppyFields.forEachIndexed { i, field ->
            val letter = ('A' + i)
            floppyListPanel.add(JPanel(BorderLayout(6, 0)).also { row ->
                row.alignmentX = LEFT_ALIGNMENT
                row.add(JLabel("$letter:"), BorderLayout.WEST)
                row.add(field, BorderLayout.CENTER)
                row.add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).also {
                    it.add(JButton("Browse…").also { b ->
                        b.addActionListener {
                            chooseFile(field, "Floppy $letter:", arrayOf("img", "dsk", "ima"), open = true)
                        }
                    })
                    it.add(JButton("Remove").also { b ->
                        b.addActionListener { removeFloppyRow(i) }
                    })
                }, BorderLayout.EAST)
                row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
            })
            floppyListPanel.add(Box.createVerticalStrut(4))
        }
        floppyListPanel.revalidate()
        floppyListPanel.repaint()
    }

    private fun buildSidebar(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY),
                EmptyBorder(16, 12, 16, 12),
            )
            background = UIManager.getColor("Panel.background")?.darker() ?: Color(245, 245, 245)
            isOpaque = true
        }
        panel.add(JLabel("Steps").also {
            it.font = it.font.deriveFont(Font.BOLD)
            it.border = EmptyBorder(0, 4, 10, 0)
        })
        steps.forEachIndexed { i, step ->
            val label = JLabel(stepLabelText(i, STEP_MARKER_PENDING)).apply {
                border = EmptyBorder(6, 4, 6, 4)
                alignmentX = LEFT_ALIGNMENT
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        if (i <= stepIndex) goTo(i)
                    }
                })
            }
            stepLabels += label
            panel.add(label)
        }
        panel.add(Box.createVerticalGlue())
        val width = sidebarWidth(panel)
        panel.preferredSize = Dimension(width, 0)
        panel.minimumSize = Dimension(width, 0)
        return panel
    }

    /**
     * Width that fits every step in its widest rendering. The active step is bold and
     * carries a marker glyph, so sizing from the plain build-time text clips it.
     */
    private fun sidebarWidth(panel: JPanel): Int {
        val widestStep = stepLabels.mapIndexed { i, label ->
            val metrics = label.getFontMetrics(label.font.deriveFont(Font.BOLD))
            val insets = label.insets.left + label.insets.right
            listOf(STEP_MARKER_ACTIVE, STEP_MARKER_DONE, STEP_MARKER_PENDING).maxOf { marker ->
                metrics.stringWidth(stepLabelText(i, marker)) + insets
            }
        }.maxOrNull() ?: 0
        val panelInsets = panel.insets.left + panel.insets.right
        // Slack absorbs fractional advances under HiDPI scaling, where integer
        // stringWidth can round below the painted width and re-introduce ellipses.
        return widestStep + panelInsets + SIDEBAR_TEXT_SLACK
    }

    private fun stepLabelText(index: Int, marker: String): String =
        "$marker  ${index + 1}.  ${steps[index].title}"

    private fun buildWelcomePage(): JPanel = paddedColumn().also { p ->
        p.add(JLabel("<html><body style='width:460px'>" +
            "<p>Configure an IBM 5155 / XT the way you would set motherboard switches and cards:</p>" +
            "<ul>" +
            "<li><b>ROMs</b> — U18 / U19 system BIOS (snapshotted into the VM)</li>" +
            "<li><b>System</b> — RAM size, 8087, initial video (SW1)</li>" +
            "<li><b>Adapters</b> — CGA, COM1, floppy &amp; hard-disk controllers</li>" +
            "<li><b>Drives</b> — floppy / HD images</li>" +
            "<li><b>Expansion</b> — AdLib, EMS, and other ISA JARs</li>" +
            "</ul>" +
            "<p>Nothing is applied until you click <b>$finishButtonLabel</b> on Review.</p>" +
            "</body></html>"))
        p.add(Box.createVerticalGlue())
    }

    private fun buildRomsPage(): JPanel = paddedColumn().also { p ->
        p.add(sectionTitle("System BIOS (U18 / U19)"))
        p.add(JLabel("<html><body style='width:480px'>" +
            "Defaults are the shipped <b>rmDOS</b> clean-room XT ROMs. Browse to override for this VM. " +
            "Images are copied into the VM as immutable snapshots; <code>fdrom.bin</code> beside U18 " +
            "is included automatically when present (needed for hard-disk INT 13h)." +
            "</body></html>").also { it.alignmentX = LEFT_ALIGNMENT })
        p.add(Box.createVerticalStrut(12))
        p.add(romPathRow("U18 ROM (32 KB):", u18Field))
        p.add(Box.createVerticalStrut(8))
        p.add(romPathRow("U19 ROM (8 KB):", u19Field))
        p.add(Box.createVerticalStrut(12))
        p.add(JButton("Restore shipped defaults").also { b ->
            b.alignmentX = LEFT_ALIGNMENT
            b.addActionListener {
                val (u18, u19) = SystemRomDefaults.resolve()
                u18Field.text = u18
                u19Field.text = u19
            }
        })
        p.add(Box.createVerticalGlue())
    }

    private fun romPathRow(label: String, field: JTextField): JPanel =
        JPanel(BorderLayout(6, 0)).also {
            it.alignmentX = LEFT_ALIGNMENT
            it.add(JLabel(label), BorderLayout.WEST)
            it.add(field, BorderLayout.CENTER)
            it.add(JButton("Browse…").also { b ->
                b.addActionListener {
                    chooseFile(field, "System ROM", arrayOf("bin", "rom"), open = true, startDir = File("roms"))
                }
            }, BorderLayout.EAST)
            it.maximumSize = Dimension(Int.MAX_VALUE, it.preferredSize.height)
        }

    private fun buildSystemPage(): JPanel = paddedColumn().also { p ->
        p.add(sectionTitle("CPU"))
        p.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).also {
            it.alignmentX = LEFT_ALIGNMENT
            it.add(JLabel("Processor:"))
            it.add(cpuCombo)
        })
        p.add(hint("IBM 5155/5160 shipped with an 8088. The 8086 option uses the same instruction engine with the 8086 silicon model."))
        p.add(Box.createVerticalStrut(12))
        p.add(sectionTitle("Conventional memory"))
        p.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).also {
            it.alignmentX = LEFT_ALIGNMENT
            it.add(JLabel("Base memory:"))
            it.add(memoryCombo)
        })
        p.add(hint("POST sizes RAM up to this limit (64–640 KB). 256 KB matches many stock 5155s; 640 KB is typical for DOS."))
        p.add(Box.createVerticalStrut(12))
        p.add(sectionTitle("Coprocessor"))
        p.add(coprocessorCheck)
        p.add(hint("Sets SW1 bit 1 and enables the software 8087 (stack, real/integer load-store, arithmetic, comparison, control, and common transcendental operations)."))
        p.add(Box.createVerticalStrut(12))
        p.add(sectionTitle("Initial video mode (SW1)"))
        p.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).also {
            it.alignmentX = LEFT_ALIGNMENT
            it.add(videoModeCombo)
        })
        p.add(hint("Reported to POST / INT 11h. Pair with a matching graphics adapter on the next step."))
        p.add(Box.createVerticalStrut(12))
        p.add(sectionTitle("Diagnostics"))
        p.add(postLoopCheck)
        p.add(hint("Equivalent to the motherboard “loop on POST” test switch — leave off for normal use."))
        p.add(Box.createVerticalGlue())
    }

    private fun buildAdaptersPage(): JPanel = paddedColumn().also { p ->
        p.add(sectionTitle("Graphics adapter"))
        p.add(graphicsCga)
        p.add(graphicsNone)
        p.add(showVideoCheck)
        p.add(Box.createVerticalStrut(12))
        p.add(sectionTitle("Serial"))
        p.add(com1Check)
        p.add(Box.createVerticalStrut(12))
        p.add(sectionTitle("Storage controllers"))
        p.add(fdcCheck)
        p.add(hint("Disable if this machine has no floppy subsystem."))
        p.add(hdCheck)
        p.add(hint("XT fixed-disk adapter. Configure ports and media on the next step / below."))
        p.add(Box.createVerticalStrut(8))
        p.add(hdConfigPanel)
        p.add(Box.createVerticalGlue())
    }

    private fun buildDrivesPage(): JPanel {
        floppySection.border = BorderFactory.createTitledBorder("Floppy drives (requires floppy controller)")
        floppySection.add(JScrollPane(floppyListPanel).also { it.preferredSize = Dimension(100, 160) }, BorderLayout.CENTER)
        floppySection.add(JPanel(FlowLayout(FlowLayout.LEFT)).also {
            it.add(JButton("Add floppy drive").also { b ->
                b.addActionListener {
                    if (!fdcCheck.isSelected) {
                        JOptionPane.showMessageDialog(this, "Enable the floppy controller on the Adapters step first.")
                        return@addActionListener
                    }
                    if (floppyFields.size >= ConfigValidator.MAX_FLOPPY_DRIVES) {
                        JOptionPane.showMessageDialog(this, "Maximum ${ConfigValidator.MAX_FLOPPY_DRIVES} drives (A:–D:).")
                    } else addFloppyRow()
                }
            })
        }, BorderLayout.SOUTH)

        return JPanel(BorderLayout(8, 8)).also { outer ->
            outer.border = EmptyBorder(8, 16, 8, 16)
            outer.add(JLabel("<html>Attach media for enabled controllers. Controllers without media are valid.</html>"), BorderLayout.NORTH)
            outer.add(JPanel().also { stack ->
                stack.layout = BoxLayout(stack, BoxLayout.Y_AXIS)
                stack.add(floppySection)
                stack.add(Box.createVerticalStrut(8))
                stack.add(JLabel("<html>Hard-disk image and size are configured with the HD controller on the Adapters step.</html>"))
            }, BorderLayout.CENTER)
        }
    }

    private fun buildCardsPage(): JPanel = JPanel(BorderLayout(4, 4)).also { outer ->
        outer.border = EmptyBorder(8, 16, 8, 16)
        outer.add(JLabel("Optional ISA expansion cards (JAR plugins)."), BorderLayout.NORTH)
        outer.add(JScrollPane(cardsPanel), BorderLayout.CENTER)
        outer.add(JPanel(FlowLayout(FlowLayout.LEFT)).also {
            it.add(JButton("Refresh").also { b -> b.addActionListener { catalog.refresh(); rebuildCardRows() } })
            it.add(JButton("Add JAR…").also { b ->
                b.addActionListener {
                    val chooser = JFileChooser(File("cards")).apply {
                        fileFilter = FileNameExtensionFilter("ISA card JAR", "jar")
                    }
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                        catalog.refresh(listOf(chooser.selectedFile))
                        rebuildCardRows()
                    }
                }
            })
        }, BorderLayout.SOUTH)
    }

    private fun buildNetworkPage(): JPanel = JPanel(BorderLayout(8, 8)).also { outer ->
        outer.border = EmptyBorder(8, 16, 8, 16)
        networkList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        outer.add(
            JLabel(
                "<html>Virtual NAT networks: the host is the gateway. " +
                    "NIC cards (e.g. DE-220) attach by network id.</html>",
            ),
            BorderLayout.NORTH,
        )
        outer.add(JScrollPane(networkList), BorderLayout.CENTER)
        outer.add(JPanel(FlowLayout(FlowLayout.LEFT)).also { bar ->
            if (networks == null) {
                bar.add(JLabel("Networking API not available in this session."))
            } else {
                bar.add(JButton("New…").also { it.addActionListener { editNetwork(null) } })
                bar.add(JButton("Edit…").also { it.addActionListener { editSelectedNetwork() } })
                bar.add(JButton("Delete").also { it.addActionListener { deleteSelectedNetwork() } })
                bar.add(JButton("Refresh").also { it.addActionListener { refreshNetworkList() } })
            }
        }, BorderLayout.SOUTH)
    }

    private fun refreshNetworkList() {
        networkListModel.clear()
        val api = networks ?: return
        for (n in api.listNetworks()) {
            val dhcp = if (n.dhcpEnabled) "DHCP ${n.dhcpStartIp}–${n.dhcpEndIp}" else "DHCP off"
            networkListModel.addElement("${n.id}: ${n.name}  gw=${n.gatewayIp}/${n.subnetMask}  $dhcp")
        }
    }

    private fun selectedNetworkId(): String? {
        val line = networkList.selectedValue ?: return null
        return line.substringBefore(':').trim().ifBlank { null }
    }

    private fun editSelectedNetwork() {
        val id = selectedNetworkId() ?: return
        val def = networks?.getNetwork(id) ?: return
        editNetwork(def)
    }

    private fun deleteSelectedNetwork() {
        val api = networks ?: return
        val id = selectedNetworkId() ?: return
        val n = JOptionPane.showConfirmDialog(
            this, "Delete network '$id'?", "Delete network",
            JOptionPane.YES_NO_OPTION,
        )
        if (n != JOptionPane.YES_OPTION) return
        try {
            api.deleteNetwork(id)
            refreshNetworkList()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Delete failed", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun editNetwork(existing: NetworkDefinition?) {
        val api = networks ?: return
        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(3, 3, 3, 3)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        fun row(i: Int, label: String, field: JComponent) {
            c.gridx = 0; c.gridy = i; c.weightx = 0.0
            form.add(JLabel(label), c)
            c.gridx = 1; c.weightx = 1.0
            form.add(field, c)
        }
        val idField = JTextField(existing?.id ?: "", 16).also { it.isEnabled = existing == null }
        val nameField = JTextField(existing?.name ?: "Network", 16)
        val gwField = JTextField(existing?.gatewayIp ?: "10.0.2.2", 16)
        val maskField = JTextField(existing?.subnetMask ?: "255.255.255.0", 16)
        val dhcpCheck = JCheckBox("Enable DHCP", existing?.dhcpEnabled ?: true)
        val dhcpStart = JTextField(existing?.dhcpStartIp ?: "10.0.2.15", 16)
        val dhcpEnd = JTextField(existing?.dhcpEndIp ?: "10.0.2.31", 16)
        row(0, "Id:", idField)
        row(1, "Name:", nameField)
        row(2, "Gateway IP:", gwField)
        row(3, "Subnet mask:", maskField)
        row(4, "", dhcpCheck)
        row(5, "DHCP start:", dhcpStart)
        row(6, "DHCP end:", dhcpEnd)
        val title = if (existing == null) "New virtual network" else "Edit ${existing.id}"
        val ok = JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return
        val def = NetworkDefinition(
            id = idField.text.trim(),
            name = nameField.text.trim().ifBlank { idField.text.trim() },
            gatewayIp = gwField.text.trim(),
            subnetMask = maskField.text.trim(),
            dhcpEnabled = dhcpCheck.isSelected,
            dhcpStartIp = dhcpStart.text.trim(),
            dhcpEndIp = dhcpEnd.text.trim(),
        )
        try {
            if (existing == null) api.createNetwork(def) else api.updateNetwork(def)
            refreshNetworkList()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Save failed", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun buildReviewPage(): JPanel = JPanel(BorderLayout(8, 8)).also {
        it.border = EmptyBorder(8, 16, 8, 16)
        it.add(JLabel("Review ROMs, adapters, media, networks, and cards — then $finishButtonLabel."), BorderLayout.NORTH)
        it.add(JScrollPane(summaryArea), BorderLayout.CENTER)
    }

    private fun paddedColumn(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(8, 16, 8, 16)
        alignmentX = LEFT_ALIGNMENT
    }

    private fun sectionTitle(text: String) = JLabel(text).also {
        it.font = it.font.deriveFont(Font.BOLD)
        it.border = EmptyBorder(0, 0, 6, 0)
        it.alignmentX = LEFT_ALIGNMENT
    }

    private fun hint(text: String) = JLabel("<html><body style='width:420px;color:gray'>$text</body></html>").also {
        it.alignmentX = LEFT_ALIGNMENT
        it.border = EmptyBorder(2, 20, 8, 0)
    }

    private fun rebuildCardRows() {
        val previous = cardRows.associate { it.entry.jarPath to (it.enabled.isSelected to it.configValues()) }
        cardsPanel.removeAll()
        cardRows.clear()
        if (catalog.entries().isEmpty()) {
            cardsPanel.add(JLabel("No card JARs under cards/ — build them or Add JAR…"))
        }
        for (entry in catalog.entries()) {
            val row = CardRow(entry)
            previous[entry.jarPath]?.let { (en, cfg) ->
                row.enabled.isSelected = en
                row.applyConfig(cfg)
            }
            cardRows += row
            cardsPanel.add(row.panel)
            cardsPanel.add(Box.createVerticalStrut(4))
        }
        cardsPanel.revalidate()
        cardsPanel.repaint()
    }

    private fun chooseFile(
        field: JTextField,
        title: String,
        exts: Array<String>,
        open: Boolean,
        startDir: File? = null,
    ) {
        val preferred = startDir?.takeIf { it.isDirectory }
            ?: File("disks").takeIf { it.isDirectory }
            ?: File(".")
        val chooser = JFileChooser(preferred).apply {
            dialogTitle = title
            fileFilter = FileNameExtensionFilter(exts.joinToString("/") { "*.$it" }, *exts)
            selectedFile = File(field.text).takeIf { it.parentFile?.isDirectory == true }
        }
        val result = if (open) chooser.showOpenDialog(this) else chooser.showSaveDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) field.text = chooser.selectedFile.absolutePath
    }

    private fun parseHexField(text: String, fallback: Int): Int {
        val t = text.trim().removePrefix("0x").removePrefix("0X")
        return t.toIntOrNull(16) ?: t.toIntOrNull() ?: fallback
    }

    private fun hardDiskBytes(): Long = when {
        size10.isSelected -> XT_HARD_DISK_BYTES
        size20.isSelected -> 20L * 1024 * 1024
        size40.isSelected -> 40L * 1024 * 1024
        else -> (customMb.value as Int).toLong() * 1024L * 1024L
    }

    private fun currentSetup(): MachineSetup {
        val floppies = floppyFields.map { it.text.trim() }.filter { it.isNotEmpty() }
        val cpuModel = CpuModel.entries.getOrElse(cpuCombo.selectedIndex) { CpuModel.I8088 }
        val memKb = MotherboardConfig.MEMORY_PRESETS_KB.getOrElse(memoryCombo.selectedIndex) { 640 }
        val videoMode = InitialVideoMode.entries.getOrElse(videoModeCombo.selectedIndex) {
            InitialVideoMode.CGA_80x25
        }
        return MachineSetup(
            motherboard = MotherboardConfig(
                cpu = cpuModel,
                baseMemoryKb = memKb,
                mathCoprocessor = coprocessorCheck.isSelected,
                initialVideo = videoMode,
                postLoop = postLoopCheck.isSelected,
            ),
            graphics = if (graphicsCga.isSelected) GraphicsAdapter.CGA else GraphicsAdapter.NONE,
            showVideo = showVideoCheck.isSelected && graphicsCga.isSelected,
            enableCom1 = com1Check.isSelected,
            floppy = FloppyControllerConfig(
                enabled = fdcCheck.isSelected,
                driveImages = if (fdcCheck.isSelected) floppies else emptyList(),
            ),
            hardDisk = HardDiskControllerConfig(
                enabled = hdCheck.isSelected,
                imagePath = if (hdCheck.isSelected) hdImageField.text.trim().ifEmpty { null } else null,
                provisionBytes = hardDiskBytes(),
                bootFromDisk = hdCheck.isSelected && bootHdCheck.isSelected,
                ioBase = parseHexField(hdIoField.text, 0x320),
                irq = hdIrqSpinner.value as Int,
                dmaChannel = hdDmaSpinner.value as Int,
            ),
            cards = cardRows.map {
                CardSelection(it.entry.jarPath, it.entry.factory, it.enabled.isSelected, it.configValues())
            },
        )
    }

    private fun showStep(index: Int) {
        stepIndex = index.coerceIn(0, steps.lastIndex)
        val step = steps[stepIndex]
        stepTitle.text = step.title
        stepSubtitle.text = step.subtitle
        cardLayout.show(pages, step.name)
        updateDriveSections()
        steps.forEachIndexed { i, s ->
            val label = stepLabels[i]
            when {
                i == stepIndex -> {
                    label.text = stepLabelText(i, STEP_MARKER_ACTIVE)
                    label.font = label.font.deriveFont(Font.BOLD)
                }
                i < stepIndex -> {
                    label.text = stepLabelText(i, STEP_MARKER_DONE)
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
                else -> {
                    label.text = stepLabelText(i, STEP_MARKER_PENDING)
                    label.font = label.font.deriveFont(Font.PLAIN)
                }
            }
        }
        backButton.isEnabled = stepIndex > 0
        nextButton.text = if (step == WizardStep.REVIEW) finishButtonLabel else "Next"
        if (step == WizardStep.REVIEW) refreshReview()
    }

    /** Used by [StartWizard.captureSteps] — skips forward validation. */
    fun showStepForCapture(index: Int) {
        showStep(index.coerceIn(0, steps.lastIndex))
    }

    private fun goTo(index: Int) {
        if (index < 0 || index > steps.lastIndex) return
        if (index > stepIndex && !validateBeforeLeaving(stepIndex)) return
        showStep(index)
    }

    private fun onNextOrStart() {
        if (steps[stepIndex] == WizardStep.REVIEW) {
            commitStart(); return
        }
        if (!validateBeforeLeaving(stepIndex)) return
        showStep(stepIndex + 1)
    }

    private fun validateBeforeLeaving(index: Int): Boolean {
        when (steps[index]) {
            WizardStep.ROMS -> {
                val u18 = u18Field.text.trim()
                val u19 = u19Field.text.trim()
                if (u18.isEmpty() || u19.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Both U18 and U19 ROM paths are required.",
                        "System ROMs",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return false
                }
                if (!File(u18).isFile || !File(u19).isFile) {
                    JOptionPane.showMessageDialog(
                        this,
                        "ROM file(s) not found:\n  $u18\n  $u19",
                        "System ROMs",
                        JOptionPane.ERROR_MESSAGE,
                    )
                    return false
                }
            }
            WizardStep.ADAPTERS -> {
                if (hdCheck.isSelected && hdImageField.text.trim().isEmpty()) {
                    JOptionPane.showMessageDialog(this, "HD controller needs an image path (or disable the controller).")
                    return false
                }
            }
            else -> { }
        }
        return true
    }

    private fun refreshReview() {
        val setup = currentSetup()
        val report = ConfigValidator.validate(setup)
        val sb = StringBuilder()
        sb.appendLine("SYSTEM ROMs")
        sb.appendLine("───────────")
        sb.appendLine("U18:       ${u18Field.text.trim()}")
        sb.appendLine("U19:       ${u19Field.text.trim()}")
        val fdromBeside = File(File(u18Field.text.trim()).parentFile ?: File("."), "fdrom.bin")
        sb.appendLine("fdrom:     ${if (fdromBeside.isFile) fdromBeside.path else "(not beside U18 — HD INT 13h may need roms/fdrom.bin)"}")
        sb.appendLine()
        sb.appendLine("MOTHERBOARD")
        sb.appendLine("───────────")
        sb.appendLine("Memory:    ${setup.motherboard.baseMemoryKb} KB")
        sb.appendLine("8087:      ${if (setup.motherboard.mathCoprocessor) "yes" else "no"}")
        sb.appendLine("Video SW1: ${setup.motherboard.initialVideo.label}")
        if (setup.motherboard.postLoop) sb.appendLine("POST loop: yes")
        sb.appendLine()
        sb.appendLine("SYSTEM ADAPTERS")
        sb.appendLine("───────────────")
        sb.appendLine("Graphics:  ${setup.graphics}" + if (setup.showVideo) " (window)" else "")
        sb.appendLine("COM1:      ${if (setup.enableCom1) "yes" else "no"}")
        sb.appendLine("FDC:       ${if (setup.floppy.enabled) "yes @ 0x3F0 / IRQ6 / DMA2" else "no"}")
        if (setup.hardDisk.enabled) {
            sb.appendLine(
                "HD ctrl:   yes @ 0x${setup.hardDisk.ioBase.toString(16)} / IRQ${setup.hardDisk.irq} / DMA${setup.hardDisk.dmaChannel}" +
                    if (setup.hardDisk.useInt13Shim) " (INT13 shim)" else " (FixedDiskBios→Wd1003)",
            )
            sb.appendLine("  I/O:     0x${setup.hardDisk.ioBase.toString(16)}")
            sb.appendLine("  IRQ:     ${setup.hardDisk.irq}")
            sb.appendLine("  DMA:     ${setup.hardDisk.dmaChannel}")
            sb.appendLine("  image:   ${setup.hardDisk.imagePath}")
            sb.appendLine("  size:    ${setup.hardDisk.provisionBytes / (1024 * 1024)} MB (if new)")
            sb.appendLine("  boot:    ${if (setup.hardDisk.bootFromDisk) "hard disk" else "default"}")
        } else {
            sb.appendLine("HD ctrl:   no")
        }
        sb.appendLine()
        sb.appendLine("DRIVES")
        sb.appendLine("──────")
        if (!setup.floppy.enabled || setup.floppy.driveImages.isEmpty()) {
            sb.appendLine("Floppies:  (none)")
        } else {
            setup.floppy.driveImages.forEachIndexed { i, path -> sb.appendLine("  ${'A' + i}: $path") }
        }
        sb.appendLine()
        sb.appendLine("NETWORKS")
        sb.appendLine("────────")
        val nets = networks?.listNetworks().orEmpty()
        if (nets.isEmpty()) sb.appendLine("(none / API unavailable)")
        else nets.forEach { n ->
            val dhcp = if (n.dhcpEnabled) "DHCP ${n.dhcpStartIp}–${n.dhcpEndIp}" else "DHCP off"
            sb.appendLine("• ${n.id}: ${n.name}  gw=${n.gatewayIp}  $dhcp")
        }
        sb.appendLine()
        sb.appendLine("EXPANSION")
        sb.appendLine("─────────")
        val enabled = setup.cards.filter { it.enabled }
        if (enabled.isEmpty()) sb.appendLine("(none)")
        else enabled.forEach { c ->
            sb.appendLine("• ${c.factory.descriptor().name}")
            val cfg = c.effectiveConfig().entries.joinToString(", ") { "${it.key}=${it.value}" }
            if (cfg.isNotBlank()) sb.appendLine("    $cfg")
        }
        sb.appendLine()
        sb.appendLine("VALIDATION")
        sb.appendLine("──────────")
        if (report.issues.isEmpty()) sb.appendLine("OK")
        else report.issues.forEach { issue ->
            val tag = when (issue.severity) {
                ValidationSeverity.ERROR -> "ERROR"
                ValidationSeverity.WARNING -> "WARN "
                ValidationSeverity.INFO -> "INFO "
            }
            sb.appendLine("[$tag] ${issue.message}")
        }
        summaryArea.text = sb.toString()
        summaryArea.caretPosition = 0
    }

    private fun commitStart() {
        if (!validateBeforeLeaving(steps.indexOf(WizardStep.ROMS))) return
        val setup = currentSetup()
        val report = ConfigValidator.validate(setup)
        refreshReview()
        if (report.hasErrors) {
            JOptionPane.showMessageDialog(
                this,
                report.errors.joinToString("\n") { "• ${it.message}" },
                "Configuration errors",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }
        if (report.warnings.isNotEmpty()) {
            val n = JOptionPane.showConfirmDialog(
                this,
                report.warnings.joinToString("\n") { "• ${it.message}" } + "\n\n$finishButtonLabel anyway?",
                "Configuration warnings",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )
            if (n != JOptionPane.YES_OPTION) return
        }
        result = WizardResult(
            setup = setup,
            u18RomPath = u18Field.text.trim(),
            u19RomPath = u19Field.text.trim(),
        )
        dispose()
    }

    private fun onCancel() {
        val dirty = !graphicsCga.isSelected || !com1Check.isSelected || !fdcCheck.isSelected ||
            hdCheck.isSelected || floppyFields.size != (if (preferredFloppy() != null) 1 else 0) ||
            cardRows.any { it.enabled.isSelected }
        if (dirty) {
            val n = JOptionPane.showConfirmDialog(
                this, "Discard this virtual machine configuration?", "Cancel setup",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
            )
            if (n != JOptionPane.YES_OPTION) return
        }
        result = null
        dispose()
    }

    private inner class CardRow(val entry: CardCatalog.Entry) {
        val enabled = JCheckBox(entry.descriptor().name, false)
        private val config = linkedMapOf<String, String>()
        val panel = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                EmptyBorder(4, 6, 4, 6),
            )
            alignmentX = LEFT_ALIGNMENT
            val desc = entry.descriptor()
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).also {
                it.add(enabled)
                it.add(JLabel("[${desc.category}]"))
            }, BorderLayout.WEST)
            add(JLabel(desc.description).also { it.toolTipText = desc.description }, BorderLayout.CENTER)
            add(JButton("Configure…").also {
                it.isEnabled = desc.fields.isNotEmpty()
                it.addActionListener { openConfigDialog() }
            }, BorderLayout.EAST)
            for (f in desc.fields) config[f.key] = f.defaultValue
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        fun configValues(): Map<String, String> = config.toMap()
        fun applyConfig(values: Map<String, String>) {
            for ((k, v) in values) config[k] = v
        }

        private fun openConfigDialog() {
            val desc = entry.descriptor()
            val form = JPanel(GridBagLayout())
            val c = GridBagConstraints().apply {
                insets = Insets(3, 3, 3, 3)
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
            }
            val editors = linkedMapOf<String, () -> String>()
            desc.fields.forEachIndexed { i, field ->
                c.gridx = 0; c.gridy = i; c.weightx = 0.0
                form.add(JLabel(field.label + ":"), c)
                c.gridx = 1; c.weightx = 1.0
                val current = config[field.key] ?: field.defaultValue
                when (field.type) {
                    ConfigFieldType.BOOL -> {
                        val box = JCheckBox(field.description.ifBlank { field.key },
                            current.equals("true", true) || current == "1")
                        form.add(box, c)
                        editors[field.key] = { if (box.isSelected) "true" else "false" }
                    }
                    ConfigFieldType.CHOICE -> {
                        val combo = JComboBox(field.choices.toTypedArray()).also { it.selectedItem = current }
                        form.add(combo, c)
                        editors[field.key] = { combo.selectedItem?.toString() ?: current }
                    }
                    ConfigFieldType.IRQ, ConfigFieldType.DMA, ConfigFieldType.INT -> {
                        val min = field.min ?: 0
                        val max = field.max ?: if (field.type == ConfigFieldType.DMA) 3 else 255
                        val spinner = JSpinner(SpinnerNumberModel(current.toIntOrNull() ?: min, min, max, 1))
                        form.add(spinner, c)
                        editors[field.key] = { spinner.value.toString() }
                    }
                    ConfigFieldType.HEX_INT -> {
                        val tf = JTextField(current, 12)
                        form.add(tf, c)
                        editors[field.key] = { tf.text.trim() }
                    }
                    ConfigFieldType.NETWORK -> {
                        val ids = networks?.listNetworks()?.map { it.id }.orEmpty()
                        if (ids.isNotEmpty()) {
                            val combo = JComboBox(ids.toTypedArray()).also {
                                it.selectedItem = if (current in ids) current else ids.first()
                            }
                            form.add(combo, c)
                            editors[field.key] = { combo.selectedItem?.toString() ?: current }
                        } else {
                            val tf = JTextField(current, 18)
                            form.add(tf, c)
                            editors[field.key] = { tf.text.trim() }
                        }
                    }
                    ConfigFieldType.PATH, ConfigFieldType.STRING -> {
                        val tf = JTextField(current, 18)
                        form.add(tf, c)
                        editors[field.key] = { tf.text.trim() }
                    }
                }
            }
            val ok = JOptionPane.showConfirmDialog(
                this@WizardDialog, form, "Configure ${desc.name}",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
            )
            if (ok == JOptionPane.OK_OPTION) {
                for ((k, getter) in editors) config[k] = getter()
            }
        }
    }
}

internal fun defaultConfigFor(fields: List<ConfigField>): Map<String, String> =
    fields.associate { it.key to it.defaultValue }

internal fun fitWizardSize(
    preferred: Dimension,
    available: Dimension,
    margin: Int = 32,
): Dimension = Dimension(
    minOf(preferred.width, (available.width - margin).coerceAtLeast(1)),
    minOf(preferred.height, (available.height - margin).coerceAtLeast(1)),
)
