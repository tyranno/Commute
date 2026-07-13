# Commute 프로젝트 메모리

## 2026-07-14: GitHub 저장소 생성 + README 정리

- 로컬에 git 없던 상태였음 → `git init -b main` 후 첫 커밋, `gh repo create Commute --private --source=. --remote=origin --push`로 https://github.com/tyranno/Commute (private) 생성 및 push 완료.
- 프로젝트 개요(기능/기술스택/구조/빌드법/권한/TODO)를 README.md로 정리해서 함께 커밋. `.gitignore`가 local.properties/build 산출물을 이미 잘 막고 있어서 그대로 커밋에 포함, 별도 조정 불필요했음.
- `.teleclaude/memory.md`도 그대로 git에 포함시킴(비밀정보 없음, 프로젝트 이력으로 유용).

- 2026-07-14: 새 프로젝트 생성. TRide(Capacitor/Svelte 웹뷰)와 달리 순수 코틀린 + Jetpack Compose 네이티브 안드로이드 앱으로 스캐폴드. 패키지명 `com.commute.app`, 프로젝트 경로 `C:\Project\88.MyProject\Commute`.
- 빌드 설정은 TRide `android/` 참조: AGP 8.13.0, Gradle wrapper 8.14.3(gradle-wrapper.jar/properties, gradlew, gradlew.bat 그대로 복사), compileSdk/targetSdk 36, minSdk 24. Kotlin 2.1.0 + `org.jetbrains.kotlin.plugin.compose` 사용(Compose Compiler Gradle 플러그인 방식, kotlinCompilerExtensionVersion 방식 아님).
- JDK/SDK는 TRide의 `build-apk.bat`와 동일한 네이티브 Windows 경로 사용: `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`, `ANDROID_HOME=C:\Program Files (x86)\Android\android-sdk`. (voice-chat 프로젝트와 달리 WSL 경로 아님 — TRide의 `android/local.properties`에 남아있는 WSL 경로(`/home/lab/android-sdk`)는 실사용과 무관한 것으로 보여 Commute는 처음부터 네이티브 Windows 경로로 `local.properties` 작성함.)
- 빌드 중 발견한 이슈: 로컬 Android SDK에 `build-tools;35.0.0`이 없고 `36.0.0`만 설치돼 있었음 + 라이선스 미동의 상태. `app/build.gradle.kts`에 `buildToolsVersion = "36.0.0"`을 명시하고, `C:\Program Files (x86)\Android\android-sdk\licenses\android-sdk-license` / `android-sdk-preview-license` 파일에 표준 SHA1 해시를 직접 기록해서 해결(sdkmanager --licenses 대화형 실행 없이). 이 SDK 라이선스 파일은 시스템 전역이라 다른 프로젝트(TRide, voice-chat) 빌드에도 영향 없이 재사용 가능.
- `.\gradlew.bat assembleDebug --no-daemon` 빌드 성공 확인(2026-07-14). 출력: `app\build\outputs\apk\debug\app-debug.apk` (~9.4MB). `build-apk.bat`로 빌드+ADB 설치 가능(디바이스 연결 시).
- 현재 화면: MainActivity + Material3 Compose 기본 "Hello, Commute!" 스캐폴드만 있음. 실제 출퇴근 앱 기능(화면, 데이터 모델 등)은 아직 없음 — 다음 세션에서 요구사항 논의 필요.

## 2026-07-14: 회사 와이파이 감지 → 출퇴근 자동 기록 기능 1차 구현

- **동작 방식**: `ConnectivityManager.registerNetworkCallback`(TRANSPORT_WIFI)로 와이파이 연결/해제를 감지하는 포그라운드 서비스(`wifi/WifiMonitorService.kt`). 연결된 SSID가 사용자가 등록한 "회사 와이파이" SSID와 일치하면 ARRIVE 이벤트, 해제/불일치로 전환되면 LEAVE 이벤트를 Room DB에 기록 + 알림 표시. 상태 전이는 DataStore에 저장한 `isAtWork` 플래그로 판단(중복 기록 방지).
- **데이터 계층**: `data/CommuteEvent.kt`(Room 엔티티, ARRIVE/LEAVE enum) + `CommuteDao` + `CommuteDatabase`(싱글턴) + `SettingsRepository`(DataStore Preferences: companySsid, monitoringEnabled, isAtWork).
- **UI**: `MainActivity.kt`의 `CommuteScreen` — 현재 연결 와이파이 표시, "현재 와이파이를 회사 와이파이로 등록" 버튼, 자동 감지 on/off 스위치, 오늘 상태, 최근 기록 리스트. `CommuteViewModel`(AndroidViewModel)이 Flow들을 StateFlow로 노출.
- **권한/컴포넌트**: ACCESS_FINE_LOCATION(SSID 판독에 필수, Android 8+), POST_NOTIFICATIONS, FOREGROUND_SERVICE(_LOCATION), RECEIVE_BOOT_COMPLETED. `wifi/BootReceiver.kt`가 재부팅 후 monitoringEnabled=true면 서비스 자동 재시작.
- **중요 빌드 트러블슈팅**: Room 어노테이션 프로세싱을 처음엔 kapt로 붙였으나 **Kotlin 2.1.0 + kapt 조합에서 반드시 실패**함 — kapt가 stub 생성 시 language version을 1.9로 낮춰도 `@Metadata` 버전은 2.1.0으로 찍히는데, Room이 내장한(jarjar) `kotlinx-metadata-jvm`은 버전 2.0.0까지만 지원해서 `IllegalArgumentException: Provided Metadata instance has version 2.1.0, while maximum supported version is 2.0.0`로 죽음. **해결: kapt를 완전히 버리고 KSP로 전환**(`id("com.google.devtools.ksp") version "2.1.0-1.0.29"`, `ksp("androidx.room:room-compiler:2.6.1")`). 이 프로젝트(및 Kotlin 2.1.0을 쓰는 다른 프로젝트)에서는 앞으로 어노테이션 프로세서가 필요하면 kapt 대신 처음부터 KSP를 쓸 것.
- `.\gradlew.bat assembleDebug --no-daemon` 빌드 성공 확인. 실기기에서의 실제 와이파이 감지 동작은 아직 미검증(빌드 환경에 연결된 디바이스 없음) — 다음 세션에서 실기기 설치 후 회사 와이파이로 실제 테스트 필요.

## 2026-07-14: 이벤트 기반 → 1분 주기 폴링 방식으로 전환

- 사용자 요청: "출근은 첫감지 시간, 퇴근은 마지막 감지시간, 주기는 1분마다". 기존 `ConnectivityManager.NetworkCallback` 푸시 방식(연결/해제 시점에만 콜백)을 버리고, 포그라운드 서비스 안에서 `while(isActive) { checkWifiState(); delay(60_000) }` 코루틴 루프로 교체(`WifiMonitorService.kt`).
- **판정 로직**: 매 틱마다 `currentWifiSsid()`로 현재 SSID를 읽어 회사 SSID와 비교. `!wasAtWork && connected`면 그 순간(now)을 ARRIVE로 기록(=첫 감지 시각). connected인 동안은 매 틱마다 `lastSeenAt`(DataStore Long)을 계속 갱신. `wasAtWork && !connected`가 되면 LEAVE 이벤트의 timestamp는 `now`가 아니라 저장해둔 `lastSeenAt`(=마지막으로 연결이 확인됐던 시각)을 사용.
- `SettingsRepository`에 `lastSeenAt: Flow<Long?>` + `setLastSeenAt()` 추가(DataStore `longPreferencesKey`). 이게 있어야 "마지막 감지시간"을 서비스 재시작 후에도 안전하게 재구성 가능.
- `WifiUtils.kt`의 `extractSsid(NetworkCapabilities)`는 NetworkCallback 전용이라 폴링 전환 후 미사용 → 삭제(죽은 코드 방지). `WifiMonitorService`에서 `ConnectivityManager` 관련 import/필드도 전부 제거.
- 권한/매니페스트는 변경 없음(여전히 `currentWifiSsid()`가 ACCESS_FINE_LOCATION 필요, foregroundServiceType="location" 유지).
- `JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8" ./gradlew assembleDebug` 빌드 성공 확인. 커밋 e9725ed, GitHub push 완료. 실기기 라이브 검증은 아직 안 함(다음 세션 TODO).

## 2026-07-14: 랄프루프 5회 구현 점검 (감사→수정→빌드검증→커밋 반복)

teleclaude/aglink-chat에서 쓰던 "랄프루프 N회 감사" 패턴을 Commute에도 적용. 매 라운드마다 실제 결함을 찾아 수정하고 `gradlew assembleDebug`로 빌드 검증 후 커밋, 마지막에 clean build로 전체 회귀 확인 후 push.

- **Round 1 (db80008)**: 세션 시작 시점에 이미 uncommitted 상태로 존재하던 자정 경계 수정(`isSameDay` 기반)을 검토·빌드검증 후 그대로 커밋. `wasAtWork`가 전날부터 이어져 있으면(밤새 연결 유지 또는 감지 중단으로 LEAVE를 못 본 경우) `lastSeenAt` 시각으로 자동 마감 LEAVE를 기록하고, 오늘 기준으로 새로 판정.
- **Round 2 (98f6254, 가장 중요한 결함)**: **크래시 버그.** targetSdk 36(Android 14+)에서는 `foregroundServiceType="location"`인 서비스가 `startForeground()`를 호출하는 시점에 이미 `ACCESS_FINE_LOCATION`을 보유하고 있어야 하며, 없으면 `SecurityException`. 그런데 `MainActivity`의 스위치 `onCheckedChange`가 `requestPermissions()`(비동기)와 `viewModel.setMonitoringEnabled(true)`(동기)를 같은 클릭에서 동시에 호출해서, 권한 다이얼로그 응답 전에 서비스가 먼저 시작을 시도 → 첫 사용자가 스위치를 켜는 순간 크래시할 수 있는 구조였음. 같은 근본 원인이 `BootReceiver`(재부팅 후 권한이 취소된 상태로 서비스 재시작)에도 있었음.
  - 수정: `WifiMonitorService.onStartCommand`에서 자체적으로 권한을 체크해서 없으면 `stopSelf()`(양쪽 호출부를 한 곳에서 방어), `MainActivity`는 권한이 실제로 승인된 뒤에만 `setMonitoringEnabled(true)` 호출하도록 콜백 기반으로 변경.
- **Round 3 (beb3e06)**: UX 결함. 위치 **권한**은 허용해도 기기의 위치 **서비스(GPS 토글)** 가 꺼져 있으면 `WifiManager`가 SSID를 계속 "알 수 없음"으로 반환하는 안드로이드 플랫폼 특성이 있어, 이 상태면 앱이 고장난 것처럼 보임. `LocationManagerCompat.isLocationEnabled()`를 SSID 폴링과 같이 3초마다 확인해서, 권한은 있는데 위치서비스가 꺼져있으면 안내 카드를 띄우도록 추가.
- **Round 4**: 나머지 리소스/설정 파일(strings.xml, themes.xml, proguard-rules.pro, settings.gradle.kts, .gitignore) 재검토 — 추가 결함 없음으로 확정.
- **Round 5**: `gradlew clean assembleDebug`로 전체 회귀 빌드 확인(40/40 태스크 실행, BUILD SUCCESSFUL) 후 `git push origin main` 완료(GitHub `tyranno/Commute`, HEAD `beb3e06`).
- **How to apply**: Round 2 크래시는 실기기 테스트를 아직 안 한 상태에서는 놓치기 쉬운 종류(`targetSdk`가 34+인 `location`/`camera`/`microphone` 타입 foreground service는 항상 "권한 승인 확정 후에만 시작" 패턴을 지킬 것 — 이 프로젝트뿐 아니라 다른 안드로이드 프로젝트에도 적용 가능한 일반 원칙).
- **미해결/다음 세션**: 실기기 설치 후 실제 회사 와이파이로 ARRIVE/LEAVE/자정마감/권한거부 시나리오 라이브 검증 여전히 안 됨. 저장소 루트에 `.bkit/`(정체불명 에이전트 툴 런타임 상태, git 추적 안 됨)와 `doc/`(관련 없어 보이는 PDF 1개) 미추적 디렉터리가 있는데 이번 작업 범위 밖이라 손대지 않음 — 다음에 정리 필요 여부 사용자에게 확인.
