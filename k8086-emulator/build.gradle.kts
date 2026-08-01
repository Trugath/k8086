plugins {
    kotlin("jvm")
    application
    jacoco
}

group = "com.trugath.k8086"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":k8086-api"))
    implementation(project(":k8086-protocol"))
    implementation(project(":k8086-net"))
    testImplementation(kotlin("test"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    // Example cards on the test classpath for direct factory / attach tests.
    testImplementation(project(":cards:sample-rom"))
    testImplementation(project(":cards:ram-umb"))
    testImplementation(project(":cards:adlib"))
    testImplementation(project(":cards:heartbeat"))
    testImplementation(project(":cards:ems-window"))
    testImplementation(project(":cards:de220"))
}

tasks.test {
    useJUnitPlatform {
        // Opt-in suites run via dedicated tasks.
        excludeTags("single-step-8086", "single-step-8088", "single-step-80286", "cpu-performance")
    }
    // ROM/disk fixtures live at the repo root.
    workingDir = rootProject.projectDir
    finalizedBy(tasks.jacocoTestReport)
}

fun org.gradle.api.tasks.testing.Test.configureSingleStepSuite(tag: String, descriptionText: String) {
    description = descriptionText
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
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
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("cpu-performance")
    }
    workingDir = rootProject.projectDir
    systemProperty("k8086.cpuPerf.enabled", "true")
    // Match typical desktop settings so baseline comparisons stay meaningful.
    jvmArgs("-Xms256m", "-Xmx512m")
    maxHeapSize = "512m"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // Include API + example cards exercised via testImplementation.
    val extraProjects = listOf(
        ":k8086-api",
        ":cards:sample-rom",
        ":cards:ram-umb",
        ":cards:adlib",
        ":cards:heartbeat",
        ":cards:ems-window",
        ":cards:de220",
    )
    classDirectories.setFrom(
        files(
            sourceSets.main.get().output,
            *extraProjects.map { project(it).extensions.getByType(SourceSetContainer::class.java).getByName("main").output }.toTypedArray(),
        ).asFileTree.matching {
            exclude("**/META-INF/**")
        },
    )
    additionalSourceDirs.setFrom(
        files(
            sourceSets.main.get().allSource.srcDirs,
            *extraProjects.map {
                project(it).extensions.getByType(SourceSetContainer::class.java).getByName("main").allSource.srcDirs
            }.toTypedArray(),
        ),
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.trugath.k8086.MainKt")
}
