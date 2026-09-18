plugins {
    id("org.jetbrains.kotlin.jvm")
}

group = "com.terminalwatcher"
version = "1.2.2"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}
