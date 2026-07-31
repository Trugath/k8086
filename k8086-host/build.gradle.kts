plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":k8086-protocol"))
    implementation(project(":k8086-emulator"))
    implementation(project(":k8086-api"))
    implementation(project(":k8086-net"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
}
