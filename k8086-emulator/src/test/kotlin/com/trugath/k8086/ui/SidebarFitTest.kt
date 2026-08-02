package com.trugath.k8086.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.GraphicsEnvironment
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.FontUIResource

class SidebarFitTest {
    @Test
    fun everyStepLabelFitsSidebarAtAllFontSizes() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val original = UIManager.get("Label.font")
        try {
            for (size in listOf(12, 16, 20, 24, 30)) {
                UIManager.put("Label.font", FontUIResource(Font.DIALOG, Font.PLAIN, size))
                checkFit(size)
            }
        } finally {
            UIManager.put("Label.font", original)
        }
    }

    private fun checkFit(fontSize: Int) {
        SwingUtilities.invokeAndWait {
            val cls = Class.forName("com.trugath.k8086.ui.WizardDialog")
            val netApi = Class.forName("com.trugath.k8086.protocol.NetworkApi")
            val modeCls = Class.forName("com.trugath.k8086.ui.WizardMode")
            val createMode = modeCls.enumConstants.first { it.toString() == "CREATE" }
            val ctor = cls.declaredConstructors.first {
                it.parameterCount >= 6 && it.parameterTypes[1] == modeCls
            }.also { it.isAccessible = true }
            val dialog = ctor.newInstance(
                null, // networks
                createMode,
                "Create",
                "roms/u18.bin",
                "roms/u19.bin",
                "roms/fdrom.bin",
                null, // initial
            )

            @Suppress("UNCHECKED_CAST")
            val labels = cls.getDeclaredField("stepLabels")
                .also { it.isAccessible = true }
                .get(dialog) as List<JLabel>
            val showStep = cls.getDeclaredMethod("showStep", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }

            val sidebar = labels.first().parent as JPanel
            val available = sidebar.preferredSize.width - sidebar.insets.left - sidebar.insets.right

            var widest = 0
            for (step in labels.indices) {
                showStep.invoke(dialog, step)
                labels.forEachIndexed { i, label ->
                    val need = label.preferredSize.width
                    widest = maxOf(widest, need)
                    assertTrue(
                        need <= available,
                        "font=$fontSize step=$step label=$i need=$need available=$available text='${label.text}'",
                    )
                }
            }
            (dialog as JDialog).dispose()
            assertTrue(widest > 0, "font=$fontSize: no label widths measured")
        }
    }
}
