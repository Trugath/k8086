plugins {
    kotlin("jvm") version "2.3.21" apply false
}

allprojects {
    group = "com.trugath.k8086"
    // Override for releases: ./gradlew :k8086-app:distZip -PreleaseVersion=1.0.0
    version = (findProperty("releaseVersion") as String?) ?: "1.0-SNAPSHOT"
}
