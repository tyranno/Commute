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
