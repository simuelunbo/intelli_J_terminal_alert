plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.terminalwatcher"
version = "1.2.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}
