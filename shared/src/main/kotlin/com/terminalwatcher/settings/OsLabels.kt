package com.terminalwatcher.settings

/**
 * OS별 UI 라벨과 사운드 정책을 담는 값 객체.
 *
 * ## 왜 shared 모듈에 있는가
 * `SettingsUiState`가 이 값을 품고, `core`(VM/DSL 패널)와 `compose-ui`(Compose 패널)가
 * 모두 이를 소비한다. IntelliJ Platform 의존 없이 순수 Kotlin/JDK만 사용한다.
 *
 * ## OS 판정은 어디서 하는가
 * `core/.../settings/SettingsViewModel.kt`의 생성자에서 `com.intellij.openapi.util.SystemInfo`
 * 로 판정하여 [MAC] / [WINDOWS] / [LINUX] 중 하나를 주입한다. UI 레이어에는 OS 감지가
 * 누출되지 않는다 — UI는 전달받은 라벨·리스트·확장자 집합만 읽는다.
 *
 * ## 필드 설계 원칙
 * - [badgeLabel] / [systemNotificationLabel]: 각 OS의 자연스러운 명칭 (Dock, Taskbar 등).
 * - [soundList] / [soundDir] / [soundExtension]: 해당 OS의 빌트인 사운드 카탈로그.
 *   [defaultSoundPath] 로 완전 경로를 구성한다.
 * - [supportedSoundExtensions]: **그 OS가 실제로 재생 가능한** 확장자만 포함한다.
 *   파일 선택기 필터에 사용되며, 사용자가 재생 불가 파일을 고르는 상황을 원천 차단한다.
 *
 * ## 새 OS를 추가하려면
 * 1. 이 파일에 `val OTHER = OsLabels(...)` 추가.
 * 2. `core/.../settings/SettingsViewModel.kt`의 `osLabels` 분기에 케이스 추가.
 * 3. 해당 OS 전용 `Notifier` 구현을 `core/.../{os}/` 에 작성하고
 *    `core/.../notify/NotifierProvider.kt` 의 FQN 분기와
 *    `core/src/main/resources/META-INF/plugin.xml`의 `applicationService` 등록을 함께 추가.
 */
data class OsLabels(
    val badgeLabel: String,
    val systemNotificationLabel: String,
    val soundList: List<String>,
    val soundDir: String,
    val soundExtension: String,
    val supportedSoundExtensions: Set<String>,
) {
    /** [soundList]의 항목을 OS별 절대 경로로 변환한다. */
    fun defaultSoundPath(name: String): String =
        if (name.isBlank()) "" else "$soundDir$name$soundExtension"

    companion object {
        val MAC = OsLabels(
            badgeLabel = "Dock badge count",
            systemNotificationLabel = "macOS system notification",
            soundList = listOf(
                "Glass", "Pop", "Ping", "Purr", "Blow", "Bottle",
                "Frog", "Hero", "Submarine", "Morse", "Tink",
            ),
            soundDir = "/System/Library/Sounds/",
            soundExtension = ".aiff",
            // afplay(CoreAudio)는 Apple이 번들한 코덱을 모두 재생한다.
            supportedSoundExtensions = setOf("aiff", "aif", "wav", "mp3", "m4a", "caf", "aac"),
        )

        val WINDOWS = OsLabels(
            badgeLabel = "Taskbar attention (flash)",
            systemNotificationLabel = "Windows system notification",
            soundList = listOf(
                "chimes", "chord", "ding", "notify", "tada",
                "Alarm01", "Alarm02", "Alarm03", "Ring01", "Ring02",
            ),
            soundDir = (System.getenv("SystemRoot") ?: "C:\\Windows") + "\\Media\\",
            soundExtension = ".wav",
            // WAV/AIFF 는 javax.sound.sampled, MP3/WMA/M4A/AAC 는
            // WindowsNotifier 의 PowerShell MediaPlayer 폴백으로 재생한다.
            supportedSoundExtensions = setOf("wav", "aiff", "aif", "mp3", "m4a", "wma", "aac"),
        )

        val LINUX = OsLabels(
            badgeLabel = "Window attention",
            systemNotificationLabel = "System notification",
            soundList = emptyList(),
            soundDir = "/usr/share/sounds/",
            soundExtension = ".wav",
            // FallbackNotifier 는 javax.sound.sampled 만 사용한다.
            supportedSoundExtensions = setOf("wav", "aiff", "aif"),
        )
    }
}
