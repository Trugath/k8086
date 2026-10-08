plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    jvmToolchain(21)
    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
        }
    }
}

tasks.register("test") {
    dependsOn("jvmTest")
    group = "verification"
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
