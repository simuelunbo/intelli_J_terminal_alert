plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform")
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
