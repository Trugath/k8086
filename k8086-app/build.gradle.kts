plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":k8086-host"))
    implementation(project(":k8086-client"))
    implementation(project(":k8086-protocol"))
    implementation(project(":k8086-emulator"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.trugath.k8086.app.WorkstationMainKt")
}

tasks.register<JavaExec>("docScreenshots") {
    group = "documentation"
    description = "Capture workstation UI screenshots into docs/screenshots"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.trugath.k8086.app.DocScreenshotsMainKt")
    workingDir = rootProject.projectDir
    // Pass an optional output directory: -PdocScreenshotDir=docs/screenshots
    val out = (findProperty("docScreenshotDir") as String?) ?: "docs/screenshots"
    args(out)
}
