plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform")
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
        bundledPlugin("org.jetbrains.plugins.terminal")
        pluginModule(implementation(project(":compose-ui")))
    }

    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

kotlin {
    jvmToolchain(17)
}

tasks {
    test {
        useJUnitPlatform()
    }

    patchPluginXml {
        sinceBuild.set("243")
    }
}
