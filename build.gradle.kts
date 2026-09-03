import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    kotlin("plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.intellij.platform") version "2.13.1" apply false
}

/*
 * 빌드 대상 IntelliJ Platform(IDE) 위치 결정 — :core / :compose-ui 가 공유한다.
 *
 * 우선순위
 *   1. Gradle 프로퍼티 `ideLocalPath`   (-PideLocalPath=... / gradle.properties / ~/.gradle/gradle.properties)
 *   2. 환경변수 `TERMINAL_ALERT_IDE_HOME`
 *   3. OS 별 Android Studio 기본 설치 경로 자동 탐색 (macOS / Windows / Linux)
 *   4. 아무것도 없으면 null → 각 모듈이 `androidStudio(platformFallbackVersion)` 을 내려받아 사용
 */
fun isIdeHome(dir: File): Boolean =
    dir.isDirectory && listOf(
        "product-info.json", "build.txt",                      // Windows / Linux
        "Resources/product-info.json", "Resources/build.txt",  // macOS (*.app/Contents)
    ).any { File(dir, it).isFile }

val userHome: String = System.getProperty("user.home")
val osName: String = System.getProperty("os.name").lowercase()
val localAppData: String? = System.getenv("LOCALAPPDATA")

val defaultIdeCandidates: List<File> = when {
    osName.contains("mac") -> listOf(
        "/Applications/Android Studio.app/Contents",
        "$userHome/Applications/Android Studio.app/Contents",
    )
    osName.contains("win") -> listOfNotNull(
        "C:/Program Files/Android/Android Studio",
        localAppData?.let { "$it/Programs/Android Studio" },
    )
    else -> listOf(
        "/opt/android-studio",
        "/usr/local/android-studio",
        "$userHome/android-studio",
        "/snap/android-studio/current/android-studio",
    )
}.map(::File)

val explicitIdeHome: String? =
    providers.gradleProperty("ideLocalPath").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("TERMINAL_ALERT_IDE_HOME").orNull?.takeIf { it.isNotBlank() }

val ideLocalPath: String? = if (explicitIdeHome != null) {
    val dir = File(explicitIdeHome)
    if (!isIdeHome(dir)) {
        throw GradleException(
            "ideLocalPath / TERMINAL_ALERT_IDE_HOME does not point to an IDE installation: '$explicitIdeHome'\n" +
                "  Windows/Linux: Android Studio install root (e.g. C:/Program Files/Android/Android Studio)\n" +
                "  macOS:         /Applications/Android Studio.app/Contents"
        )
    }
    dir.absolutePath
} else {
    defaultIdeCandidates.firstOrNull(::isIdeHome)?.absolutePath
}

val platformFallbackVersion: String =
    providers.gradleProperty("platformFallbackVersion").orNull?.takeIf { it.isNotBlank() } ?: "2026.1.3.7"

extra["ideLocalPath"] = ideLocalPath
extra["platformFallbackVersion"] = platformFallbackVersion

logger.lifecycle(
    if (ideLocalPath != null) "IntelliJ Platform: local IDE at $ideLocalPath"
    else "IntelliJ Platform: no local IDE found → downloading Android Studio $platformFallbackVersion " +
        "(set -PideLocalPath=<IDE dir> or TERMINAL_ALERT_IDE_HOME to use a local install)"
)
