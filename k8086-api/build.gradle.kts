plugins {
    kotlin("jvm")
}

group = "com.trugath.k8086"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}
