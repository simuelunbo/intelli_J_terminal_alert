plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
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
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

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
