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
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.trugath.k8086.app.WorkstationMainKt")
}
