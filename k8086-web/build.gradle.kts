plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("k8086")
        browser {
            commonWebpackConfig {
                outputFileName = "k8086.js"
            }
        }
        binaries.executable()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":k8086-emulator"))
        }
    }
}

fun npmCommand(vararg args: String): List<String> {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    return if (windows) listOf("cmd.exe", "/c", "npm", *args) else listOf("npm", *args)
}

val demoDir = rootProject.layout.projectDirectory.dir("web-demo")
val demoPublic = demoDir.dir("public")
val webpackDir = layout.buildDirectory.dir("kotlin-webpack/wasmJs/productionExecutable")

val stageWebDemoWasm by tasks.registering(Copy::class) {
    group = "distribution"
    dependsOn("wasmJsBrowserProductionWebpack")
    from(webpackDir)
    into(demoPublic)
}

val stageWebDemoAssets by tasks.registering(Copy::class) {
    group = "distribution"
    from(rootProject.file("roms/u18.bin"))
    from(rootProject.file("roms/u19.bin"))
    from(rootProject.file("disks/fd.img"))
    into(demoPublic.dir("assets"))
}

val npmCiWebDemo by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(stageWebDemoWasm, stageWebDemoAssets)
    workingDir = demoDir.asFile
    commandLine(npmCommand("ci"))
    inputs.file(demoDir.file("package-lock.json"))
    inputs.file(demoDir.file("package.json"))
}

val viteBuildWebDemo by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(npmCiWebDemo)
    workingDir = demoDir.asFile
    commandLine(npmCommand("run", "build"))
    inputs.dir(demoDir.dir("src"))
    inputs.file(demoDir.file("index.html"))
    inputs.file(demoDir.file("vite.config.ts"))
    outputs.dir(demoDir.dir("dist"))
}

tasks.register("assembleWebDemo") {
    group = "distribution"
    description = "Build the static in-browser demo (Wasm + Vite) into web-demo/dist"
    dependsOn(viteBuildWebDemo)
}

tasks.register("compileWasmJs") {
    group = "verification"
    description = "Smoke-compile the WasmJS demo façade"
    dependsOn("compileKotlinWasmJs")
}
