plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":k8086-protocol"))
    implementation(project(":k8086-host"))
    implementation(project(":k8086-emulator"))
}

kotlin {
    jvmToolchain(21)
}
