package com.trugath.k8086.app

import com.trugath.k8086.client.ManagerFrame
import com.trugath.k8086.client.defaultRomPaths
import com.trugath.k8086.client.romsExist
import com.trugath.k8086.host.LocalHost
import javax.swing.JOptionPane

fun main() {
    val (u18, u19) = defaultRomPaths()
    if (!romsExist(u18, u19)) {
        System.err.println("ROM BIOS not found. Expected:")
        System.err.println("  $u18")
        System.err.println("  $u19")
        System.err.println("Shipped defaults are roms/u18.bin and roms/u19.bin.")
        System.err.println("Override with K8086_U18_ROM / K8086_U19_ROM.")
        JOptionPane.showMessageDialog(
            null,
            "ROM BIOS not found.\n$u18\n$u19",
            "k8086 Workstation",
            JOptionPane.ERROR_MESSAGE,
        )
        return
    }

    val host = LocalHost()
    Runtime.getRuntime().addShutdownHook(Thread { host.close() })
    println("k8086 Workstation — multi-VM host")
    ManagerFrame.launch(host, u18, u19)
}
