plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "k8086"
include("k8086-api")
include("k8086-emulator")
include("k8086-protocol")
include("k8086-net")
include("k8086-host")
include("k8086-client")
include("k8086-app")
include("cards:sample-rom")
include("cards:ram-umb")
include("cards:adlib")
include("cards:heartbeat")
include("cards:ems-window")
include("cards:de220")
include("cards:mem-expansion")
include("cards:uart-8250")
include("cards:rtc-mm58167")
include("cards:lpt")
include("cards:gameport")
include("cards:vga")
