plugins {
    kotlin("jvm")
}

group = "com.trugath.k8086.cards"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":k8086-api"))
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveBaseName.set("heartbeat")
}
