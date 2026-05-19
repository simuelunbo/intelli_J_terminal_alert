## 하네스: Windows 지원 (Terminal AI Watcher)

**목표:** macOS 전용으로 작성된 알림·배지·사운드 로직을 OS 추상화로 전환하고, Windows 10/11에서 동작하도록 UI와 설정까지 포함해 안전하게 확장한다.

**트리거:** Windows 지원·OS 추상화·Notifier 인터페이스·taskbar flash·플러그인 릴리즈 준비 작업 시 `windows-support-orchestrator` 스킬을 사용한다. 단순 Mac 로직 질문이나 한 줄 수정은 직접 응답 가능.

**변경 이력:**

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-04-19 | 초기 구성 — 에이전트 6 + 스킬 6 + 오케스트레이터 1 | 전체 | Windows 지원 준비 체계 수립 |

## 릴리즈·Change-notes 정책

**`core/src/main/resources/META-INF/plugin.xml` 의 `<change-notes>` 작성 시:**

- **사용자 가치 중심**으로 간결히 작성한다 ("어떤 것을 개선했다" 수준).
- 환경변수 이름, API·메서드 시그너처, HTTP 헤더·상태코드, 내부 클래스·함수명 같은 **기술 세부 사항은 적지 않는다**. 그런 디테일은 commit message·코드에 두면 충분하다.
- 항목 분류는 `New` / `Improve` / `Fix` 만 사용한다.

**버전 업그레이드 시:**

- 새 버전을 release 할 때 `<change-notes>` 의 **이전 버전 항목은 모두 제거**한다. 현재 release한 버전 블록 하나만 남긴다.
- 과거 변경 이력은 git tag·commit history·GitHub releases 로 추적한다.
