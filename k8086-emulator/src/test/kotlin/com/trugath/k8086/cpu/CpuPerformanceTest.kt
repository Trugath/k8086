package com.trugath.k8086.cpu

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime

/**
 * Opt-in hot-path microbenchmark for CPU refactors.
 *
 * Enable with `-Dk8086.cpuPerf.enabled=true` (Gradle task `cpuPerformanceTest`).
 * Reports warmed-up instructions/second, allocated bytes/instruction, GC activity,
 * and steady heap. Acceptance: zero steady-state per-instruction allocation.
 *
 * All models are warmed before any measurement so bimorphic call-site transitions
 * do not attribute JIT recompilation traffic to the hot path.
 */
@Tag("cpu-performance")
class CpuPerformanceTest {
    @Test
    fun denseMixHasZeroSteadyStateAllocation() {
        assumeTrue(System.getProperty(ENABLED_PROPERTY) == "true")

        val models = listOf(
            "8086" to { Emulator8086() },
            "8088" to { Emulator8088() },
            "80286" to { Emulator80286() },
        )

        // Shared warmup across concrete types so polymorphic sites stabilize first.
        for ((_, factory) in models) {
            val cpu = factory()
            installDenseMix(cpu)
            repeat(WARMUP_ITERS) {
                resetIp(cpu)
                runSteps(cpu, STEPS_PER_ITER)
            }
        }
        System.gc()
        Thread.sleep(50)

        val reports = mutableListOf<String>()
        for ((name, factory) in models) {
            reports += runModel(name, factory)
        }
        println("=== CPU performance baseline ===")
        reports.forEach { println(it) }
        val failed = reports.any { it.startsWith("FAIL ") }
        assertTrue(!failed) { reports.joinToString("\n") }
    }

    private fun runModel(name: String, factory: () -> Emulator8086): String {
        val cpu = factory()
        installDenseMix(cpu)

        // Per-model settle after shared warmup (keeps caches hot, avoids JIT during measure).
        repeat(SETTLE_ITERS) {
            resetIp(cpu)
            runSteps(cpu, STEPS_PER_ITER)
        }
        System.gc()
        Thread.sleep(20)

        val threadMx = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(threadMx != null && threadMx.isThreadAllocatedMemorySupported)
        threadMx!!.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().threadId()

        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gcCountBefore = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }
        val gcTimeBefore = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }
        val heapBefore = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        val allocBefore = threadMx.getThreadAllocatedBytes(threadId)

        val elapsedNs = measureNanoTime {
            repeat(MEASURE_ITERS) {
                resetIp(cpu)
                runSteps(cpu, STEPS_PER_ITER)
            }
        }

        val allocAfter = threadMx.getThreadAllocatedBytes(threadId)
        val heapAfter = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        val gcCountAfter = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }
        val gcTimeAfter = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }

        val totalSteps = MEASURE_ITERS.toLong() * STEPS_PER_ITER
        val ips = totalSteps * 1_000_000_000.0 / elapsedNs
        val allocBytes = (allocAfter - allocBefore).coerceAtLeast(0)
        val bytesPerInstr = allocBytes.toDouble() / totalSteps
        val gcCount = (gcCountAfter - gcCountBefore).coerceAtLeast(0)
        val gcTime = (gcTimeAfter - gcTimeBefore).coerceAtLeast(0)
        val heapDelta = heapAfter - heapBefore

        // Steady-state hot path must not allocate. Allow a tiny absolute budget for
        // measurement noise / incidental JVM activity outside the step loop.
        val ok = bytesPerInstr < 0.01
        if (!ok) {
            System.err.println(
                "PERF_FAIL $name allocated $bytesPerInstr bytes/instruction " +
                    "($allocBytes bytes over $totalSteps steps)",
            )
        }

        return buildString {
            append(if (ok) "OK " else "FAIL ")
            append(name)
            append(": ips=")
            append("%.0f".format(ips))
            append(" allocB/instr=")
            append("%.4f".format(bytesPerInstr))
            append(" gcCount=")
            append(gcCount)
            append(" gcMs=")
            append(gcTime)
            append(" heapDelta=")
            append(heapDelta)
        }
    }

    private fun installDenseMix(cpu: Emulator8086) {
        cpu.setReg16(REG_CS, 0x1000)
        cpu.setReg16(REG_DS, 0x1000)
        cpu.setReg16(REG_ES, 0x1000)
        cpu.setReg16(REG_SS, 0x2000)
        cpu.setReg16(REG_SP, 0xFFFE)
        cpu.setReg16(REG_AX, 0x1234)
        cpu.setReg16(REG_BX, 0x0100)
        cpu.setReg16(REG_CX, 0x0003)
        cpu.setReg16(REG_DX, 0x0000)
        cpu.setReg16(REG_SI, 0x0200)
        cpu.setReg16(REG_DI, 0x0300)
        cpu.setFlagsValue(0)
        // Tight mix: ALU, shifts, memory MOV, jumps, stack — no I/O, no HLT.
        val body = intArrayOf(
            0x40,                   // INC AX
            0x01, 0xD8,             // ADD AX, BX
            0x29, 0xD8,             // SUB AX, BX
            0x21, 0xD8,             // AND AX, BX
            0x09, 0xD8,             // OR AX, BX
            0x31, 0xD8,             // XOR AX, BX
            0xD1, 0xE0,             // SHL AX, 1
            0xD1, 0xE8,             // SHR AX, 1
            0x89, 0x07,             // MOV [BX], AX
            0x8B, 0x07,             // MOV AX, [BX]
            0x50,                   // PUSH AX
            0x58,                   // POP AX
            0x90,                   // NOP
            0xEB, 0xE8,             // JMP short back to start (-24)
        )
        for (i in body.indices) {
            cpu.writePhysByte(0x10100 + i, body[i] and 0xFF)
        }
        resetIp(cpu)
    }

    private fun resetIp(cpu: Emulator8086) {
        cpu.setIp(0x0100)
    }

    private fun runSteps(cpu: Emulator8086, steps: Int) {
        repeat(steps) {
            if (!cpu.executeSingleInstruction()) {
                resetIp(cpu)
            }
        }
    }

    companion object {
        private const val ENABLED_PROPERTY = "k8086.cpuPerf.enabled"
        private const val WARMUP_ITERS = 200
        private const val SETTLE_ITERS = 50
        private const val MEASURE_ITERS = 400
        private const val STEPS_PER_ITER = 5_000
    }
}
