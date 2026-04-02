plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.intellij.platform.module")
}

group = "com.terminalwatcher"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/Android Studio.app/Contents")
        bundledModule("intellij.platform.jewel.foundation")
        bundledModule("intellij.platform.jewel.ui")
        bundledModule("intellij.platform.jewel.ideLafBridge")
        bundledModule("intellij.libraries.compose.foundation.desktop")
        bundledModule("intellij.libraries.compose.runtime.desktop")
        bundledModule("intellij.libraries.skiko")
        bundledModule("intellij.platform.compose")
    }

    compileOnly(project(":shared"))
}

kotlin {
    jvmToolchain(17)
}
