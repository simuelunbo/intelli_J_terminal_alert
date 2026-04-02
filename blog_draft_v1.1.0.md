# "Codex 알림이 왜 양쪽 IDE에 다 뜨지?" — Terminal AI Watcher 1.1.0 개발기

> **제목 후보**
> 1. "Codex 알림이 왜 양쪽 IDE에 다 뜨지?" — Terminal AI Watcher 1.1.0 개발기
> 2. IntelliJ 플러그인 Settings UI를 Compose(Jewel)로 마이그레이션하면서 겪은 것들
> 3. stale 스크립트 하나가 만든 멀티 IDE 알림 버그 — PPID chain routing 트러블슈팅

---

## 도입

[Terminal AI Watcher](https://github.com/simjunbo/android-studio-terminal-alert)는 Android Studio / IntelliJ 터미널에서 AI CLI 도구(Claude Code, Codex, Gemini CLI)가 작업을 완료하거나 권한을 요청하면 IDE 알림으로 알려주는 플러그인이다. 1.0.x에서 기본 기능을 완성한 뒤, 1.1.0에서는 두 가지를 목표로 잡았다.

1. **Settings UI 현대화** — Swing GridBagLayout에서 Compose(Jewel) + DSL 듀얼 패널로
2. **알림 채널 세분화** — "알림 켜기/끄기" 하나가 아닌, Dock 뱃지·시스템 알림·IDE 풍선·사운드를 독립 제어

그런데 이 작업을 하다가 예상치 못한 버그를 발견했다. **Codex 알림이 Android Studio와 IntelliJ 양쪽 모두에 뜨는 문제**였다. 원인을 찾는 과정이 가장 어려우면서도 가장 배운 게 많았던 부분이라, 이 글에서 함께 다루려 한다.

---

## 1. Settings UI: Compose(Jewel)와 DSL 듀얼 패널

### 왜 듀얼 패널인가

IntelliJ 2025.1+에는 [Jewel](https://github.com/JetBrains/jewel)이라는 Compose for Desktop 기반 UI 프레임워크가 번들되어 있다. 새로운 Settings UI를 만드는 데 이상적이지만, 구버전 IDE에서는 사용할 수 없다. 그래서 **런타임에 Jewel 사용 가능 여부를 확인하고, 불가능하면 Kotlin UI DSL로 폴백**하는 구조를 택했다.

```kotlin
private fun rebuildContent() {
    val panel = wrapper ?: return
    panel.removeAll()
    val content = if (isJewelAvailable()) {
        ComposeSettingsPanel.create(viewModel)
    } else {
        DslSettingsPanel.create(viewModel)
    }
    panel.add(content, BorderLayout.CENTER)
    panel.revalidate()
    panel.repaint()
}

private fun isJewelAvailable(): Boolean = try {
    Class.forName("org.jetbrains.jewel.bridge.theme.SwingBridgeThemeKt")
    true
} catch (_: ClassNotFoundException) {
    false
}
```

`Class.forName`으로 Jewel 클래스를 찾아보고, 있으면 Compose 패널을, 없으면 DSL 패널을 생성한다.

### ViewModel로 UI 로직 공유

두 패널이 동일한 로직을 공유해야 하므로 ViewModel을 분리했다. Android 개발에서 익숙한 MVI 패턴을 그대로 적용했다.

```kotlin
data class SettingsUiState(
    val enableBadgeCount: Boolean = true,
    val enableSystemNotification: Boolean = true,
    val enableIdeBalloon: Boolean = true,
    val enableSound: Boolean = true,
    val soundName: String = "Glass",
    val customSoundPath: String = "",
    val enableClaudeCode: Boolean = true,
    val enableCodex: Boolean = true,
    val enableGeminiCli: Boolean = true,
)

sealed interface SettingsAction {
    data class ToggleBadgeCount(val enabled: Boolean) : SettingsAction
    data class ToggleSystemNotification(val enabled: Boolean) : SettingsAction
    data class SelectSound(val name: String) : SettingsAction
    data class SelectCustomSoundPath(val path: String) : SettingsAction
    data object LoadSettings : SettingsAction
    data object SaveSettings : SettingsAction
    // ...
}
```

ViewModel이 `StateFlow<SettingsUiState>`를 관리하고, 각 패널은 자신의 방식으로 상태를 구독한다.

- **Compose**: `collectAsState()`로 자동 반응
- **DSL**: `ActionListener`와 `DocumentListener`로 수동 연결

IntelliJ의 `Configurable` 프레임워크가 요구하는 `isModified()`, `apply()`, `reset()`도 ViewModel에 위임한다.

```kotlin
override fun isModified(): Boolean = viewModel.isModified()
override fun apply() { viewModel.onAction(SettingsAction.SaveSettings) }
override fun reset() {
    viewModel.onAction(SettingsAction.ResetSettings)
    rebuildContent()
}
```

### Compose 패널 — Jewel 컴포넌트

Jewel의 `CheckboxRow`, `Dropdown`, `OutlinedButton` 등을 사용해 IDE 테마와 일관된 UI를 만들 수 있었다. `SwingBridgeTheme`으로 감싸면 IDE 테마(Light/Dark)가 자동 적용된다.

```kotlin
object ComposeSettingsPanel {
    fun create(viewModel: SettingsViewModel): JComponent {
        return ComposePanel().apply {
            setContent {
                SwingBridgeTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    SettingsScreen(
                        uiState = uiState,
                        onAction = viewModel::onAction,
                    )
                }
            }
        }
    }
}
```

### DSL 패널의 함정 — textField의 이벤트

DSL 패널에서 커스텀 사운드 경로 입력을 위해 `textField()`를 사용했는데, `addActionListener`는 **Enter 키를 눌렀을 때만** 발동된다. 사용자가 경로를 입력하고 Tab으로 포커스를 옮기면 변경이 반영되지 않는다.

```kotlin
// 잘못된 방법 — Enter에서만 동작
component.addActionListener {
    viewModel.onAction(SettingsAction.SelectCustomSoundPath(component.text))
}

// 올바른 방법 — 문자 입력마다 동작
component.document.addDocumentListener(object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent?) = sync()
    override fun removeUpdate(e: DocumentEvent?) = sync()
    override fun changedUpdate(e: DocumentEvent?) = sync()
    private fun sync() {
        viewModel.onAction(SettingsAction.SelectCustomSoundPath(component.text))
    }
})
```

이후 직접 경로를 타이핑하는 UX 자체가 좋지 않아서, 파일 브라우저 다이얼로그로 변경했다. Compose 쪽은 `FileChooser.chooseFile()` + `OutlinedButton`, DSL 쪽은 `textFieldWithBrowseButton()`을 사용했다.

```kotlin
// Compose
OutlinedButton(onClick = {
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        .withFileFilter { it.extension in SOUND_EXTENSIONS }
    FileChooser.chooseFile(descriptor, null, null) { file ->
        onAction(SettingsAction.SelectCustomSoundPath(file.path))
    }
}) { Text("Browse…") }

// DSL
val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
    .withFileFilter { it.extension in SOUND_EXTENSIONS }
    .withTitle("Select Sound File")
textFieldWithBrowseButton(descriptor)
```

여기서도 작은 함정이 있었다. DSL의 `textFieldWithBrowseButton`에 `browseDialogTitle` 파라미터를 사용하면 빌드 에러가 난다. 이 API가 deprecated 되어 있기 때문이다. 새 방식은 `FileChooserDescriptor.withTitle()`을 사용한다.

---

## 2. 알림 채널 세분화

### 하나의 스위치에서 네 개의 독립 채널로

기존에는 `enableNotifications` 하나의 boolean으로 전체 알림을 켜고 껐다. 1.1.0에서는 네 개의 독립 채널로 분리했다.

| 채널 | 설명 |
|------|------|
| Dock badge count | macOS Dock 아이콘에 미확인 알림 숫자 표시 |
| System notification | macOS 알림 센터에 네이티브 알림 |
| IDE balloon | IDE 내부 타임라인에 BALLOON 알림 |
| Sound alert | 시스템 사운드 또는 커스텀 오디오 파일 재생 |

`MacNotifier`에서 각 채널의 활성화 여부를 개별 확인하도록 변경했다.

```kotlin
fun sendNotification(...) {
    val state = SettingsState.getInstance().state

    if (state.enableIdeBalloon) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification("$toolName — $subtitle", message, notificationType)
            .notify(null)
    }
    if (state.enableSystemNotification) {
        SystemNotifications.getInstance().notify(SYSTEM_NOTIFICATION_NAME, ...)
    }
    if (state.enableBadgeCount) {
        incrementBadge()
    }
}
```

`NotificationDispatcher`에는 모든 채널이 비활성화된 경우를 위한 얼리 리턴을 추가했다. 모든 채널이 꺼져 있으면 메시지 빌드, 로깅, 서비스 호출을 아예 하지 않는다.

```kotlin
val state = settings.state
if (!state.enableBadgeCount && !state.enableSystemNotification &&
    !state.enableIdeBalloon && !state.enableSound
) return
```

### v1.0.x 설정 마이그레이션

기존 사용자가 `enableNotifications = false`로 설정해두었다면, 새 버전에서 모든 채널이 기본값(`true`)으로 초기화되어 갑자기 알림이 쏟아지는 문제가 생길 수 있다.

IntelliJ의 `SimplePersistentStateComponent`에서 `Boolean` 프로퍼티를 `String?`으로 변경하면, 기존 XML에 저장된 `"false"` 값을 문자열로 읽어올 수 있다. 이를 이용해 마이그레이션 로직을 구현했다.

```kotlin
class SettingsData : BaseState() {
    var enableNotifications by string(null) // 1.0.x 마이그레이션용 (기존 Boolean → String?)
    var enableBadgeCount by property(true)
    var enableSystemNotification by property(true)
    var enableIdeBalloon by property(true)
    var enableSound by property(true)
    // ...
}

private fun migrateFromV1(data: SettingsData) {
    val oldValue = data.enableNotifications ?: return  // null이면 이미 마이그레이션 완료
    if (oldValue == "false") {
        data.enableBadgeCount = false
        data.enableSystemNotification = false
        data.enableIdeBalloon = false
    }
    data.enableNotifications = null  // 필드 제거 → 다음 저장 시 XML에서 사라짐
}
```

---

## 3. 가장 어려웠던 버그: Codex 알림이 모든 IDE에 뜬다

### 증상

Android Studio와 IntelliJ IDEA를 동시에 실행한 상태에서, 한쪽 터미널의 Codex가 작업을 완료하면 **양쪽 IDE 모두**에 알림이 뜨고 뱃지 카운트가 올라갔다. Claude Code는 정상적으로 해당 IDE에만 알림이 왔다.

### 배경: PPID chain routing

이 플러그인은 "어떤 IDE 터미널에서 실행된 건지"를 판별하기 위해 PPID chain routing을 사용한다. 원리는 간단하다.

1. 각 IDE가 시작할 때 `~/.terminal-watcher/ports/{IDE_PID}.port` 파일을 생성
2. hook 스크립트가 자신의 프로세스 트리를 따라 올라가며 포트 파일을 찾음
3. 찾은 포트로 해당 IDE에만 HTTP POST 전송

```
hook 스크립트($$ = PID_A)
  → 부모: CLI 도구 (PID_B)
    → 부모: shell (PID_C)
      → 부모: IDE 프로세스 (PID_IDE) ← 여기서 포트 파일 발견!
```

Claude Code와 Gemini는 JSON 설정 파일에 inline command로 이 로직이 들어간다.

```bash
P=$$;
while [ "$P" != "1" ]; do
  P=$(ps -o ppid= -p "$P" 2>/dev/null | xargs);
  [ -f ~/.terminal-watcher/ports/"$P".port ] && {
    PORT=$(cat ~/.terminal-watcher/ports/"$P".port);
    echo "$INPUT" | curl -s -X POST "http://127.0.0.1:$PORT/event?tool=claude" ...;
    exit 0;
  };
done
```

### 원인을 찾는 과정

Claude Code는 되고 Codex는 안 되는 상황이어서, 두 도구의 hook 설정 방식 차이를 집중적으로 살펴봤다.

- **Claude/Gemini**: JSON 설정 파일의 inline command → 매번 코드에서 생성하는 최신 로직 사용
- **Codex**: 외부 bash 스크립트 파일(`~/.codex/notify-twatcher.sh`) → 한번 생성되면 파일로 남음

"설마 파일이 안 바뀐 건가?" 하는 마음으로 실제 디스크의 스크립트를 열어봤다.

```bash
# 실제 디스크의 notify-twatcher.sh (v1.0.0 — broadcast)
#!/bin/bash
ls ~/.terminal-watcher/ports/*.port >/dev/null 2>&1 || exit 0
for f in ~/.terminal-watcher/ports/*.port; do
  PID=$(basename "$f" .port)
  kill -0 "$PID" 2>/dev/null || continue
  PORT=$(cat "$f")
  curl -s -X POST "http://127.0.0.1:$PORT/event?tool=codex" \
    -H 'Content-Type: application/json' -d "$1"
done
```

**모든 포트 파일을 순회하면서 살아있는 IDE 전부에 POST를 보내고 있었다.** PPID chain walking이 전혀 없는 v1.0.0의 broadcast 스크립트였다.

코드가 생성하려는 스크립트에는 PPID chain 로직이 정상적으로 포함되어 있었다. 하지만 stale 체크 로직이 문제였다.

```kotlin
// 기존 stale 체크
if (!scriptFile.exists() || !scriptFile.readText().contains("terminal-watcher")) {
    // 스크립트 교체
}
```

v1.0.0의 broadcast 스크립트에도 `"terminal-watcher"`라는 문자열이 포함되어 있었기 때문에, 이 체크를 통과하고 새 스크립트로 교체되지 않은 것이다.

반면 Claude와 Gemini의 JSON hook은 stale 체크에서 **`"JetBrains-JediTerm"` 포함 여부까지 확인**하고 있었다. 이 차이가 "Claude는 되는데 Codex만 안 되는" 상황을 만들었다.

```kotlin
// Claude/Gemini의 stale 체크 — 더 엄격
val hasCorrectHooks = hooksStr.contains("terminal-watcher") &&
    hooksStr.contains("JetBrains-JediTerm") && !hasStaleConfig
```

### 해결

Codex 스크립트의 stale 체크에 `JetBrains-JediTerm` 포함 여부를 추가해, Claude/Gemini와 동일한 기준으로 맞췄다.

```kotlin
// 수정 후
val scriptContent = if (scriptFile.exists()) scriptFile.readText() else ""
val hasCorrectScript = scriptContent.contains("terminal-watcher") &&
    scriptContent.contains("JetBrains-JediTerm")

if (!hasCorrectScript) {
    // PPID chain 버전으로 교체
}
```

플러그인이 재시작되면 v1.0.0 broadcast 스크립트가 감지되어 PPID chain 버전으로 자동 교체된다.

### 함께 수정한 것

config.toml의 `[tui]` 섹션 추가 로직에도 비슷한 문제가 있었다. `notify-twatcher.sh`가 이미 등록되어 있으면 전체 함수를 조기 리턴해서, `[tui]` 섹션 추가를 놓치고 있었다. notify 등록과 tui 설정을 독립적으로 체크하도록 분리했다.

### Gemini는 영향 없음

Gemini CLI는 Claude Code와 동일한 JSON inline command 방식이다. 실제 `~/.gemini/settings.json`을 확인해보니 PPID chain + JediTerm 체크가 정상적으로 포함되어 있었다.

---

## 4. 결과

### 1.1.0에서 달라진 것

| 영역 | Before (1.0.x) | After (1.1.0) |
|------|----------------|---------------|
| Settings UI | Swing GridBagLayout | Compose(Jewel) + DSL 듀얼 패널 |
| 알림 제어 | 전체 on/off | 채널별 독립 제어 (4개) |
| 사운드 | Glass/Pop 고정 | 시스템 사운드 선택 + 커스텀 파일 |
| 커스텀 사운드 | 없음 | 파일 브라우저 다이얼로그 (aiff, wav, mp3, m4a, caf) |
| Codex 라우팅 | broadcast (전체 IDE) | PPID chain (해당 IDE만) |
| 상태 관리 | Configurable에서 직접 관리 | ViewModel + UiState (MVI) |

### 파일 구조 변경

```
settings/
├── SettingsConfigurable.kt    — Configurable 진입점 (듀얼 패널 분기)
├── SettingsState.kt           — 영속 설정 + v1 마이그레이션
├── SettingsUiState.kt         — UI 상태 + Action sealed interface  [NEW]
├── SettingsViewModel.kt       — StateFlow 기반 상태 관리            [NEW]
├── compose/                                                        [NEW]
│   ├── ComposeSettingsPanel.kt — Jewel ComposePanel 래퍼
│   └── SettingsScreen.kt      — Compose UI
└── dsl/                                                            [NEW]
    └── DslSettingsPanel.kt    — Kotlin UI DSL 폴백
```

---

## 5. 회고

### 잘한 것

- **ViewModel 분리로 UI 로직 공유**. Compose와 DSL이라는 완전히 다른 렌더링 방식이지만, 하나의 ViewModel로 상태를 관리하니 로직 중복이 없었다. IntelliJ의 `Configurable.isModified()`, `apply()`, `reset()`도 ViewModel에 위임하면 깔끔해진다.
- **실제 디스크 파일을 확인한 것**. 코드만 보면 PPID chain이 적용되어 있었지만, 실제 배포된 스크립트는 달랐다. `cat ~/.codex/notify-twatcher.sh` 한 번에 원인이 드러났다. **"코드가 의도한 것"과 "실제로 동작하는 것"은 다를 수 있다.**

### 아쉬운 것

- **stale 체크 기준이 도구마다 달랐다**. Claude/Gemini는 JSON inline이라 `JetBrains-JediTerm`까지 확인했지만, Codex는 외부 스크립트 파일이라 `terminal-watcher` 문자열만 체크했다. 처음부터 통일된 기준을 두었다면 이 버그를 만들지 않았을 것이다.
- **DSL 패널의 반응성 한계**. Compose는 `collectAsState()`로 상태 변경이 즉시 반영되지만, DSL은 리스너를 수동으로 연결해야 한다. 듀얼 패널의 UX 일관성을 완벽하게 맞추기는 어렵다.

### 핵심 교훈

1. **외부 파일을 생성하는 코드는, stale 감지가 "한 단계 더 엄격"해야 한다.** inline 코드는 매번 새로 만들어지니까 문제가 없지만, 파일로 한번 떨어지면 "이미 있으니 안 건드려야지"로 넘어가기 쉽다. 파일 내용까지 검증해야 한다.
2. **디버깅할 때 코드와 실제 파일, 양쪽을 모두 확인하자.** 코드가 "이 파일을 생성한다"고 해서, 디스크에 그 파일이 있다는 보장은 없다. 이전 버전의 파일이 그대로 남아있을 수 있다.

---

*Terminal AI Watcher의 소스 코드는 [GitHub](https://github.com/simjunbo/android-studio-terminal-alert)에서 확인할 수 있다.*
