plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.intellij.platform.module")
}

group = "com.terminalwatcher"
version = "1.2.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// 루트 build.gradle.kts 에서 결정 (로컬 IDE 경로 or null)
val ideLocalPath: String? by rootProject.extra
val platformFallbackVersion: String by rootProject.extra

dependencies {
    intellijPlatform {
        val ideHome = ideLocalPath
        if (ideHome != null) local(ideHome) else androidStudio(platformFallbackVersion)
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
