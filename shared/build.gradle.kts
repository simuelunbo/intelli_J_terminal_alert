plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.terminalwatcher"
version = "1.2.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}
