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
    applicationName = "k8086"
    mainClass.set("com.trugath.k8086.app.WorkstationMainKt")
}

// Relative roms/ and disks/ paths resolve from the process working directory.
// Ensure start scripts (and double-clicked launches) run from the install root.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readText().replace(
                ":execute\r\n@rem Setup the command line",
                ":execute\r\ncd /d \"%APP_HOME%\"\r\n@rem Setup the command line",
            ).replace(
                ":execute\n@rem Setup the command line",
                ":execute\ncd /d \"%APP_HOME%\"\n@rem Setup the command line",
            ),
        )
        unixScript.writeText(
            unixScript.readText().replace(
                "# Determine the Java command to use to start the JVM.",
                "cd \"\$APP_HOME\" || exit 1\n\n# Determine the Java command to use to start the JVM.",
            ),
        )
    }
}

distributions {
    main {
        distributionBaseName.set("k8086")
        contents {
            from(rootProject.layout.projectDirectory) {
                include("LICENSE", "NOTICE")
            }
            from(rootProject.layout.projectDirectory.dir("roms")) {
                include("*.bin")
                into("roms")
            }
            from(rootProject.layout.projectDirectory.dir("disks")) {
                include("fd.img")
                into("disks")
            }
        }
    }
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
