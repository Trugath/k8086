plugins {
    kotlin("jvm")
}

group = "com.trugath.k8086.cards"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":k8086-api"))
    testImplementation(project(":k8086-api"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveBaseName.set("vga")
}

tasks.test {
    useJUnitPlatform()
}
