plugins {
    kotlin("multiplatform")
    jacoco
}

group = "com.trugath.k8086"

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":k8086-api"))
            implementation(project(":k8086-protocol"))
        }
        jvmMain.dependencies {
            implementation(project(":k8086-net"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
            implementation(project(":cards:sample-rom"))
            implementation(project(":cards:ram-umb"))
            implementation(project(":cards:adlib"))
            implementation(project(":cards:heartbeat"))
            implementation(project(":cards:ems-window"))
            implementation(project(":cards:de220"))
            implementation(project(":cards:mem-expansion"))
            implementation(project(":cards:uart-8250"))
            implementation(project(":cards:rtc-mm58167"))
            implementation(project(":cards:lpt"))
            implementation(project(":cards:gameport"))
        }
    }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the single-instance emulator CLI"
    mainClass.set("com.trugath.k8086.MainKt")
    workingDir = rootProject.projectDir
    val compilation = kotlin.jvm().compilations.getByName("main")
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform {
        excludeTags("single-step-8086", "single-step-8088", "single-step-80286", "cpu-performance")
    }
    workingDir = rootProject.projectDir
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    finalizedBy("jacocoTestReport")
}

fun org.gradle.api.tasks.testing.Test.configureSingleStepSuite(tag: String, descriptionText: String) {
    description = descriptionText
    group = "verification"
    val compilation = kotlin.jvm().compilations.getByName("test")
    testClassesDirs = compilation.output.classesDirs
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
    useJUnitPlatform {
        includeTags(tag)
    }
    workingDir = rootProject.projectDir
    systemProperty("k8086.singleStep.enabled", "true")
    systemProperty(
        "k8086.singleStep.opcodes",
        providers.gradleProperty("singleStepOpcodes").orElse("").get(),
    )
    systemProperty(
        "k8086.singleStep.limit",
        providers.gradleProperty("singleStepLimit").orElse("2000").get(),
    )
    systemProperty(
        "k8086.singleStep.failures",
        providers.gradleProperty("singleStepFailures").orElse("20").get(),
    )
    maxHeapSize = "2g"
}

val singleStep8086Test by tasks.registering(Test::class) {
    configureSingleStepSuite(
        "single-step-8086",
        "Runs hardware-generated single-step 8086 conformance tests.",
    )
}

val singleStep8088Test by tasks.registering(Test::class) {
    configureSingleStepSuite(
        "single-step-8088",
        "Runs hardware-generated single-step 8088 (v2) conformance tests.",
    )
}

val singleStep80286Test by tasks.registering(Test::class) {
    configureSingleStepSuite(
        "single-step-80286",
        "Runs hardware-generated single-step 80286 (v1_real_mode) conformance tests.",
    )
}

val cpuPerformanceTest by tasks.registering(Test::class) {
    description = "Runs opt-in CPU hot-path throughput/allocation baseline."
    group = "verification"
    val compilation = kotlin.jvm().compilations.getByName("test")
    testClassesDirs = compilation.output.classesDirs
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles
    useJUnitPlatform {
        includeTags("cpu-performance")
    }
    workingDir = rootProject.projectDir
    systemProperty("k8086.cpuPerf.enabled", "true")
    jvmArgs("-Xms256m", "-Xmx512m")
    maxHeapSize = "512m"
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("jvmTest"))
    val extraProjects = listOf(
        ":k8086-api",
        ":cards:sample-rom",
        ":cards:ram-umb",
        ":cards:adlib",
        ":cards:heartbeat",
        ":cards:ems-window",
        ":cards:de220",
        ":cards:mem-expansion",
        ":cards:uart-8250",
        ":cards:rtc-mm58167",
        ":cards:lpt",
        ":cards:gameport",
    )
    classDirectories.setFrom(
        files(
            kotlin.jvm().compilations.getByName("main").output.allOutputs,
            *extraProjects.map {
                val proj = project(it)
                val kotlinExt = proj.extensions.findByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java)
                if (kotlinExt != null) {
                    kotlinExt.target.compilations.getByName("main").output.allOutputs
                } else {
                    proj.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
                        .jvm().compilations.getByName("main").output.allOutputs
                }
            }.toTypedArray(),
        ).asFileTree.matching {
            exclude("**/META-INF/**")
        },
    )
    additionalSourceDirs.setFrom(
        files(
            kotlin.sourceSets.getByName("commonMain").kotlin.srcDirs,
            kotlin.sourceSets.getByName("jvmMain").kotlin.srcDirs,
            *extraProjects.map { projPath ->
                val proj = project(projPath)
                val jvmExt = proj.extensions.findByType(org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension::class.java)
                if (jvmExt != null) {
                    jvmExt.sourceSets.getByName("main").kotlin.srcDirs
                } else {
                    proj.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
                        .sourceSets.getByName("commonMain").kotlin.srcDirs
                }
            }.toTypedArray(),
        ),
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.register("test") {
    dependsOn("jvmTest")
    group = "verification"
}
