plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

group = "com.terminalwatcher"
version = "1.2.2"

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
        pluginVerifier()
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
    // The terminal API in the minimum supported 2026.1 IDE is Java 21 bytecode.
    // javac must be able to read it now that the extension adapter is Java.
    jvmToolchain(providers.gradleProperty("buildJavaVersion").orElse("21").get().toInt())
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

intellijPlatform {
    pluginVerification {
        ides {
            val verificationIdePath = providers.gradleProperty("verificationIdePath").orNull ?: ideLocalPath
            if (verificationIdePath != null) local(file(verificationIdePath)) else create("AI", platformFallbackVersion)
            providers.gradleProperty("verificationIdeVersion").orNull?.let { create("IU", it) }
        }
    }
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask>("verifyPlugin") {
    // The verifier clears its extraction directory on startup. Keep other projects'
    // verifier processes from deleting this task's in-use dependencies.
    systemProperty("plugin.verifier.home.dir", layout.buildDirectory.dir("pluginVerifier-cache").get().asFile.absolutePath)
}

tasks {
    test {
        useJUnitPlatform()
    }

    patchPluginXml {
        // ShellExecOptionsCustomizer (our env-injection EP) only exists from 2026.1.
        sinceBuild.set("261")
    }
}
