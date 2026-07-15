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

## 2026-07-14: 재점검 + `doc/가산 연구소 운영 방안_0923 (1).pdf` 내용 확인 — 이 앱이 지원해야 할 실제 회사 정책 파악

- **재점검**: HEAD `27b168c` 기준 클린 빌드(`gradlew assembleDebug --no-daemon`) 재확인, 성공. 핵심 로직(1분 폴링 ARRIVE/LEAVE, 자정 경계 자동 마감, 권한 없을 때 `stopSelf()`, 위치서비스 꺼짐 안내) 전부 재검증 완료, 이상 없음.
- **새로 발견한 경미한 이슈(미수정, 다음 세션 후보)**: `MainActivity`의 `hasLocationPermission`이 `remember{}`로 최초 1회만 계산돼서, 앱이 백그라운드에 있는 동안 시스템 설정에서 위치 권한을 취소해도 액티비티가 재생성되지 않는 한 UI가 갱신 안 됨(스위치는 켜진 것처럼 보이지만 서비스는 이미 조용히 꺼진 상태) — 크래시는 아니고 UX 혼란 수준. `app/src/test`에는 보일러플레이트만 있고 ARRIVE/LEAVE·자정 경계 로직에 대한 단위 테스트 없음.
- **`doc/가산 연구소 운영 방안_0923 (1).pdf` 내용 (2022.09.23, 가산 연구소)**: 이 앱이 궁극적으로 지원해야 할 실제 회사 근태 정책. 이번 세션에서 처음으로 문서를 읽고 파악함.
  - **식대 지원**: 주변 식당 월말 정산 지원, 기존 월급 포함 식대(10만원)는 제외 — 출퇴근 앱과 무관한 별도 복지 항목.
  - **자율출퇴근제 상세 규정** (앱이 지원해야 할 부분):
    - 주 40시간 기본 근무, 근무 인정 시간 07:00~22:00, 출근 인정 시간 07:00~13:00
    - 일 최소 근무시간 4시간30분(업무4h+휴게30분)
    - 집중근무시간 10:00~12:00, 14:00~16:00
    - 휴게시간 차등 공제: 4시간마다 30분 / 8시간 근무 시 점심 1시간 / 12시간 이상 시 1시간30분 (예: 08:00~21:30 근무 시 실근무 12시간)
    - 10분 이상 이석은 근무시간 제외(업무상 이석은 소명 후 인정), 외근/출장은 별도 결재(연구소장 전결)
- **현재 구현과의 격차** (다음 개발 단계로 필요, 아직 미구현):
  1. 실 근무시간 계산 로직 없음 — ARRIVE/LEAVE 타임스탬프만 저장, 구간별 휴게시간 차감 계산이 없음.
  2. 짧은 이석(10분 미만) 재연결을 하나의 근무 세션으로 병합하는 로직 없음 — 현재는 와이파이가 잠깐만 끊겨도 즉시 LEAVE 기록 후 재연결 시 새 ARRIVE로 기록돼 세션이 잘게 쪼개짐. 정책과 어긋날 수 있음.
  3. 근무 인정 시간(07:00~22:00) 상한 캡핑 없음, 출근 인정 시간(07:00~13:00) 지각/결근 판정 없음.
  4. 주 40시간 등 기간별 집계·통계 화면 없음(README TODO와 동일 항목).
  5. 집중근무시간 준수 확인, 외근/출장 결재 소명은 와이파이 감지만으로 판단 불가 — 별도 수동 입력/승인 UI 필요.
  - **How to apply**: 앞으로 "근무시간 계산", "이석 처리", "지각/결근 판정" 등을 구현할 때는 이 문서의 수치(07:00~22:00, 07:00~13:00, 4h30m, 10분 이석 기준, 4h/8h/12h 휴게 구간)를 정확한 근거로 사용할 것 — 자체적으로 규칙을 새로 정의하지 말 것.

## 2026-07-14: 이석(자리비움) vs 출근/퇴근 구분 로직 + 규칙 설정 화면 1차 구현

- **사용자 지시**: "우선 와이파이 등록/인식, 이석·출근·퇴근 구분부터 정하고, 정해진 룰을 설정으로 값 변경 가능하게. 기본값은 문서(가산 연구소 운영 방안) 기준으로." 격차 목록의 2번(이석 병합)을 우선 처리하고, 나머지(근무/출근 인정 시간 캡핑, 휴게시간 계산, 주 40h 통계, 집중근무시간, 외근/출장 결재)는 아직 손대지 않음 — 각 기능을 구현할 때 그 기능에 필요한 규칙만 그때 설정으로 추가하기로 함(안 쓰는 설정 항목을 미리 만들어두지 않음).
- **상태머신 설계**: `WifiMonitorService.checkWifiState()`를 "끊기면 즉시 LEAVE"에서 "끊기면 유예시간(`awaySinceAt`) 동안 지켜보다가, 유예시간 안에 재연결되면 이석(AWAY)으로 기록하고 세션 유지 / 유예시간을 넘기면 그제서야 진짜 LEAVE(타임스탬프는 여전히 `lastSeenAt` = 마지막 연결 확인 시각)"로 변경. `SettingsRepository`에 `awaySinceAt: Flow<Long?>`(유예 관찰 중일 때만 non-null)과 `absenceThresholdMinutes: Flow<Int>`(기본값 10, DataStore에 저장돼 설정 화면에서 변경 가능) 추가.
  - **왜 새 필드가 필요했는가**: 처음엔 기존 `lastSeenAt`만으로 폴링 간격 대비 시간차를 계산해서 끊김 여부를 추론하려 했으나, 코루틴 `delay()` 지연/Doze 모드 등으로 폴링 주기가 흔들리면 오탐 가능성이 있어 견고하지 않다고 판단. 대신 "끊김을 처음 인지한 시각"을 명시적 상태(`awaySinceAt`)로 저장하는 방식으로 감. 자정 경계 자동 마감 로직에서도 `awaySinceAt`을 함께 clear하도록 반영.
- **데이터 모델**: `CommuteEventType`에 `AWAY` 추가, `CommuteEvent`에 `endTimestamp: Long? = null` 추가(AWAY만 시작~복귀 구간을 한 행에 표현). Room DB 버전 1→2, `fallbackToDestructiveMigration()` 적용(앱이 아직 실기기 라이브 검증 전 단계라 마이그레이션 대신 파괴적 재생성으로 처리 — 실사용자 데이터가 생기기 전에 내린 결정이므로, 나중에 실사용 데이터가 쌓이기 시작하면 이 방식을 재검토할 것).
- **UI**: `MainActivity`에 "근무 규칙 설정" 카드 추가 — 이석 인정 기준(분) 입력 필드 + 저장 버튼, `CommuteViewModel`이 `absenceThresholdMinutes` StateFlow와 `setAbsenceThresholdMinutes()` 노출. `최근 기록` 리스트의 `formatEvent()`가 AWAY 이벤트를 "시작~복귀 (N분) 이석"으로 별도 포맷팅하도록 확장.
- **빌드 검증**: `gradlew assembleDebug --no-daemon` 성공 확인. (트러블슈팅: Room 2.6.1의 `fallbackToDestructiveMigration()`은 인자 없는 버전만 존재 — `fallbackToDestructiveMigration(true)`처럼 Boolean 인자를 넘기면 컴파일 에러. 최신 Room 문서 예제와 실제 프로젝트에 고정된 라이브러리 버전이 다를 수 있으니 항상 실제 의존성 버전 기준으로 API를 확인할 것.)
- **README 갱신**: 주요 기능에 이석 구분 추가, 이전부터 남아있던 오기(“ConnectivityManager로 실시간 감지”는 이전 세션에 이미 폴링 방식으로 바뀌었는데 README에 반영이 안 돼 있었음 — 이번에 같이 고침)도 함께 수정. "근무 규칙" 섹션 신설해 문서 기준값과 구현 여부를 표로 정리.
- **미해결/다음 세션**: 근무 인정 시간(07:00~22:00) 캡핑, 출근 인정 시간(07:00~13:00) 지각 판정, 휴게시간 차등 공제 기반 실 근무시간 계산, 주 40시간 통계, 집중근무시간·외근출장 결재는 여전히 미구현. 이전 세션에서 발견된 "권한 취소 후 UI 미갱신" 이슈도 아직 미수정. 실기기 라이브 검증 계속 보류 중.

## 2026-07-14: 점심시간 설정 기능 추가

- **사용자 지시**: "점심시간도 별도로 설정할 수 있는 기능이 있어야 한다." 이석 인정 기준(기본 10분)만으로는 1시간짜리 점심을 무조건 "퇴근→재출근"으로 쪼개버리는 문제가 있어서, 점심시간대는 이석 기준과 무관하게 세션을 유지하도록 별도 규칙을 추가.
- **설계**: `SettingsRepository`에 `lunchStartMinute`/`lunchEndMinute: Flow<Int>`(자정 기준 분 단위, 기본값 12:00/13:00 = 720/780) 추가. 시각 문자열("HH:mm") ↔ 분 변환은 재사용 가능하게 `data/TimeOfDay.kt`에 `parseHHmmToMinuteOfDay`/`formatMinuteOfDayToHHmm`로 분리.
- **`WifiMonitorService` 로직 확장**: 이석 유예 판정 분기(`else if (wasAtWork)`)에서, `now`가 설정된 점심시간 구간 안이면 이석 기준 초과 여부를 아예 평가하지 않고 계속 대기(`isWithinLunchWindow`). 점심시간을 지난 뒤에는, 끊김이 점심 시작 전/중에 시작됐다면 "점심 종료 시각"부터 다시 이석 인정 기준만큼의 유예를 추가로 줌(`lunchEndTimestampIfPassed` — 점심 끝나고 바로 안 돌아와도 평소처럼 N분은 봐줌). 점심시간이 끝나고도 그 유예까지 넘기면 그제서야 LEAVE로 마감 — 이때도 LEAVE 타임스탬프는 여전히 마지막 실제 연결 시각(`lastSeenAt`)을 사용해 실제 퇴근 시각이 왜곡되지 않게 함.
- **왜 "점심 시작~복귀"를 하나의 AWAY로 남기는가**: 점심시간 동안의 이탈도 기존 AWAY 병합 로직을 그대로 타므로, 복귀 시 자동으로 "이석 12:xx~13:xx (N분)" 기록이 남음. 별도의 "점심" 이벤트 타입을 새로 만들지 않고 기존 AWAY 개념을 재사용 — 나중에 실 근무시간 계산 기능을 만들 때 이 AWAY 구간을 휴게시간 공제 대상으로 참조하면 됨.
- **UI**: "근무 규칙 설정" 카드에 점심 시작/종료 시각(HH:mm 텍스트 입력 2개) + 저장 버튼 추가. `CommuteViewModel`이 `lunchStartMinute`/`lunchEndMinute` StateFlow와 `setLunchWindow(start, end)` 노출.
- **비활성화 방법**: 시작==종료(또는 시작>종료)로 저장하면 `lunchWindowMinutes()`가 null을 반환해 점심 규칙이 자동으로 꺼짐 — 별도의 on/off 토글 없이 값만으로 켜고 끌 수 있게 설계.
- **빌드 검증**: `gradlew assembleDebug --no-daemon` 성공.
- **미해결**: 여전히 근무/출근 인정 시간 캡핑, 휴게시간 계산, 주 40시간 통계, 실기기 라이브 검증 등은 미착수.

## 2026-07-14: 근무 규칙 설정을 별도 화면(SettingsScreen)으로 분리 + Navigation Compose 도입

- **사용자 지시**: "이 시간을 변경 가능하도록 별도 설정화면이 있어야 한다. 이석감지시간도 나중에는 30/60분 등으로 바뀔 수 있으니, 이런 제한 사항을 변경할 수 있는 기능이 있어야 한다." — 이석 인정 기준·점심시간이 지금까지는 메인 화면(`CommuteScreen`) 안에 카드로 인라인돼 있었는데, 앞으로 근무/출근 인정 시간·휴게시간 등 규칙이 계속 늘어날 걸 감안해 정식으로 별도 화면으로 분리해달라는 요청.
- **내비게이션 도입**: 지금까지 앱에 화면 전환 인프라가 전혀 없어서(`MainActivity`가 단일 Composable만 `setContent`) `androidx.navigation:navigation-compose:2.8.5`를 신규 추가(compose-bom 2024.12.01과 호환되는 stable 버전). `MainActivity`는 `CommuteApp()` 하나만 `setContent`하고, `CommuteApp`이 `NavHost`로 "home"(`CommuteScreen`)/"settings"(`SettingsScreen`) 두 라우트를 관리. `CommuteViewModel`은 `CommuteApp` 레벨에서 한 번만 `viewModel()`로 얻어서 두 화면에 그대로 넘겨줌(화면별로 따로 얻으면 Navigation Compose가 백스택 엔트리마다 별도 인스턴스를 만들 수 있어서, 명시적으로 공유).
- **화면 분리**: `SettingsScreen.kt` 신규 파일 생성 — 이석 인정 기준 에디터 + 점심시간 에디터를 `MainActivity.kt`에서 그대로 이동(로직 변경 없음, 배치만 변경). `CommuteScreen` 상단에 "근무 규칙 설정" 버튼을 추가해 진입, `SettingsScreen` 상단에 "뒤로" 버튼으로 복귀. 별도 아이콘 라이브러리 의존성을 새로 추가하지 않으려고(기존 앱이 아이콘을 전혀 안 쓰고 텍스트 버튼만 쓰는 스타일이라 통일) `Icons.Default.Settings` 대신 텍스트 버튼("근무 규칙 설정" / "뒤로")으로 처리.
- **왜 지금 이 구조로 바꿨는가**: 문서(가산 연구소 운영 방안)에 남은 규칙(근무 인정 시간, 출근 인정 시간, 휴게시간 차등공제)이 계속 추가될 예정이라, 인라인 카드 방식으로는 메인 화면이 계속 길어질 것 — 이번에 미리 화면을 분리해두면 다음 규칙들도 `SettingsScreen`에 카드만 추가하면 되는 구조.
- **빌드 검증**: `gradlew assembleDebug --no-daemon` 성공.
- **미해결**: 여전히 근무/출근 인정 시간 캡핑, 휴게시간 계산, 주 40시간 통계, 실기기 라이브 검증 등은 미착수. `SettingsScreen`에는 현재 이석 인정 기준·점심시간 두 카드만 있음 — 다음 규칙 구현 시 여기에 카드만 추가하면 됨.

## 2026-07-14: 커밋 + 실기기(adb) 설치 + 런처 아이콘 교체

- **사용자 지시**: "커밋하고 adb로 내 폰이 연결되어 있으니 설치해봐. apk 아이콘도 이 앱에 맞춰서 생성해서 등록해줘 — 다른 앱이랑 구분되게."
- **아이콘 교체**: `ic_launcher_background.xml`/`ic_launcher_foreground.xml`(adaptive icon, 108x108 벡터)을 기본 템플릿(파란 배경 + 단순 도넛 모양)에서 커스텀 디자인으로 교체. 배경색은 앱 테마의 Material3 primary 색(`Purple40 #6650A4`, `ui/theme/Theme.kt`)과 통일. 전경은 흰색 위치핀(원+삼각형 꼬리) 안에 배경색으로 그린 시계 바늘(시침/분침 + 중심점)을 얹은 모양 — "회사 위치(와이파이 반경) 도착/이탈을 시간 단위로 기록"한다는 앱 컨셉을 형상화. 이미지 렌더링 툴이 환경에 없어서(Windows `convert`는 ImageMagick이 아니라 디스크 포맷 변환 도구였음 — 착각하기 쉬우니 주의) PNG가 아니라 순수 벡터 path로만 직접 작성.
  - **한계**: `minSdk 24`인데 프로젝트에 API 26 미만용 legacy PNG 런처 아이콘(`mipmap-hdpi` 등)이 원래부터 없음(이번에 만든 게 아니라 기존 스캐폴드 때부터 없었음) — adaptive icon(`mipmap-anydpi-v26`)만 있어서 Android 8.0 미만 기기에서는 아이콘이 정상적으로 안 뜰 수 있음. 이미지 래스터라이즈 도구가 없어 이번엔 손대지 않음 — 실사용 기기가 대부분 API26+ 라 당장 급하지 않다고 판단했지만, 구형 기기 지원이 필요해지면 Android Studio Image Asset Studio로 PNG를 따로 생성해야 함.
- **커밋**: `d9af217` — 이번 세션 전체 변경사항(이석/출근/퇴근 구분, 점심시간, 설정 화면 분리, 아이콘)을 하나의 커밋으로 묶음. `.bkit/`, `doc/`는 여전히 범위 밖이라 제외.
- **실기기 설치**: `adb devices`로 확인된 기기 `SM_S938N`(시리얼 `R3CY10AF61Z`)에 `adb install -r`로 설치 성공, `pm list packages`로 `com.commute.app` 설치 확인.
- **미해결**: 아직 GitHub push는 안 함(사용자가 커밋만 요청, push는 별도 확인 필요). 실기기 라이브 시나리오(회사 와이파이로 실제 ARRIVE/이석/점심/퇴근 테스트)는 앱이 폰에 깔린 지금이 처음 시도해볼 기회 — 다음 세션에서 진행 여부 확인.

## 2026-07-14: UI 디자인 개선(아이콘·색상·카드 레이아웃 정리)

- **사용자 지시**: "UI 화면이 넘 이쁘지 않아. 내가 그냥 쓰는 것이라도 모양은 좀 이쁘게 갖추자." — 이전까지는 기본 Material3 `Card` + 텍스트만 나열한 수수한 레이아웃이었음.
- **의존성 추가**: `androidx.compose.material:material-icons-extended`(compose-bom 관리 버전) 추가 — 이전 세션에서는 "앱이 텍스트 버튼만 쓰는 스타일이라 아이콘 의존성을 새로 추가하지 않겠다"고 결정했었는데, 이번엔 "예쁘게"가 명시적 목표라 아이콘을 적극 활용하는 쪽으로 방향을 바꿈.
- **`MainActivity.kt`(홈 화면) 재구성**:
  - `Scaffold` + `TopAppBar` 도입(제목 "Commute" + 우측 설정 아이콘 버튼) — 기존의 밋밋한 텍스트 헤더+버튼 방식 대체.
  - **상태 히어로 카드** 신설: 출근 중이면 `primaryContainer`(테마 강조색) 배경 + `Work` 아이콘, 아니면 `surfaceVariant` 배경 + `ExitToApp` 아이콘으로 오늘 상태를 크고 명확하게 표시. 기존엔 다른 카드 안에 작은 텍스트 한 줄로만 표시돼 있었음.
  - 권한/위치서비스 경고를 `NoticeCard` 공통 컴포넌트로 통일(warning 아이콘 + `tertiaryContainer` 톤).
  - 와이파이 카드: `Wifi`/`WifiOff` 아이콘으로 연결 여부를 시각적으로 구분, `HorizontalDivider`로 "현재 연결"과 "등록된 회사 와이파이" 구획 분리(구버전 `Divider` 대신 신버전 `HorizontalDivider` 사용).
  - 최근 기록 리스트: 텍스트 한 줄이 아니라 이벤트 타입별 색상 아이콘(출근=녹색 `Work`, 퇴근=빨강 `ExitToApp`, 이석=주황 `DirectionsWalk`) + 라벨/SSID/시간을 구분된 영역으로 보여주는 카드형 리스트로 변경.
- **`SettingsScreen.kt` 재구성**: `Scaffold` + `TopAppBar`(뒤로가기 아이콘) 도입, 각 규칙 카드에 아이콘 추가(이석 인정 기준=`DirectionsWalk`, 점심시간=`Restaurant`)로 카드별 성격을 한눈에 구분되게 함.
- **아이콘 API 정리**: 처음 구현 시 `Icons.Filled.ExitToApp`/`DirectionsWalk`/`ArrowBack`을 썼더니 컴파일은 되지만 "AutoMirrored 버전을 쓰라"는 deprecation 경고가 남아서, RTL 대응이 되는 `Icons.AutoMirrored.Filled.*`로 전부 교체해 경고 없는 클린 빌드로 마무리.
- **빌드 검증**: `gradlew assembleDebug --no-daemon` 성공(경고 0개). 중간에 백그라운드로 돌리던 첫 빌드가 세션 종료로 끊겨서(완료 기록 없음) 재실행해서 최종 확인함 — 백그라운드 빌드가 끊기면 부분 로그만 믿지 말고 처음부터 다시 돌려서 확인할 것.
- **미해결**: 이번 라운드에서 폰이 adb에서 안 보여서(케이블 재연결 필요해 보임) 새 UI를 실기기에 설치하지 못함 — 다음에 폰 연결 확인 후 설치 필요. 아이콘은 여전히 `mipmap-anydpi-v26`(API26+)만 있고 구형 기기용 legacy PNG는 없음(기존에 기록된 한계, 미해결 유지).

## 2026-07-14: 실기기 설치 + 실사용 데이터 확인 + 점심시간 설정 레이아웃 버그 수정

- **폰 재연결 후 설치**: adb에 폰이 안 잡혀서 사용자에게 USB 재연결 요청 → 재연결 확인 후 `adb devices`로 인식 확인, 빌드된 APK `adb install -r`로 설치 성공.
- **`adb shell am start` vs `monkey`**: `am start -n com.commute.app/.MainActivity`로 실행했더니 `dumpsys activity`상 포커스가 여전히 launcher에 남아있고(크래시 로그도 없음) 실제로는 화면에 앱이 안 뜸. `adb shell monkey -p com.commute.app -c android.intent.category.LAUNCHER 1`로 다시 실행하니 정상적으로 포그라운드로 올라옴 — 이 기기(삼성 OneUI)에서는 `am start`가 조용히 씹히는 경우가 있는 듯. **How to apply**: 이 프로젝트(또는 이 폰)에서 adb로 앱을 띄워 스크린샷 검증할 때는 `am start` 대신 `monkey -p <pkg> -c android.intent.category.LAUNCHER 1`을 기본으로 쓸 것.
- **Git Bash 경로 변환 함정**: `adb shell screencap -p /sdcard/xxx.png`처럼 원격(안드로이드) 경로를 인자로 넘길 때, Git Bash(MSYS2)가 `/sdcard/...`를 로컬 Windows 경로로 자동 변환해버려서 `screencap`이 이상한 인자를 받고 usage 메시지만 출력하며 실패함. `MSYS_NO_PATHCONV=1` 환경변수를 command 앞에 붙여서 해결. **How to apply**: 이 환경에서 `adb shell`로 안드로이드 절대경로(`/sdcard/...`, `/data/...` 등)를 인자로 넘길 때는 항상 `MSYS_NO_PATHCONV=1`을 붙일 것.
- **실사용 데이터로 기능 검증**: 스크린샷으로 실제 앱 화면 확인 결과, 사용자가 이미 실제 회사 와이파이(`iptime5G`)를 등록해서 쓰고 있었고 최근 기록에 실제 출근(07/15 07:55)·퇴근(07/14 16:35)·출근(07/14 15:49) 이벤트가 정상적으로 쌓여 있음을 확인 — 이석/출근/퇴근 구분 로직이 실기기에서 실제로 동작 중임을 처음으로 확인함(이전까지는 "실기기 라이브 검증 미완료"로 기록돼 있었음, 이제 최소한 ARRIVE/LEAVE 기본 동작은 검증됨).
- **발견 및 수정한 레이아웃 버그**: 설정 화면의 "점심시간" 카드에서 시작/종료 `OutlinedTextField` 2개 + "저장" `Button`을 한 `Row`에 나란히 넣었더니, 화면 폭에 다 안 들어가서 종료 필드와 저장 버튼이 찌그러져 이상한 빈 세로 박스로 렌더링되는 버그를 스크린샷으로 발견함. `SettingsScreen.kt`의 `LunchWindowEditor`를 시작/종료 필드는 `Row`에 `Modifier.weight(1f)`로 균등 분할, 저장 버튼은 그 아래 `Modifier.fillMaxWidth()`로 별도 `Column`에 배치하도록 수정 — 재빌드/재설치 후 스크린샷으로 정상 렌더링 확인.
- **교훈**: Compose 프리뷰/빌드 성공만으로는 실제 기기 화면에서 여러 요소가 들어간 `Row`가 좁은 화면에서 깨지는지 알 수 없음 — 필드가 2개 이상인 Row는 항상 `weight()`로 폭을 명시하거나 실기기/에뮬레이터 스크린샷으로 확인할 것.

## 2026-07-14: 이석 인정 기준 카드도 같은 레이아웃 버그였음 — "저장" 글자 안 보임

- **사용자 발견**: "설정에서 이석 인정 기준에 시간 변경하고 저장하는 버튼이 따로 있나?"라고 물어서 "있다"고 코드·이전 스크린샷 기준으로 답했는데, 사용자가 실제 화면에서는 "저장이라는 글자가 안 보인다"고 재확인 요청함.
- **원인**: 직전 라운드에서 "점심시간" 카드의 `Row` 폭 초과 버그만 고치고, 정확히 같은 구조(`OutlinedTextField` + `Button`을 `weight` 없이 `Row`에 나열)였던 "이석 인정 기준" 카드의 `AbsenceThresholdEditor`는 안 고쳤었음 — 이전에 찍은 스크린샷에서 버튼이 파란 캡슐 모양으로는 보여서 "버튼이 있다"고 판단했지만, 그 캡슐 안에 "저장" 텍스트가 실제로는 안 보이는 상태였는데 스크린샷을 자세히 보지 않고 놓쳤던 것 — **같은 버그 패턴을 카드 하나 고치고 다른 카드에 똑같이 남아있는 걸 놓친 사례**.
- **수정**: `AbsenceThresholdEditor`의 `OutlinedTextField`에 `Modifier.weight(1f)` 추가(기존엔 weight 없이 `Button`을 밀어내고 있었음) — 이제 텍스트필드가 남는 공간만 차지하고 버튼은 항상 제 크기(저장 텍스트 포함)를 확보.
- **재검증**: 재빌드 → `adb install -r` → 앱이 충전 화면(잠금 아님, `isKeyguardShowing=false`)에 가려 있어서 `input keyevent KEYCODE_WAKEUP` + 위로 스와이프로 깨운 뒤 `monkey`로 재실행 → 설정 화면 스크린샷에서 "이석 인정 기준"·"점심시간" 두 카드 모두 "저장" 글자가 정상적으로 보이는 것 확인.
- **How to apply**: 이런 반복되는 레이아웃(같은 컴포저블 패턴이 여러 카드에 쓰였을 때) 버그를 하나 고칠 땐, 같은 패턴을 쓴 다른 곳도 같이 훑어서 고칠 것 — 이번처럼 "카드 A만 고치고 카드 B는 똑같은 버그인데 놓치는" 재작업을 피할 것.

## 2026-07-14: 설정 화면 "저장" 버튼 제거 → 자동저장(디바운스 + 화면 이탈 시 flush) 방식으로 전환

- **사용자 지시**: "보통 이런 거 저장 버튼 없이 바로 변경하면 저장되게 하는 게 깔끔하다. 일정 동안 연속 입력 변경이 없거나 뒤로 가기 등으로 화면을 벗어나면 변경사항이 저장되게." — 명시적 저장 버튼 UX를 버리고 자동저장으로 전환하라는 요청.
- **구현**: `SettingsScreen.kt`의 `AbsenceThresholdEditor`/`LunchWindowEditor`에서 `Button`(저장)을 완전히 제거. 대신:
  - `LaunchedEffect(text)`(점심시간은 `LaunchedEffect(startText, endText)`)로 800ms(`AUTO_SAVE_DEBOUNCE_MS`) 디바운스 자동저장 — 값이 바뀔 때마다 이전 대기 코루틴이 취소되고 새로 시작되므로, 타이핑을 멈추고 800ms 지나야 실제 저장(`onSave`) 호출됨. 유효하지 않은 값(파싱 실패, 시작≥종료 등)은 조용히 무시(기존 저장 버튼 방식과 동일한 검증 기준 유지).
  - `DisposableEffect(Unit) { onDispose { trySave() } }`로, 디바운스가 끝나기 전에 사용자가 뒤로가기 등으로 이 컴포저블이 컴포지션에서 빠질 때 그 시점 값을 즉시 한 번 더 저장 시도 — "화면을 벗어나면 저장" 요구사항을 담당.
- **실기기 검증**: 재빌드/재설치 후, 이석 인정 기준을 "42"로 바꾸고 800ms 이상 대기 → 홈으로 나갔다가 설정 화면에 다시 들어가서 "42"가 그대로 유지되는 것으로 DataStore에 실제로 저장됐음을 확인(단순히 로컬 state가 남아있는 게 아니라 `absenceThresholdMinutes` Flow에서 다시 읽어온 값임). 이후 실수로 남긴 테스트 값은 같은 방식으로 다시 10분으로 되돌려놓음.
- **How to apply**: 이 프로젝트에서 앞으로 추가되는 규칙 설정 UI(근무/출근 인정 시간 등)도 저장 버튼 없이 이 디바운스+dispose-flush 패턴을 기본으로 따를 것 — 이번에 사용자가 명시적으로 확인한 UX 방향.

## 2026-07-14: 점심시간 기본값 변경 요청 — 값 미지정으로 사용자에게 확인 대기 / DataStore 검증 방법 교훈

- **사용자 지시**: "기본 점심시간도 바꿔" — 몇 시로 바꿀지 값이 없어서, 먼저 폰에 이미 커스텀 값이 저장돼 있는지 확인 후(`run-as com.commute.app cat .../commute_settings.preferences_pb`로 protobuf 원본 읽어서 수기로 varint 디코딩) 점심시간은 기본값(720분/780분=12:00~13:00) 그대로임을 확인. 이석 인정 기준 값도 같이 읽었는데 이때 수기 디코딩으로 13(0x0d)으로 잘못 읽었으나, 앱을 완전히 재시작(`monkey`)해서 새 프로세스가 디스크에서 다시 읽은 값으로 화면을 보니 정상적으로 10이었음 — **수기 protobuf 바이트 디코딩은 오프셋을 놓치기 쉬워 신뢰도가 낮음**. 값 확인은 원본 바이너리를 손으로 파싱하기보다 "앱 재시작 후 화면에 뜨는 값"으로 확인하는 편이 안전함(캐시/메모리 상태가 아니라 디스크에서 새로 읽은 값이라 신빙성 높음).
- **미해결**: 사용자에게 새 점심시간 값(시작~종료)을 물어보고 답변 대기 중.

## 2026-07-14: 점심시간 기본값 11:20~12:20 + 일별/주간 근무시간 통계·막대그래프 + 홈 화면 현황/기록 탭 구조로 재편

- **사용자 지시**: "11:20~12:20이 지금 운영되는 점심시간이니 이를 기본값이 되게 하고, 최근 기록은 1주일 치가 나오고 스크롤이 되게, 통계 그래프 보기, 홈에 주간 총 근무시간·오늘 근무시간 등 현황을 볼 수 있는 탭이 있었으면 좋겠다. 홈의 전체 상단의 내용이 높이를 줄여서 전체의 1/3정도만으로 차지하게 하자."
- **점심시간 기본값**: `SettingsRepository.DEFAULT_LUNCH_START_MINUTE`/`DEFAULT_LUNCH_END_MINUTE`를 720/780(12:00~13:00)에서 680/740(11:20~12:20)으로 변경. DataStore에 값이 한 번도 저장 안 된 사용자는 재설치 없이 다음 실행부터 새 기본값을 바로 봄(값을 저장한 적이 없으면 매번 fallback 상수를 읽는 구조라서).
- **일별 실 근무시간 계산 로직 신설(`data/WorkStats.kt`)**: ARRIVE→LEAVE를 시간순으로 페어링해 세션 길이를 합산하고, 그 세션과 겹치는 이석(AWAY) 구간 중 **점심시간과 겹치는 부분만** 차감(가산 연구소 규정상 10분 미만 이석은 근무시간 인정이라 점심이 아닌 이석은 그대로 근무로 침). 아직 LEAVE가 안 찍힌(퇴근 전) 세션은 `nowMillis`로 마감해서 "오늘 근무시간"이 실시간에 가깝게 반영됨. `WifiMonitorService`에 있던 `timestampAtMinuteOfDay` 중복 헬퍼를 `data/TimeOfDay.kt`로 옮겨 공유.
- **ViewModel 통계 노출**: `CommuteViewModel`에 `recentEvents`(최근 7일), `dailyWorkStats`(최근 7일 일별), `todayWorkedMinutes`, `weeklyWorkedMinutes` StateFlow 추가. `dailyWorkStats`는 이벤트/점심설정 변화뿐 아니라 **1분 주기 ticker**와도 `combine`해서, 세션이 계속 열려있는 동안(퇴근 전)도 "오늘" 값이 시간이 지나며 갱신되게 함.
- **홈 화면 구조 변경**: 기존 `StatusHeroCard`+`WifiCard`+`MonitoringCard` 3장을 `CompactStatusCard` 하나로 통합(출근상태/와이파이상태/자동감지 스위치를 한 줄에, 등록 필요할 때만 보조 줄 노출)해서 상단 높이를 크게 줄임. 처음엔 상단을 `weight(1f)`로 강제 고정했더니 내용이 짧아서 위아래에 큰 빈 공간이 생기는 어색한 결과가 나왔음 — **"1/3만 차지"의 실제 의도는 "상단이 그 이상을 넘지 않게"이지 "정확히 1/3을 채우라"가 아니었음**을 스크린샷으로 확인 후, 상단을 `wrapContentHeight`(내용만큼만)로 바꾸고 남는 공간 전부를 하단 탭(`weight(1f)`)에 몰아주는 방식으로 수정.
- **현황/기록 탭(`HomeTabs.kt` 신설)**: `TabRow`로 "현황"(오늘/최근7일 합계 스탯 타일 + 요일별 막대그래프)과 "기록"(최근 7일 이벤트 `LazyColumn`, 자체 스크롤) 두 탭 구현. 기존 `EventRow`/`formatEventTimeRange`를 `MainActivity.kt`에서 이 파일로 이동.
- **차트 작업 전 `/dataviz` 스킬 사용**: Compose Canvas로 막대그래프를 그리기 전에 dataviz 스킬을 먼저 읽음(시스템 프롬프트 규칙상 차트 작성 전 필수 호출). 적용한 판단: 이 그래프는 요일별 "근무시간" 하나의 시리즈만 그리는 것이므로 **nominal categorical, 단일 시리즈** → 범례 없이 테마 primary 색 하나만 사용, 오늘 막대만 알파값을 높여 강조(보조 인코딩). 막대 스펙(상단만 4dp 라운드, 바닥은 각짐, 두께 상한 24dp, 축선은 recessive 1px)도 가이드대로 구현.
- **빌드/실기기 검증**: `gradlew assembleDebug` 매 단계 성공(경고 0개). `RoundRect(...)` 생성자 파라미터명이 `topLeftCornerRadius`가 아니라 `topLeft`인 걸 몰라서 첫 빌드 실패 — 수정 후 통과. adb로 실기기 설치·`monkey` 실행·스크린샷으로 현황 탭(오늘/7일 합계 숫자, 막대그래프), 기록 탭(실제 iptime5G ARRIVE/LEAVE 기록), 설정 화면(점심시간 11:20~12:20 기본값 반영) 모두 확인.
- **미해결**: 근무/출근 인정 시간 캡핑, 4h/8h/12h 휴게시간 완전 공제, 주 40시간 초과 경고 등은 여전히 미구현. `WorkStats`의 "점심 겹침만 차감" 방식은 문서의 전체 휴게시간 표(4h마다 30분 등)를 완전히 구현한 게 아니라 단순화한 v1 — 다음에 정식 휴게시간 표를 구현할 때 이 로직을 교체해야 함.

## 2026-07-14: 그래프 클릭 상세보기 + "최근 7일"→"이번주"(월요일 기준) 전환 + 와이파이 연결 대신 스캔 검출 기반 감지로 전환

- **사용자 지시**: "그래프를 누르면 상세 기록을 확인할 수 있게(출퇴근 시간 바로 확인). 월요일부터 금요일까지가 항상 기준이 되니 최근 7일보다 이번주 근무시간이 더 필요하다. 그리고 와이파이 인식할 때 꼭 연결하지 않더라도 주변에 회사 wifi가 검출되면 되는 걸로 하자."
- **① 그래프 클릭 → 상세 다이얼로그**: `HomeTabs.kt`의 `WeeklyBarChart`에 `Modifier.pointerInput` + `detectTapGestures`로 탭 좌표를 막대 인덱스로 변환해 `onDayClick(day)` 콜백 추가. `StatusTab`이 `selectedDay` 상태를 들고 있다가 `DayDetailDialog`(`AlertDialog`)를 띄움 — 해당 날짜의 이벤트를 `startOfDay(it.timestamp) == day`로 필터링해 기존 `EventRow`로 그대로 렌더링(출근/퇴근/이석 각각의 실제 시각이 그대로 보임). 다이얼로그가 그날의 이벤트를 찾으려면 그 날짜가 `weekEvents`(아래 ②) 범위 안에 있어야 하므로, 차트에 보이는 요일(이번주 월~일)과 클릭 시 조회하는 이벤트 소스 범위가 항상 일치하도록 같은 `weekEvents`를 공유시킴.
- **② 최근 7일(롤링) → 이번주(월요일 리셋)**: `data/WorkStats.kt`에 `startOfWeek(timestamp)` 추가(월요일 자정 기준, `Calendar.DAY_OF_WEEK` 기준 역산). `CommuteViewModel`의 `recentWindowStart()`(오늘-6일) 방식을 전부 제거하고 `dailyWorkStats`/`weekEvents`(이전 이름 `recentEvents`에서 개명) 둘 다 `startOfWeek(now)` 기준으로 필터링하도록 변경 — 화요일에 열어보면 "이번주"가 월/화 이틀치만 나오고, 그 다음 주 월요일이 되면 완전히 리셋됨(이전엔 항상 지난 7일이 굴러다녔음). `WeeklyBarChart`도 `오늘-6일..오늘` 대신 `이번주 월요일..일요일` 7일을 항상 그림(아직 안 지난 미래 요일은 0으로 빈 막대). 라벨도 "최근 7일 합계/근무시간" → "이번주 총 근무시간/이번주 근무시간"으로 변경.
- **③ 와이파이 감지: 연결(connection) 기반 → 스캔 검출(scan) 기반**: `wifi/WifiUtils.kt`에 `isCompanyWifiNearby(context, ssid)` 신설 — `WifiManager.scanResults`(OS가 백그라운드로 주기 갱신하는 캐시)에서 등록된 SSID가 하나라도 보이면 true. `WifiManager.startScan()`은 호출하지 않음(Android 9+에서 매우 강하게 throttle되고, 60초 폴링 주기에서 굳이 강제로 새 스캔을 트리거할 이유도 적음 — OS가 이미 주기적으로 스캔한 캐시를 읽는 걸로 충분하다고 판단). `WifiMonitorService.checkWifiState()`의 판정 기준을 `currentWifiSsid(...) == companySsid`(실제 연결 여부)에서 `isCompanyWifiNearby(...)`(주변 검출 여부)로 교체, 변수명도 `connectedToCompany`→`companyWifiNearby`로 개명해 의도를 명확히 함. 새 권한은 불필요(스캔 결과 읽기는 기존 `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE`로 충분, `startScan()`을 안 쓰므로 `CHANGE_WIFI_STATE`도 불필요). 와이파이 **등록** 플로우(현재 연결된 네트워크를 회사 와이파이로 등록하는 버튼)는 그대로 연결 기반 유지(등록 시점엔 실제로 연결돼 있는 게 자연스러운 UX라 안 건드림) — `currentWifiSsid()`는 그 용도로만 남김.
- **빌드/실기기 검증**: `gradlew assembleDebug` 경고 0개로 성공. adb 설치 후 스크린샷으로 막대그래프가 월~일 순서로 나오고 오늘(수) 막대가 강조되는 것, 막대 탭 시 "7월 15일 (수)" 다이얼로그에 실제 출근 기록(07/15 07:55)이 뜨는 것 확인. 스캔 기반 감지로 바꾼 뒤에도 서비스가 크래시 없이 계속 폴링되고("오늘 근무시간"이 58분→1시간1분으로, "이번주 총 근무시간"이 1시간43분→1시간46분으로 시간 경과에 따라 정상 증가) 있음을 확인 — 다만 이건 "여전히 연결된 상태에서도 스캔 로직이 정상 동작한다"는 것만 검증한 것이고, **실제로 연결을 끊은 채 근처에만 있을 때도 ARRIVE가 유지되는지는 아직 라이브로 검증 못함**(폰을 실제로 옮겨야 확인 가능).
- **미해결**: 스캔-only 감지의 실제 "연결 안 하고 근처에만 있기" 시나리오 라이브 검증 필요(다음 세션 후보). 근무/출근 인정 시간, 완전한 휴게시간 표, 주 40시간 초과 경고는 여전히 미구현.

## 2026-07-14: 기록 수정/삭제 기능 추가 (오인식 보정)

- **사용자 지시**: "잘못된 기록을 변경할 수 있는 것도 필요하다. 기록이 잘못인식되는 경우가 있을 수 있다." — README TODO에 있던 "기록 수정/삭제 화면 없음" 항목을 해소.
- **DB 계층**: `CommuteDao`에 `@Update suspend fun update(event: CommuteEvent)`, `@Delete suspend fun delete(event: CommuteEvent)` 추가(스키마 변경 없이 PK 기반으로 동작해서 Room DB 버전업 불필요). `CommuteViewModel`에 `updateEvent`/`deleteEvent` 함수 추가, 둘 다 `dao`를 직접 호출.
- **UI**: `HomeTabs.kt`의 `EventRow`가 `onClick` 콜백을 받도록 확장하고 `Modifier.clickable`로 감쌈 — "기록" 탭과 "현황" 탭의 날짜별 상세 다이얼로그(`DayDetailDialog`) 양쪽에서 재사용되므로, 두 곳 모두 각자 `editingEvent` 상태를 들고 `EditEventDialog`를 띄우는 구조로 만듦(다이얼로그 위에 다이얼로그가 뜨는 형태 — `DayDetailDialog` 안에서 기록을 누르면 그 위에 `EditEventDialog`가 겹쳐 뜸).
- **`EditEventDialog` 설계**: 유형은 `FilterChip` 3개(출근/퇴근/이석)로 전환, 날짜(`yyyy-MM-dd`)와 시각(`HH:mm`, 이석이면 시작/종료 두 개) 텍스트필드로 수정. 이 다이얼로그는 **설정 화면과 달리 자동저장(디바운스)을 쓰지 않고 명시적 "저장" 버튼**을 둠 — 과거 기록(특히 삭제)을 건드리는 건 실수 시 되돌리기 어려운 파괴적 행위라, 단순 설정값 조정([[feedback_settings_autosave_no_button]])과는 다른 UX 카테고리로 판단해 일부러 다르게 감. "삭제"는 2단계 확인(첫 클릭 시 버튼 라벨이 "정말 삭제"로 바뀌고, 그 상태에서 한 번 더 눌러야 실제 삭제) — 별도 다이얼로그를 새로 띄우지 않는 가벼운 방식으로 구현.
- **날짜/시각 파싱**: `SimpleDateFormat("yyyy-MM-dd HH:mm").apply { isLenient = false }`로 파싱(윤년 아닌 2/30 같은 값 거부). 파싱 실패나 이석인데 종료시각이 시작시각보다 빠른 경우는 조용히 저장 무시(기존 앱의 "잘못된 입력은 조용히 무시" 컨벤션과 통일).
- **실기기 검증**: adb로 실제 기록(퇴근 07/14 16:35)을 16:40으로 수정 → 리스트에 즉시 반영 확인 → 다시 16:35로 되돌려 원상복구까지 확인(스크린샷 3장으로 수정 전/후/복원 확인). 삭제 기능은 실사용자 데이터 손실 위험이 있어 실기기에서 실제로 삭제해보지는 않고 코드 리뷰로 갈음(2단계 확인 로직이 코드상 올바름을 확인).
- **How to apply**: 이 앱에서 "값 편집" UX는 두 갈래로 나뉨 — 설정(이석기준/점심시간)처럼 되돌리기 쉬운 값은 자동저장, 과거 기록처럼 삭제/수정이 파괴적인 데이터는 명시적 저장 버튼 + 삭제 2단계 확인. 앞으로 비슷한 기능을 추가할 때 이 구분 기준을 따를 것.

## 2026-07-15: 설정 화면에 근거 문서(가산 연구소 운영 방안 PDF) 보기 기능 추가

- **사용자 지시**: "운영방안 기준이 되는 문서도 설정 아래에 보기 기능을 넣어서 보게 하자." — 지금까지 `doc/가산 연구소 운영 방안_0923 (1).pdf`는 저장소 루트에만 있고 앱에는 전혀 포함돼 있지 않았음(앱이 읽을 수 없는 참고용 파일이었음).
- **자산 번들링**: 원본을 `app/src/main/assets/가산연구소_운영방안_20220923.pdf`로 복사(파일명에서 공백/괄호 제거해 안전한 이름으로). `doc/`의 원본 파일은 그대로 두고 손대지 않음(여전히 범위 밖 취급) — assets 쪽은 이제부터 앱이 실제로 참조하는 소스이므로 **커밋 대상에 반드시 포함**해야 함(이전 세션들이 `doc/`을 계속 범위 밖으로 취급했던 것과 혼동하지 말 것).
- **열람 방식**: 인앱 PDF 렌더러를 새로 구현하지 않고, `Intent.ACTION_VIEW` + `FileProvider`로 기기에 설치된 외부 PDF 뷰어에 위임하는 방식을 선택(가장 적은 코드로 안정적). `PolicyDocument.kt`에 `openPolicyDocument(context)` 함수: 최초 호출 시 asset을 `cacheDir/docs/`로 복사(FileProvider는 asset 스트림이 아니라 실제 파일이 필요해서) → `FileProvider.getUriForFile(...)`로 `content://` URI 생성 → `ACTION_VIEW` 인텐트(`FLAG_GRANT_READ_URI_PERMISSION`)로 실행. 뷰어 앱이 하나도 없는 극단적 경우만 `ActivityNotFoundException`을 잡아 토스트로 안내.
- **매니페스트/리소스**: `AndroidManifest.xml`에 `androidx.core.content.FileProvider` `<provider>` 등록(`android:authorities="${applicationId}.fileprovider"`), `res/xml/file_paths.xml` 신설(`cache-path name="docs" path="docs/"`로 캐시의 `docs/` 하위만 공유 허용). 새 런타임 권한은 필요 없음(`androidx.core:core-ktx`에 FileProvider가 이미 포함돼 있어서 의존성 추가도 불필요).
- **설정 화면 UI**: `SettingsScreen.kt`에 "근거 문서" 카드 추가 — 설명 텍스트 + "가산 연구소 운영 방안 보기" 버튼.
- **실기기 검증**: adb로 버튼을 눌러 안드로이드의 "연결 앱"(공유 대상 앱 선택) 시트가 정상적으로 뜨는 것 확인(OneDrive, 삼성노트 PDF리더, 한컴오피스 Viewer, Drive, M365 등 PDF 처리 가능한 앱들이 후보로 나옴 — 시스템이 파일을 유효한 PDF로 정확히 인식했다는 뜻이고 FileProvider 권한도 올바르게 부여됐다는 뜻). 실제 뷰어 앱 내부에서 내용이 제대로 렌더링되는지는 서드파티 앱의 몫이라 별도로 검증하지 않음. 크래시/FileProvider 관련 오류 로그 없음 확인.
- **좌표 실수 메모**: 이번 검증 중 스크린샷 좌표를 실기기 픽셀로 환산할 때(표시 923px → 실제 1440px, 배율 1.56) 한 번 배율 곱하는 걸 깜빡해서 엉뚱한 곳(다른 필드)을 탭한 적 있었음 — 다음에도 스크린샷 좌표를 tap에 쓸 때는 항상 "표시 좌표 × 1.56 = 실제 좌표" 공식을 명시적으로 계산할 것.

## 2026-07-15: 현황 탭 그래프를 "근무시간량 막대"에서 "출퇴근 시간대 타임라인"으로 전환

- **사용자 지시**: "그래프도 총근무시간만 보이는데, 출근 시간, 퇴근시간만 비교해서 볼 수 있게 하자. ... y축이 시간 기준으로 막대 그래프를 출근에서 퇴근까지를 막대를 표시하는 방안이 되는 모양이 있으면 될거 같아. 즉 근무시간량을 볼 수도 있고, 출퇴근 시간도 같이 표출되게." 이전까지 그래프는 0을 기준선으로 자란 막대(높이=근무시간)만 보여줘서, 하루 언제 출근/퇴근했는지는 알 수 없었음.
- **선택한 방식**: "그래프 종류를 선택"(토글) 방식 대신, **떠있는 범위 막대(고저 온도 그래프와 같은 형태)** 하나로 두 정보를 동시에 표현 — Y축을 시각(06:00~22:00, 근무 인정 시간 상한과 동일)으로 바꾸고, 각 요일 막대를 그날의 **첫 출근~마지막 퇴근**(아직 퇴근 안 했으면 현재 시각까지) 구간에 맞춰 띄워서 그림. 막대 위치=언제, 막대 길이=얼마나, 두 가지를 한 시각화로 해결 — 사용자가 결론으로 말한 "즉 ~ 둘 다 표출"과 정확히 일치하는 형태라 별도 차트 타입 선택 UI는 불필요하다고 판단.
- **`data/WorkStats.kt` 확장**: `DailyWorkStat`에 `firstArriveAt`/`lastLeaveAt`/`open`(아직 퇴근 안 한 세션인지) 필드 추가. `computeDailyWorkStats`가 세션을 닫을 때마다 그 날짜의 최초 출근/최후 퇴근을 같이 누적하도록 내부 `DayAccum` 헬퍼로 리팩터. 기존 `workedMinutes` 계산 로직(점심 겹침 이석 차감 등)은 그대로 — 필드만 추가, 하위 호환.
- **`HomeTabs.kt`의 `WeeklyBarChart` → `WeeklyRangeChart`로 교체**: Y축 매핑 `yFor(minute)`(06:00→막대 하단, 22:00→막대 상단), 4시간 간격 그리드라인(06/10/14/18/22시)과 시각 라벨을 `TextMeasurer` + `drawText`(Compose Canvas용 신규 텍스트 렌더 API, 기존처럼 요일 라벨은 여전히 Canvas 밖 별도 `Row`의 Text로 처리하되 축 라벨은 Canvas 안에 직접 그림)로 표시. 왼쪽에 축 라벨 폭만큼(34dp) 여백을 두고, 탭 좌표 판정도 그 여백을 제외한 영역 기준으로 다시 계산(전에는 Canvas 전체 폭 기준이었음). 데이터 없는 요일은 막대를 그리지 않음(빈 슬롯).
- **현황 탭 라벨 갱신**: "이번주 근무시간 (눌러서 상세 기록 보기)" → "이번주 출퇴근 시간 (눌러서 상세 기록 보기)"로 의미에 맞게 변경.
- **직전 라운드(출근/퇴근/근무시간 3종 요약, 다이얼로그 상단)와의 정합성**: 그 요약은 `dayEvents`에서 즉석으로 첫 ARRIVE/마지막 LEAVE를 구해서 썼는데, 이번에 `DailyWorkStat`에 같은 정보(`firstArriveAt`/`lastLeaveAt`/`open`)가 이미 계산돼 있으므로 사실상 중복 계산 — 지금 당장 통합하지 않고 두 경로를 그대로 뒀음(다이얼로그는 여전히 원시 이벤트에서, 차트는 `DailyWorkStat`에서). 근거: 다이얼로그 쪽은 "그 날짜의 실제 개별 이벤트 목록"이 이미 필요해서 어차피 `dayEvents`를 필터링해야 하고, 거기서 첫/마지막을 뽑는 게 자연스러움 — 무리해서 하나로 합치면 오히려 두 함수 시그니처가 꼬임. 다음에 성능/일관성 문제가 생기면 그때 통합 고려.
- **실기기 검증**: adb 스크린샷으로 확인 — Y축에 06:00/10:00/14:00/18:00/22:00 그리드라인과 라벨 정상 표시, 화요일 막대가 실제 기록(15:49~16:35)과 일치하는 위치에, 오늘(수) 막대가 07:55~현재 사이 위치에 뜨는 것 확인. 막대 탭 → 다이얼로그도 여전히 정상 작동(출근 07:55/퇴근 "근무 중"/근무시간 1시간 31분 요약 + 원시 이벤트 목록 모두 표시).
- **폰 잠금 이슈**: 이번 검증 도중 폰이 실제 PIN/패턴 잠금(`isKeyguardShowing=true`)에 걸려 스와이프로는 안 풀렸음 — 사용자에게 잠금 해제를 요청하고 대기했다가 재개함. **이런 경우 PIN을 추측하거나 강제로 풀려고 시도하지 말고, 사용자에게 해제를 요청할 것**(보안/권한 경계를 넘지 않기 위함).

## 2026-07-15: "이석"→"자리비움" 용어 통일 + 4단계 상태 표시 + 와이파이 검색 등록 기능

- **사용자 지시**: (1) "이석이라는 용어보다는 자리비움이 용어가 맞다. 이석이라는 용어대신 자리비움으로 바꿔라" (2) "상태는 출근 인식됨, 근무중, 자리비움, 퇴근으로 상태 표시를 하게 하자" (3) "와이파이 버튼을 누르면 주변 와이파이를 찾아서 회사 와이파이로 등록 할 수있는 검색 기능을 넣자." 같은 메시지에 타임라인 그래프 요청이 한 번 더 그대로 반복돼 있었는데, 그건 바로 직전 라운드에서 이미 구현·검증 완료된 상태라 재작업 없이 넘어감(사용자가 여러 요청을 한 번에 적으면서 이전 문장이 실수로 같이 복사된 것으로 판단).
- **① 용어 통일**: `grep -rn "이석"`으로 코드 전체(주석 포함) + README에서 전부 찾아 `sed`로 일괄 치환. `CommuteEventType.AWAY`라는 내부 식별자 자체는 안 건드림(영문 enum 이름은 용어 논쟁과 무관) — 사용자 노출 문자열(알림 문구, `EventRow`/`EditEventDialog`의 "이석" 라벨, `SettingsScreen`의 "이석 인정 기준" 카드 제목/설명, 코드 주석)만 교체. sed 치환 후 `README.md`에서 "이석(자리비움) 구분" 같은 기존 괄호 병기 문구가 "자리비움(자리비움)"으로 중복돼버린 곳 하나를 수동으로 고침 — 기계적 치환 후엔 항상 이런 중복/어색한 결과가 없는지 diff를 훑어볼 것.
- **② 4단계 상태 표시**: 기존엔 "출근 중"/"퇴근" 2가지뿐이었음. "출근 인식됨"이 왜 필요한지 고민한 끝에, 앱이 이미 갖고 있는 두 신호의 조합으로 자연스럽게 4가지가 나온다는 걸 발견함 — ① `isAtWork`(서비스가 60초 주기로 공식 커밋하는 세션 상태), ② `companyWifiDetectedNow`(화면이 3초 주기로 직접 확인하는 "지금 이 순간" 감지 여부, 신규 추가). `isAtWork × detectedNow`의 2×2:
  - `false × false` → 퇴근
  - `false × true` → **출근 인식됨**(와이파이는 보이는데 서비스가 아직 60초 주기를 안 돌아서 공식 기록 전 — 최대 1분 정도 지속되는 자연스러운 과도 상태)
  - `true × true` → 근무중
  - `true × false` → 자리비움(서비스의 유예시간 로직이 살아있는 동안, 즉 아직 진짜 퇴근으로 확정되기 전)
  UI 색상: 근무중=`primaryContainer`(기존 유지), 퇴근=`surfaceVariant`(기존 유지), 출근 인식됨·자리비움=`tertiaryContainer`(기존 알림 카드와 같은 "주의 필요" 톤 재사용, 둘 다 "확정되지 않은 과도 상태"라 같은 톤으로 묶음 — 아이콘으로만 구분: 출근인식됨=Wifi, 자리비움=DirectionsWalk). `MainActivity.kt`의 3초 폴링 `LaunchedEffect`에 `isCompanyWifiNearby(context, companySsid)` 호출을 추가해서 `companyWifiDetectedNow`를 계산(기존엔 `currentWifiSsid`만 썼는데 그건 연결 기반이라 스캔 기반 서비스 로직과 불일치했음 — 이번에 상태 카드용으로는 서비스와 동일한 스캔 기반 함수를 씀). `companySsid`가 `by collectAsState()` 위임 프로퍼티라 스마트캐스트가 안 돼서 `val registeredSsid = companySsid`로 로컬 변수에 담아 null 체크 후 사용해야 했음(흔한 Compose 컴파일 에러 패턴).
- **③ 와이파이 검색 등록**: `wifi/WifiUtils.kt`에 `requestWifiScan(context)`(`WifiManager.startScan()` 베스트에포트 호출)과 `nearbyWifiSsids(context)`(스캔 결과를 신호세기 내림차순으로 정렬, 중복/빈값 제거) 추가. 상시 폴링(`isCompanyWifiNearby`)과 다르게 이건 **사용자가 명시적으로 버튼을 눌러 1회성으로 트리거하는 액션**이라, 스로틀링 걱정 없이 `startScan()`을 직접 호출하기로 함(이전에 감지 로직에서는 스로틀링 우려로 `startScan()`을 의도적으로 안 썼던 것과 다른 판단 — "60초마다 자동 반복되는 백그라운드 폴링"과 "사람이 한 번 누르는 수동 액션"은 스로틀링 관점에서 리스크가 다르다고 봄). `startScan()` 결과를 비동기로 받는 `BroadcastReceiver`는 안 만들고, 그냥 1.5초 지연 후 캐시를 다시 읽는 방식으로 단순화(스로틀링돼도 최소한 이전 캐시는 보여줄 수 있어 완전히 빈 화면이 되진 않음). `CHANGE_WIFI_STATE` 권한을 매니페스트에 추가(정상 권한이라 런타임 프롬프트 없음). `MainActivity.kt`의 상태 카드 와이파이 아이콘을 `IconButton`으로 바꿔서 누르면 `WifiSearchDialog`(검색 중 스피너 → 목록 → 탭해서 `registerCompanySsid` 호출) 오픈.
- **실기기 검증**: adb로 확인 — 상태 카드가 "근무중"으로 정확히 표시(현재 실제로 iptime5G에 연결돐 상태). 와이파이 아이콘 탭 → "주변 와이파이 검색" 다이얼로그에 실제 주변 AP 목록(iptime5G, iptime, Solugate-AiData 등)이 신호세기순으로 뜨는 것 확인. 목록에서 기존 등록값과 동일한 "iptime5G"를 선택해(값이 안 바뀌어 안전) 선택→등록→다이얼로그 닫힘까지 전체 플로우 정상 동작 확인. 나머지 세 상태(출근 인식됨/자리비움/퇴근)는 폰을 실제로 옮겨야 재현되는 시나리오라 이번엔 라이브 검증 못 하고 코드 로직 검토로 갈음.
- **미해결**: 4단계 상태 중 "출근 인식됨"/"자리비움"/"퇴근" 전환이 실제 기기에서 의도대로 보이는지 라이브 검증 필요(다음에 실제로 자리를 비우거나 퇴근해보면서 확인). 근무/출근 인정 시간, 완전한 휴게시간 표는 여전히 미구현.

## 2026-07-15: 빠진 기록 추가 기능

- **사용자 지시**: "기록누락이 되면 추가 할 수잇는것도 잇어야 한다." — 와이파이/권한 문제나 폰이 꺼져있던 경우 등으로 서비스가 놓친 출근/퇴근/자리비움 기록을 사용자가 직접 채워 넣을 수 있어야 함.
- **DB/ViewModel**: `CommuteViewModel.addEvent(event)` 신설(`dao.insert` 호출). 스키마·Room 버전 변경 없음(기존 `insert` DAO 메서드 재사용).
- **다이얼로그 재사용**: 기존 `EditEventDialog`(기록 수정용)를 일반화 — `isNew: Boolean` 파라미터와 `onDelete: (() -> Unit)?`(nullable, 새 기록 추가 모드에서는 삭제할 게 없으니 `null`)를 추가해서 "수정"과 "추가" 두 용도를 같은 컴포저블로 처리. 제목/확인버튼 라벨만 "기록 수정"/"저장" ↔ "기록 추가"/"추가"로 갈리고 나머지 필드(유형 칩, 날짜, 시각)는 완전히 동일.
- **진입점 두 곳**: (1) `RecordsTab`에 `FloatingActionButton`("+") 추가 — 눌러서 오늘 날짜로 새 기록 추가. `LazyColumn`의 `contentPadding`에 `bottom = 88.dp`를 줘서 FAB에 마지막 항목이 가려지지 않게 함. (2) `StatusTab`의 `DayDetailDialog` 안에도 "이 날짜에 빠진 기록 추가" 텍스트버튼 추가 — 그래프에서 클릭한 **그 날짜**로 바로 채워서 열림(예: 지난주 화요일 기록이 통째로 빠졌으면 그 화요일 막대를 클릭해서 바로 추가 가능).
- **기본값 설계**: `blankEventTemplate(day, ssid)` 헬퍼가 `id=0`(Room이 자동 채번), `type=ARRIVE`, `ssid=companySsid`, `timestamp = 그날 자정 + 지금 시각의 시분초`(오늘이면 사실상 현재 시각, 과거 날짜면 "그날 + 지금 몇 시몇분" — 사용자가 어차피 날짜/시각 텍스트필드를 다시 고칠 걸 감안해 그냥 무난한 시작값)로 빈 레코드를 만듦.
- **빌드 트러블슈팅**: `DayDetailDialog`에 추가한 "빠진 기록 추가" 버튼의 아이콘에 `Modifier.size(18.dp)`를 썼는데 `HomeTabs.kt`에 `androidx.compose.foundation.layout.size` import가 없어서 컴파일 에러 — 추가해서 해결.
- **빌드 검증**: `gradlew assembleDebug` 성공, adb install 성공. 이번 라운드는 폰이 실기기 검증 도중 다시 PIN 잠금(`isKeyguardShowing=true`)에 걸려서(설치 자체는 화면 잠금 상태에서도 `adb install`로 가능했지만) 스크린샷 검증은 다음 잠금 해제 이후로 미룸 — **커밋 전 마지막 실기기 확인 단계에서 세션 안에서 폰이 여러 번 반복적으로 잠기는 패턴이 있음을 인지**, 다음에도 비슷한 상황이면 당황하지 말고 동일하게 사용자에게 해제 요청 후 대기.
- **미해결**: 이번 기능도 아직 실기기 스크린샷으로 확인 못함 — 다음에 잠금 해제되면 FAB 추가 플로우와 날짜별 추가 플로우 둘 다 확인 필요.

## 2026-07-15: 상태 카드에 점심시간 표시 + 현황 탭 그래프 좌우 스와이프로 주간 이동

- **사용자 지시 ①**: "상태에 점심시간인지도 표시 하게 하자." 설정에 점심시간(`lunchStartMinute`/`lunchEndMinute`)은 이미 있었지만 근무시간 계산(자리비움 차감)에만 쓰이고, 화면 어디에도 "지금이 점심시간"이라는 표시는 없었음.
  - `data/TimeOfDay.kt`에 `isWithinMinuteOfDayWindow(timestamp, startMinute, endMinute)` 헬퍼 추가(자정 기준 분 단위 창 안에 있는지 체크, `overlapsLunch`와 같은 경계 규칙 재사용).
  - `MainActivity.kt`: 기존에 이미 3초마다 도는 와이파이 감지 `LaunchedEffect` 루프 안에 `isLunchTimeNow` 계산을 얹음(새 타이머를 따로 만들지 않고 기존 폴링에 편승). `lunchStartMinute`/`lunchEndMinute`를 이 `LaunchedEffect`의 key에 추가해서, 설정에서 점심시간을 바꾸면 (재구독 없이 값만 바뀌는 걸 놓치지 않도록) 루프가 재시작되게 함 — `val x by collectAsState()`는 코루틴 클로저 안에서 "매번 최신값을 읽는" 게 아니라 이펙트 시작 시점 값으로 고정되므로, 값이 바뀔 때 루프가 최신값을 쓰게 하려면 key로 지정해 재시작시켜야 함(놓치기 쉬운 함정).
  - 상태 카드(`CompactStatusCard`) 라벨에 `"${status.label} · 점심시간"` 형태로 덧붙임(점심시간이 아니면 기존과 동일).
  - **실기기 검증**: 기기 시각이 마침 11:56(기본 점심시간 11:20~12:20 안)이라 "근무중 · 점심시간"이 정확히 표시되는 걸 스크린샷으로 확인.
- **사용자 지시 ②**: "그래프를 좌우 스왑시키면 주간이동이 되게 하여 이전 기록을 볼 수 있게 하자." 이전까지 현황 탭 그래프(`WeeklyRangeChart`)와 그 데이터(`dailyWorkStats`)가 **항상 이번 주(월요일 기준)로만 필터링**돼 있어서 지난 주 기록을 볼 방법이 아예 없었음.
  - **ViewModel 쪽 필터링 제거가 핵심**: `CommuteViewModel.dailyWorkStats`가 기존엔 `.filter { it.dayStart >= weekStart(지금) }`로 이번 주만 잘라서 내려줬음 — 이걸 제거하고 전체 기록 기간의 `DailyWorkStat`을 그대로 노출하도록 바꿈. 대신 `weeklyWorkedMinutes`(상단 "이번주 총 근무시간" 타일)는 여기서 별도로 "실제 지금 이 순간 기준 이번 주"만 다시 필터링해서 계산 — 그래프가 과거 주로 이동해도 상단 통계 타일(오늘/이번주 근무시간)은 스와이프와 무관하게 항상 실제 현재 기준으로 고정.
  - `MainActivity.kt`: `StatusTab`에 넘기는 `events`를 기존 `weekEvents`(이번주만) 대신 `allEvents`(`viewModel.events` 전체)로 교체 — 과거 주의 날짜를 클릭했을 때 뜨는 `DayDetailDialog`도 그 날짜의 실제 이벤트를 보여줘야 하므로. `RecordsTab`(기록 탭)은 건드리지 않고 여전히 `weekEvents`(이번주만) 사용 — 이번 요청은 현황 탭 그래프에만 해당.
  - **주간 오프셋 상태**: `StatusTab`에 `weekOffset`(0=이번주, 음수=과거) 상태 추가, `weekStart = startOfWeek(now) + weekOffset*7일`. `WeeklyRangeChart`가 이제 `weekStart`를 파라미터로 받아 그 주의 7일을 그림(예전엔 함수 내부에서 항상 `startOfWeek(today)`로 이번 주를 직접 계산했음).
  - **스와이프 제스처**: `WeeklyRangeChart`의 Canvas에 탭 감지(`detectTapGestures`, 날짜 클릭)와는 별개의 `pointerInput(Unit)` 블록으로 `detectHorizontalDragGestures` 추가 — 드래그 총량을 누적하다 손을 뗄 때(`onDragEnd`) 임계값(56dp) 넘으면 `onWeekChange(-1 또는 +1)` 호출. 탭 제스처와 드래그 제스처를 같은 Canvas에 **별도의 `pointerInput` 블록 두 개**로 붙이는 게 Compose에서 안전한 패턴(각자 독립적으로 이벤트 스트림을 받음 — 하나로 합치려 하지 말 것).
  - **미래 주 진입 차단**: `onWeekChange`에서 `weekOffset = (weekOffset + delta).coerceAtMost(0)`로 캡 — 이번 주보다 미래로는 못 감(어차피 데이터도 없음).
  - **주 라벨/복귀 버튼**: 이번 주면 "이번주", 아니면 "7/6~7/12" 같은 날짜 범위를 표시(`weekRangeLabel`). 이번 주가 아닐 때만 우측에 작은 "이번주로" 텍스트버튼이 나타나 한 번에 복귀 가능(스와이프 왕복 대신 원터치 복귀 — 스와이프만으로는 발견성이 낮을 수 있어 보조 수단으로 추가).
  - **실기기 검증**: adb `input touchscreen swipe`로 그래프 영역을 오른쪽으로 스와이프 → "7/6~7/12"(과거 주, 데이터 없어 빈 그래프)로 정확히 이동 + "이번주로" 버튼 등장 확인. 반대로 두 번 스와이프 → "이번주"로 정확히 복귀하고 그 이상 미래로는 안 넘어가는 캡 동작도 확인.
  - **폰 잠금 재발**: 이번에도 세션 도중 폰이 충전 중 잠금화면(`isKeyguardShowing=true`)에 걸림 — PIN 추측/우회 시도 없이 사용자에게 해제 요청 후 대기, 해제되자마자 재개. (반복 패턴 — 이 프로젝트 세션에서는 거의 매번 검증 도중 한 번씩 발생하니 놀라지 말고 동일하게 대응할 것.)

## 2026-07-15: 현황 탭 그래프가 카드 남은 세로 공간을 전부 채우도록 확장 + 시간 눈금 촘촘하게

- **사용자 지시**: "그래프가 나머지 세로 영역모두를 차지하게 해서 시간 간격을 더 촘촘히 보일 수 잇게 하자." 그래프(`WeeklyRangeChart`)가 고정 `height(200.dp)`라 카드 안에서 크기가 작았고, 그 아래로 화면 하단까지 빈 공간이 남아있었음.
- **레이아웃 변경**: `StatusTab`의 바깥 `Column`에서 `.verticalScroll(rememberScrollState())`를 제거(콘텐츠가 이제 화면에 정확히 맞춰지므로 스크롤이 더 이상 필요 없어짐) 하고, 카드(`Card`)에 `.weight(1f)`를 줘서 탭 영역의 남은 세로 공간을 전부 차지하게 함. 카드 내부 `Column`에는 `.fillMaxSize()` 추가, `WeeklyRangeChart`에는 `Modifier.weight(1f)` 전달.
- **`WeeklyRangeChart` 내부**: `Canvas`의 고정 `.height(200.dp)`를 `.weight(1f)`로 교체 — Compose의 `weight(fill=true)`(기본값)는 자식에게 정확한 높이 제약을 강제하므로, 부모(`WeeklyRangeChart`의 루트 `Column`)가 `modifier`로 전달받은 weight만큼의 높이를 그대로 `Canvas`에 넘겨줌. 요일 라벨 `Row`(고정 높이)는 그대로 두고 `Canvas`만 나머지를 채움.
- **눈금 촘촘하게**: 그리드라인/시각 라벨 간격을 4시간(`4 * 60`)에서 2시간(`2 * 60`)으로 좁힘 — 차트가 커진 만큼 06:00~22:00 사이 눈금이 9개(06/08/.../22)로 늘어나 촘촘하게 보임. 사용될 일 없어진 `rememberScrollState`/`verticalScroll`/`height` import는 함께 제거.
- **실기기 검증**: adb 스크린샷으로 확인 — 카드가 하단 네비게이션 바 근처까지 꽉 채워지고, 06:00~22:00 사이 2시간 간격 그리드라인 9개가 모두 표시되는 것 확인. 상태 카드는 이 시점(12:24, 점심시간 11:20~12:20 종료 후)이라 "근무중"만 표시되고 "· 점심시간"이 안 붙는 것도 함께 확인(이전에 구현한 점심시간 표시 로직이 시간 경계에서 올바르게 꺼짐).

## 2026-07-15: 근무시간 계산에서 점심시간을 항상 제외하도록 수정 (버그 수정)

- **사용자 지시**: "근무시간에 점심시간은 제외하고 표시하는게 있어야 한다. 점심시간제외하여 근무시간을 계산하게 해야 함." 실기기로 직접 재현해보니, 오늘 07:55에 출근해서 점심시간(11:20~12:20)에도 회사 와이파이에 계속 연결돼 있었던 경우(자리비움 이벤트가 전혀 기록되지 않음) — "오늘 근무시간"이 경과시간과 거의 동일(5시간41분, 13:37 기준)하게 나와 점심 60분이 전혀 안 빠지고 있었음. 실제로 버그였음.
- **근본 원인**: `data/WorkStats.kt`의 `computeDailyWorkStats`가 점심시간을 **자리비움(AWAY) 이벤트가 실제로 점심시간과 겹칠 때만** 빼는 방식이었음(`awaySpans` 순회 + `overlapsLunch` 체크). 즉 사람이 점심시간에도 와이파이 범위 안에 계속 있어서(자리 비움이 감지 안 돼서) AWAY 레코드가 안 생기면, 점심시간이 "근무 안 함"으로 잡히지 않고 그냥 근무시간에 포함돼버림 — 정책상 점심은 실제 이석 여부와 무관하게 무급 휴게시간이어야 하므로 이건 의도한 동작이 아니라 버그였음([[project-gasan-labor-policy]] 참고 — 점심 관련 시각은 정책 문서 자체엔 없고 팀 실제 운영 관행 11:20~12:20을 씀).
- **수정**: AWAY 이벤트 유무와 무관하게, ARRIVE~LEAVE(또는 진행중이면 지금까지) 세션이 그날의 점심시간 구간과 겹치는 부분을 **항상** 계산해서 빼도록 변경. 기존 `awaySpans` 순회 로직과 `overlapsLunch` 함수를 제거하고, 세션 구간과 점심시간 구간의 순수 시간 겹침만 계산하는 `lunchOverlapMinutes(sessionStart, sessionEnd, lunchStartMinute, lunchEndMinute)` 헬퍼로 대체(겹치는 시작~끝의 `maxOf`/`minOf`로 오버랩 분만 계산). 점심시간이 아닌 짧은 자리비움은 여전히 근무시간에 포함(기존 정책 그대로, 변경 없음) — 이번 수정은 순수하게 "점심시간 구간"에만 해당.
- **부작용 없음 확인**: `overlapsLunch`/`awaySpans` 변수가 이 함수 안에서만 쓰이던 것이라 삭제해도 다른 곳에서 참조하는 데가 없음(grep으로 확인). `WifiMonitorService`의 자체적인 점심시간 유예 로직(`isWithinLunchWindow` 등)은 완전히 별개 코드라 이번 변경과 무관 — 건드리지 않음.
- **실기기 검증**: 수정 전 스크린샷(13:37, "오늘 근무시간 5시간 41분" — 점심 미차감)과 수정 후 스크린샷(13:50, "오늘 근무시간 4시간 54분" — 경과 약 5시간55분에서 점심 60분 차감된 값과 일치)을 비교해 정확히 확인. 검증 도중 폰이 또 한 번 충전 잠금화면에 걸려 사용자에게 해제 요청 후 대기했다가 재개(계속 반복되는 패턴, [[reference_adb_gitbash_gotchas]] 참고).

## 2026-07-15: 랄프루프 5회 전체 점검 (이번엔 새 결함 없이 문서 오류 1건만 발견)

- **사용자 지시**: "이제 기능적인 부분은 정리한거 같아. 전체 내용을 점검을 랄프루프로 5회 반복해서 점검 해줘." 이전 세션(2026-07-14, [[project-gasan-labor-policy]] 참고)에서 확립된 "감사→수정→빌드검증→커밋 반복" 패턴을 그대로 재적용. 이번엔 세션 시작 시점에 이미 12개 파일·800줄 넘게 쌓여 있던 미커밋 변경사항(자리비움 rename, 4단계 상태, 와이파이 검색, 현황/기록 탭 전체, 점심시간 표시, 주간 스와이프, 그래프 확장, 점심시간 계산 버그수정 등 이번 대화의 여러 라운드에 걸친 작업)이 대상.
- **Round 1**: 파일별 전체 diff를 하나하나 정독. 발견한 유일한 실질 결함은 코드가 아니라 **문서**: `README.md`의 "현황/기록 탭" 설명이 옛날 로직("점심시간과 겹치는 자리비움만 차감")을 그대로 서술하고 있어서, 같은 세션에서 방금 고친 "점심시간은 항상 차감" 동작과 어긋나 있었음 — 수정하고, 주간 스와이프·점심시간 표시·그래프 확장 등 이번 세션에 추가된 기능들도 README에 함께 반영. 리뷰 후 `gradlew assembleDebug` 통과 확인, `.teleclaude/memory.md`를 제외한 전체를(`.bkit/`, `doc/`는 계속 범위 밖으로 제외) 커밋(`da767d9`).
- **Round 2**: 코드 리뷰만으로는 확신할 수 없는 상호작용 버그 가설을 실기기로 직접 검증 — `WeeklyRangeChart`의 Canvas에 탭 감지(`detectTapGestures`)와 드래그 감지(`detectHorizontalDragGestures`)를 별개의 `pointerInput` 블록 두 개로 붙였는데, 스와이프 기능 추가 이후 막대를 탭해서 날짜별 상세 다이얼로그를 여는 기존 기능이 회귀했을 가능성을 확인. adb tap으로 재현 — **회귀 없음**, 다이얼로그 정상 오픈 확인(겸사겸사 점심시간 차감도 다이얼로그 안에서 5시간1분으로 정확한 것 재확인).
- **Round 3~4**: 나머지 전 파일(`CommuteEvent.kt`, `CommuteDatabase.kt`, `Notifications.kt`, `SettingsRepository.kt`, `BootReceiver.kt`, `build.gradle.kts`, `AndroidManifest.xml`, `proguard-rules.pro`, `res/xml/file_paths.xml`) 재검토 — 추가 결함 없음으로 확정. (release 빌드가 `isMinifyEnabled = false`라 proguard/FileProvider 난독화 충돌 우려도 실제로는 해당 없음을 확인.)
- **Round 5**: `gradlew clean assembleDebug`로 전체 회귀 빌드(40/40 태스크, BUILD SUCCESSFUL, 지난 라운드와 동일한 태스크 수) → adb install → 앱 재실행 후 logcat에서 크래시 없음(`ActivityTaskManager: Displayed com.commute.app/.MainActivity` 정상 확인, `libpenguin.so` 에러는 삼성 기기에서 흔히 나오는 무해한 시스템 로그로 앱과 무관).
- **How to apply**: 이번 라운드는 지난번(Round 2 크래시)과 달리 실제 코드 결함이 거의 없었음 — 문서-코드 불일치처럼 "기능은 맞는데 설명이 stale"인 경우도 랄프루프에서 놓치지 말고 찾아야 할 결함 범주로 취급할 것. 여러 세션에 걸쳐 쌓인 미커밋 diff를 리뷰할 땐 파일당 전체 diff를 처음부터 끝까지 읽고, 새 파일(untracked)은 diff가 아니라 파일 전체를 Read로 확인해야 놓치는 부분이 없음.

## 2026-07-15: 그래프 막대에 점심시간 구간 색상 구분 + "오늘 근무시간" 클릭 시 점심 포함 값 토글

- **사용자 지시**: "그래프에서도 막대그래프내에 점심시간영역을 다른 색으로 구분지어 표시하고, 오늘 근무시간을 클릭하면 점심시간포함 시간값을 표시 할 수 잇도록 하자." 점심시간이 근무시간 계산에서 항상 빠지도록 방금 고쳤지만([[project-commute-absence-threshold-implementation]] 계열), 그 빠지는 구간이 그래프에 시각적으로 드러나지 않고 "오늘 근무시간" 숫자만 봐서는 점심 포함 여부를 알 수 없었음.
- **데이터 모델 확장**: `data/WorkStats.kt`의 `DailyWorkStat`에 `rawSpanMinutes`(점심 차감 없는 순수 체류 시간, 기본값은 `workedMinutes`와 동일하게 해서 값이 없으면 조용히 옛 동작과 같아지게) 필드 추가. `computeDailyWorkStats`의 `DayAccum`에도 `rawMinutes` 누적 필드를 나란히 추가해서, 세션 하나 닫을 때마다 "점심 차감 전/후" 두 값을 동시에 누적. `DailyWorkStat` 생성자 호출부가 코드베이스에 딱 한 곳(`computeDailyWorkStats` 내부)뿐이라 포지셔널 인자 순서 변경이 안전한지 grep으로 확인 후 진행.
- **ViewModel**: `todayWorkedMinutesIncludingLunch` StateFlow 신설(`dailyWorkStats`에서 오늘자 `rawSpanMinutes` 추출) — 기존 `todayWorkedMinutes`와 동일한 패턴.
- **오늘 근무시간 타일 토글**: `StatusTab`에 `showTodayIncludingLunch` 로컬 상태 추가, `StatTile`에 `onClick` 파라미터(nullable)를 얹어 클릭 가능하게 만듦(클릭 핸들러 없으면 기존처럼 비대화형). 클릭할 때마다 라벨이 "오늘 근무시간" ↔ "오늘 근무시간 (점심 포함)"으로, 값이 `todayMinutes` ↔ `todayMinutesIncludingLunch`로 같이 토글. "이번주 총 근무시간" 타일은 건드리지 않음(이번 요청은 "오늘"에 대한 것만).
- **그래프 막대 내 점심 구간 표시**: `WeeklyRangeChart`가 `lunchStartMinute`/`lunchEndMinute`를 새로 받아서, 각 요일 막대를 그린 뒤 그 막대의 [출근분, 퇴근분] 구간과 점심 구간의 겹치는 부분만 `MaterialTheme.colorScheme.tertiary`(기존 `primary` 막대색과 구분되는 색)로 덧칠. 겹침 계산은 세션의 실제 타임스탬프를 다시 만들지 않고, 이미 y좌표 계산에 쓰던 "분 단위" 값(`arriveMinute`/`departMinute`)과 `lunchStartMinute`/`lunchEndMinute`를 그대로 `maxOf`/`minOf`로 비교하는 방식으로 구현 — `WorkStats.kt`의 실제 차감 로직과는 완전히 별개의 순수 렌더링 계산(값 자체는 우연히 일치하지만 다른 코드 경로). 오늘/과거 요일 구분용 알파값(`alpha`)을 점심 오버레이에도 동일하게 적용해서 흐림 처리 일관성 유지.
- **MainActivity.kt 배선**: 이미 점심시간 배지 기능 때문에 `lunchStartMinute`/`lunchEndMinute`를 `collectAsState`로 갖고 있어서 그대로 `StatusTab`에 추가 전달만 하면 됐음(새 상태 구독 불필요).
- **실기기 검증**: adb 스크린샷으로 확인 — 수요일 막대 12:00 그리드라인 부근에 밝은 색(tertiary) 띠가 정확히 보임. "오늘 근무시간" 탭 → 라벨이 "오늘 근무시간 (점심 포함)"으로 바뀌고 값이 "5시간 13분" → "6시간 13분"(정확히 +60분, 점심시간 길이와 일치)으로 변경 확인. 다시 탭해서 원래 값으로 복귀도 확인.

## 2026-07-15: TRide와 동일한 인증서로 릴리즈 APK 서명 구성

- **사용자 지시**: "릴리즈용으로 만들려는데. tride 과 같은 서명을 릴리즈 apk만들어줘" — 같은 개발자(tyranno)의 다른 안드로이드 앱인 TRide(`C:/Project/88.MyProject/TRide`, Capacitor 프로젝트)와 동일한 서명 인증서로 Commute의 릴리즈 APK를 서명해달라는 요청.
- **키스토어 출처**: TRide의 `android/app/tride-release.jks`(alias `tride`, keytool로 확인한 인증서 DN `CN=TRide, OU=App, O=Tyranno, L=Seoul, ST=Seoul, C=KR`, 2053년까지 유효)와 `android/keystore.properties`(storePassword/keyPassword 값 존재, TRide 저장소의 로컬 `keystore.properties`에 평문으로 있음 — 값 자체는 이 메모리 파일에는 기록하지 않음)를 그대로 재사용. TRide 쪽 `build.gradle`이 이미 이 파일들을 `.gitignore`(`keystore.properties`, `android/app/*.jks`)로 커밋 제외하고 있어서 Commute에도 동일한 패턴을 그대로 따름.
- **적용 방식**: `.jks` 파일을 `Commute/app/tride-release.jks`로 복사(원본 TRide 프로젝트 파일은 건드리지 않음), 저장소 루트에 `keystore.properties`(storeFile/storePassword/keyAlias/keyPassword) 신설. **`.gitignore`에 `/keystore.properties`, `app/*.jks`, `app/*.keystore`를 먼저 추가한 뒤에** 두 파일을 만들어서, 실수로 시크릿이 커밋되는 걸 방지(순서를 이렇게 지킨 이유: 파일부터 만들고 나중에 gitignore를 추가하면 그 사이에 `git add`를 잘못 실행할 위험이 있음).
- **`app/build.gradle.kts`**: `keystore.properties`를 읽어 `signingConfigs.release`를 구성하고 `buildTypes.release`에 연결하되, **파일이 없는 환경(다른 개발자 PC, CI)에서는 서명 없이(unsigned) 빌드되도록 `keystorePropertiesFile.exists()` 가드**를 둠 — 시크릿 파일이 없다고 빌드 자체가 실패하면 안 되기 때문(다른 사람이 클론했을 때도 최소한 debug/디버그 서명 없는 release는 빌드되게).
- **검증**: `gradlew assembleRelease` 성공(52/52 태스크) → `apksigner verify --print-certs`로 생성된 APK의 서명 인증서 SHA-256(`3c57b1f2...41ea`)이 키스토어 자체의 인증서 지문과 정확히 일치하는 것 확인. 실기기에 설치해 정상 실행(크래시 없음, `Displayed com.commute.app/.MainActivity` 로그 확인)까지 검증.
- **부수 효과(주의)**: 기존에 디버그 서명으로 설치돼 있던 앱을 릴리즈 서명 APK로 덮어 설치하려면 서명이 달라 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`이 나므로, 검증을 위해 `adb uninstall com.commute.app` 후 재설치함 — **이 과정에서 그 기기에 있던 기존 Room DB(출퇴근 기록)와 DataStore 설정(회사 와이파이 등록, 규칙 설정값)이 전부 삭제됨**. 테스트 기기였고 이번 세션 내내 쌓아온 테스트용 기록이라 문제로 보진 않았지만, 실제 사용 데이터가 있는 기기에서 서명이 다른 APK로 갈아끼울 땐 항상 이 데이터 손실이 생긴다는 점을 사용자에게 미리 알려야 함.
- **How to apply**: 이 개발자는 여러 개인 앱(TRide, Commute 등)에 같은 서명 키를 재사용하는 걸 선호함 — 다음에 다른 개인 프로젝트도 릴리즈 서명이 필요하면 먼저 이미 있는 `tride-release.jks`를 재사용할지 물어보거나 이 패턴을 따를 것. **주의**: `.teleclaude/memory.md`는 이 프로젝트에서 git에 커밋되는 파일이므로, 키스토어 비밀번호 등 시크릿은 절대 이 안에 평문으로 적지 말 것 — 실제 값은 gitignore된 `keystore.properties`(Commute)와 TRide의 로컬 `android/keystore.properties`에만 존재.

## 2026-07-15: "가산 연구소 운영 방안 보기"를 외부 PDF 뷰어 대신 앱 내 화면으로 전환

- **사용자 지시**: "가산 연구소 운영 방안 보기는 그냥 텍스트 내용이니 이 앱에서 보이게 하자." 기존엔 FileProvider로 캐시에 복사한 PDF를 `ACTION_VIEW` 인텐트로 외부 PDF 뷰어에 위임하는 방식([[project-gasan-labor-policy]] 2026-07-15 갱신분 참고)이었는데, 문서 자체가 이미지/표 없이 순수 텍스트 한 페이지짜리라 굳이 PDF 렌더링·외부 앱 의존이 필요 없다고 판단.
- **원본 문서 실제 읽어서 옮김**: `doc/가산 연구소 운영 방안_0923 (1).pdf`를 Read 도구로 직접 읽어 전체 텍스트(식대 지원 1~2번 항목, 자율출퇴근제 운영 A~E, 상세 운영 방안 i~ix, 휴게시간 세부 a~c + 예시)를 확인 후 그대로 옮김 — 요약이 아니라 원문 그대로.
- **구현**: `PolicyDocument.kt`(PDF 복사+FileProvider 인텐트 유틸) 삭제, `PolicyDocumentScreen.kt` 신설 — 문서 내용을 `(들여쓰기 단계, 텍스트, 굵게 여부)` 리스트(`POLICY_LINES`)로 하드코딩하고, 각 줄을 `Modifier.padding(start = indent*16.dp)`로 들여써서 원본의 1./A./i./a./예) 계층 구조를 그대로 재현. 새 문자열 리소스나 별도 파싱 로직 없이 Compose Text 나열만으로 충분해서 가장 단순한 방식 선택.
- **네비게이션**: `MainActivity.kt`의 `NavHost`에 `"policy"` 라우트 추가, `SettingsScreen`에 `onOpenPolicyDocument` 콜백 파라미터를 새로 받아 버튼 클릭 시 `navController.navigate("policy")` 호출하도록 변경(기존엔 `context`를 받아 `openPolicyDocument(context)` 직접 호출).
- **죽은 코드/리소스 정리**: PDF 뷰어 전용이었던 `FileProvider` `<provider>` 선언(`AndroidManifest.xml`)과 `res/xml/file_paths.xml`, 번들된 PDF 원본(`app/src/main/assets/가산연구소_운영방안_20220923.pdf`)을 전부 삭제 — 이제 이 세 가지를 참조하는 코드가 하나도 안 남아서 완전히 죽은 리소스가 되기 때문(`grep`으로 다른 참조 없음을 먼저 확인 후 삭제). `doc/` 안의 원본 PDF는 여전히 건드리지 않음(참고용 원본, 계속 범위 밖 취급).
- **실기기 검증**: adb로 설정 화면 → "가산 연구소 운영 방안 보기" 버튼 → 새 인앱 화면이 뜨는 것 확인. 스크롤해서 상단(제목/날짜/1. 식대 지원)부터 하단(2. 자율출퇴근제 운영의 A~E, i~ix, a~c, 예시 문구)까지 전체가 원본과 동일한 내용·들여쓰기로 표시되는 것 확인.
- **좌표 실수 메모**: 이번 검증 중 "가산 연구소 운영 방안 보기" 버튼 좌표를 스크린샷에서 두 번 잘못 짚어서(첫 화면요소 없이 눈대중으로 y좌표를 추정하다가 위쪽 "점심시간" 텍스트필드를 두 번 잘못 탭해 키보드가 뜸) 헛돌았음 — 카드가 여러 개 쌓인 화면에서 특정 버튼 좌표를 추정할 땐, 화면 전체를 아이콘/제목/설명/입력창 블록 단위로 순서대로 세어가며 y좌표 범위를 짚어야지, 인접한 카드와 헷갈리지 않도록 매번 스크린샷을 다시 확인하고 계산할 것(이전 [[reference_adb_gitbash_gotchas]]의 "표시좌표×1.56" 공식은 맞았지만, 애초에 어떤 요소를 짚는지 자체를 헷갈렸던 케이스).

## 2026-07-15: "가산 연구소 운영 방안 보기"를 다시 손질 — 텍스트 재입력 대신 PDF를 이미지로 직접 렌더링

- **사용자 지시**: 바로 직전 라운드에서 문서 내용을 Compose Text로 옮겨 쳐서 보여주게 했는데, 사용자가 이어서 "가산 연구소 운영 방안 보기는 그냥 이미지로 이 앱에서 바로 보이게 하자"라고 정정 — 텍스트를 다시 타이핑해 재현하는 대신 원본 문서 자체를 이미지로 그대로 보여달라는 의도였음. (참고: 그 이전엔 외부 PDF 뷰어에 위임하는 방식이었다가, "텍스트 내용이니 앱에서 보이게"→"이미지로 앱에서 보이게"로 두 단계에 걸쳐 요구사항이 좁혀짐.)
- **변환 도구 부재**: PDF→이미지 변환을 개발 머신에서 미리 해두려 했으나 이 PC엔 ImageMagick(`magick.exe`, [[reference_windows_convert_not_imagemagick]] 참고)도 `pdftoppm`/Ghostscript도 없고 `python`도 실제로 설치돼 있지 않음(WindowsApps 별칭만 있어 실행하면 스토어 설치 유도) — 사전 변환 없이 갈 방법을 찾아야 했음.
- **채택한 방식**: 안드로이드 플랫폼이 API 21부터 기본 제공하는 `android.graphics.pdf.PdfRenderer`를 앱 런타임에서 직접 사용 — 개발 머신에서 변환할 필요 자체가 없어짐. `PolicyDocumentScreen.kt`를 다시 작성: (1) 번들된 asset PDF를 캐시 파일로 복사(PdfRenderer는 실제 파일의 `ParcelFileDescriptor`가 필요, asset 스트림은 안 됨 — 이전 FileProvider 버전과 같은 이유), (2) `PdfRenderer(pfd).openPage(0)`으로 첫 페이지를 열어 `page.width/height`(포인트 단위, 대략 72dpi급이라 그대로 그리면 흐릿함)의 3배 크기 `Bitmap`(`ARGB_8888`, 흰 배경으로 미리 채움 — PDF 렌더링 자체는 투명 배경이라 안 채우면 다크 테마에서 텍스트가 배경과 섞여 안 보일 뻔함)에 `RENDER_MODE_FOR_DISPLAY`로 렌더링, (3) `LaunchedEffect` + `Dispatchers.IO`로 백그라운드에서 렌더링(비트맵 생성이 무거운 동기 작업이라 메인 스레드에서 하면 안 됨) 후 `Image(bitmap.asImageBitmap())`로 표시.
- **레이아웃 함정**: 처음에 `Image`에 `fillMaxSize()`와 `verticalScroll()`과 `fillMaxWidth()+aspectRatio()`를 전부 같이 걸었더니, `fillMaxSize()`가 부모(뷰포트) 높이에 맞춰 이미지 높이를 고정시켜버려서 실제로는 스크롤할 내용이 생기지 않는 문제가 있었음(스크롤 가능하려면 자식의 "고유 높이"가 뷰포트보다 커야 하는데 `fillMaxSize`가 그걸 뷰포트 높이로 못박아버림) — `verticalScroll`을 이미지를 감싸는 바깥 `Box`로 옮기고, `Image`엔 `fillMaxWidth()+aspectRatio(bitmap.width/height)`만 남겨서 이미지 자체 종횡비대로 세로로 길어지고 그 길이만큼 스크롤되게 고침.
- **자산 복원**: 직전 라운드에서 "이제 텍스트로 보여주니 필요 없다"고 판단해 지웠던 `app/src/main/assets/가산연구소_운영방안_20220923.pdf`를 다시 `doc/`에서 복사해 되살림(원본은 여전히 `doc/`에 그대로 있어서 문제 없었음) — **교훈**: "이제 이 리소스는 죽은 코드다"라고 판단해서 지우는 결정은, 사용자의 다음 요청 한 번에 정반대로 뒤집힐 수 있으니 원본 소스(`doc/`)를 절대 손대지 않고 남겨두는 지금 방식이 이런 왕복에도 안전망이 됨.
- **매니페스트**: FileProvider `<provider>` 선언은 이번엔 되살리지 않음 — `PdfRenderer`는 앱 자신의 파일 디스크립터만 쓰고 외부 앱에 콘텐츠 URI를 공유할 필요가 없어서 FileProvider 자체가 애초에 불필요(외부 뷰어로 넘기던 옛 방식에서만 필요했던 인프라).
- **실기기 검증**: adb로 설정 → 가산 연구소 운영 방안 보기 버튼 클릭 → 원본 PDF 페이지가 흰 배경에 선명하게(스캔본이 아니라 실제 렌더링이라 텍스트가 또렷함) 그대로 뜨는 것 확인. 이번엔 debug 서명이 이전 설치와 동일해 `adb install -r`만으로 앱 데이터(이번주 근무 기록 등)가 보존된 채 확인 가능했음(직전 릴리즈 서명 테스트 때와 달리 데이터 손실 없었음).
- **How to apply**: 이 문서 열람 기능은 세 번째 방향 전환(PDF 외부 뷰어 → 텍스트 재입력 → PDF 이미지 렌더링)이었음 — "그냥 보여주자"류의 짧은 요청은 표현 방식(뷰어 위임/텍스트/이미지)에 대한 암묵적 선호가 숨어있을 수 있으니, 구현 방향을 바꿀 때마다 이전 방식의 부산물(FileProvider, 리소스 파일 등)이 완전히 죽었는지 매번 grep으로 재확인하고 정리할 것.

## 2026-07-15: 근무 인정 시간 하한(07:00) 적용 — 그 전 출근은 07:00부터 인정

- **사용자 지시**: "그래프의 출근 인정시간으 7시부터 이다. 그전에 와도 인정은 7시 부터 처리 하게하고 그래프로 그렇게 표시." 가산 연구소 운영 방안의 "근무 인정 시간 07:00~22:00"(README에 "아직 미구현"으로 남아있던 항목) 중 하한만 이번에 구현 — 07:00 이전 출근분은 근무시간 계산에서도, 그래프 막대 위치에서도 반영되지 않고 07:00부터로 잘려서 처리되게 함.
- **구현 범위 판단**: 정책 문서의 "출근 인정 시간 07:00~13:00"(상한 포함, 13시 이후 출근은 다르게 처리)이나 22:00 상한 클램핑까지는 요청에 없어서 손대지 않음 — 이번엔 "07:00 하한 클램프"만 정확히 구현(스코프 과잉 확장 금지 원칙 유지).
- **구현 위치**: `data/WorkStats.kt`의 `computeDailyWorkStats`에만 적용 — `WORK_RECOGNITION_START_MINUTE = 7*60` 상수 추가, `closeSession`에서 `recognizedStart = maxOf(sessionStart, 그날의 07:00 타임스탬프)`를 계산해 **근무시간(`workedMinutes`/`rawSpanMinutes`) 계산과 `firstArriveAt`(그래프 막대 시작 위치) 모두에 이 클램프된 값을 사용**. 세션 전체가 07:00 이전에 끝나버리는 극단적 경우(예: 새벽 5~6시에 출퇴근 완료)는 `recognizedStart >= sessionEnd`로 감지해 그 세션은 통째로 0분 처리(가입/이탈 시각도 그 날 통계에 반영 안 함) — 흔치 않은 엣지케이스라 최소 구현으로 처리.
- **원본 이벤트는 그대로**: `WifiMonitorService`가 실시간으로 기록하는 실제 ARRIVE 타임스탬프(예: 06:00)는 전혀 건드리지 않음 — `DayDetailDialog`는 여전히 원본 이벤트에서 직접 "출근" 시각을 읽어와 표시하므로(이 다이얼로그는 `DailyWorkStat`이 아니라 `dayEvents`를 직접 씀) 사용자는 실제 출근 시각과 "근무 인정"된 시간을 둘 다 볼 수 있음 — 투명성 유지. 점심시간 클램프([[project-commute-absence-threshold-implementation]] 계열)를 구현했을 때와 동일한 패턴(데이터 계산 계층에서만 클램프, 원본 기록/표시는 안 건드림).
- **그래프 자동 반영**: `WeeklyRangeChart`는 `stat.firstArriveAt`을 막대 시작 위치로 쓰므로, `WorkStats.kt`에서 클램프한 값이 그대로 흘러들어가 별도 그래프 코드 수정 없이 "막대가 07:00보다 아래로 안 내려가는" 동작이 자동으로 나옴 — 점심시간 표시 때와 마찬가지로 "데이터 계층에서 고치면 그래프는 따라온다"는 이 프로젝트의 반복되는 설계 패턴.
- **실기기 검증**: 이번주 월요일(데이터 없던 날)에 테스트용 출근 06:00 + 퇴근 09:00 기록을 "빠진 기록 추가"로 넣어서 확인 — 다이얼로그엔 "출근 06:00 / 퇴근 09:00"이 그대로 뜨지만 "근무시간"은 **"2시간 0분"**(3시간이 아니라 07:00~09:00만 인정)으로 정확히 계산됨. 그래프에서도 월요일 막대가 06:00이 아니라 정확히 07:00 그리드라인에서 시작하는 것을 스크린샷으로 확인. 검증 후 테스트 기록 두 건(출근/퇴근)은 두 번 누르기 삭제로 깨끗이 제거해 원래 데이터 상태로 복원.
- **좌표 계산 실수 재발**: 이번에도 "이 날짜에 빠진 기록 추가" 링크 좌표를 처음에 배율(×1.56) 곱하는 걸 깜빡하고 화면에 보이는 픽셀값을 그대로 tap에 써서 헛눌렀음 — 스크린샷에서 좌표를 읽을 때마다 "이 값은 표시 좌표(923×2000)다, tap에 쓰려면 반드시 ×1.56"이라고 매번 되뇔 것(반복되는 실수이니 다음에도 주의).

## 2026-07-15: 앱 삭제/재설치에도 살아남는 JSON 백업/복원 기능 추가

- **사용자 지시**: "데이터는 앱을 지우고 다시 설치해도 보관되게 별도의 저장소에 관리하게하거나 db처리 하는게 맞아." 이 세션 내내 서명이 다른 APK로 갈아끼우느라 `adb uninstall`을 반복하면서 테스트 데이터가 여러 번 날아간 것([[project-gasan-labor-policy]] 계열의 릴리즈 서명 라운드에서 처음 겪음)이 배경 — 근본적으로 Room DB/DataStore가 앱 전용 저장소에만 있어서 생기는 문제.
- **왜 자동 백업(Auto Backup)이 아니라 수동 내보내기/가져오기인지**: 매니페스트에 이미 `allowBackup="true"`가 있어 안드로이드 기본 전체 백업이 어느 정도 커버하긴 하지만, 이건 기기가 유휴+충전+와이파이 상태일 때만, 하루 단위로, Google 계정 로그인 전제하에 동작하고 `adb uninstall`+재설치 같은 테스트 흐름에서는 사실상 작동 안 함(신뢰 불가, 즉시 검증 불가). targetSdk 36의 scoped storage 제약상 DB 파일을 직접 `/sdcard/`에 두는 것도 `MANAGE_EXTERNAL_STORAGE` 없이는 불가능. 그래서 **SAF(Storage Access Framework)로 사용자가 직접 저장 위치를 고르는 명시적 JSON 내보내기/가져오기**를 택함 — 위험한 권한 추가 없이 "별도의 저장소에 관리"라는 요청을 가장 직접적으로 만족.
- **백업 대상**: `CommuteEvent` 전체 이력 + 지속 설정(companySsid, monitoringEnabled, absenceThresholdMinutes, lunchStartMinute, lunchEndMinute). **일부러 안 넣은 것**: `isAtWork`/`lastSeenAt`/`awaySinceAt` 같은 휘발성 서비스 상태 — 복원 후 최대 한 번의 폴링 주기(1분) 안에 `WifiMonitorService`가 실제 와이파이 감지로 다시 정확히 계산하므로 백업에 넣을 필요가 없다고 판단(단순함 우선).
- **구현**: `data/BackupData.kt`(신규) — `buildBackupJson`/`parseBackupJson` 순수 함수, `org.json`(안드로이드 내장, 의존성 추가 불필요)으로 JSON 직렬화. `CommuteDao`에 `insertAll`/`deleteAll`/`getAllOnce` 추가. `CommuteViewModel.exportBackup(uri)`/`importBackup(uri)`가 SAF `Uri`의 `contentResolver.openOutputStream/openInputStream`으로 파일을 직접 읽고 쓰고, 성공/실패를 `Toast`로 바로 알림(이 앱의 기존 관례처럼 별도 이벤트 버스 없이 ViewModel이 직접 Toast). `SettingsScreen.kt`에 "데이터 백업" 카드 신설 — "백업 저장"(`ActivityResultContracts.CreateDocument`)과 "백업 복원"(`ActivityResultContracts.OpenDocument`) 버튼.
- **실제로 잡은 버그**: `JSONObject.put(key, null)`은 안드로이드 org.json 구현에서 **키를 아예 지워버림**(값을 JSON `null`로 안 남김) — 그래서 처음 내보낸 백업 파일에 `companySsid` 키 자체가 통째로 빠져있었음(값이 null이었을 때). `put(key, value ?: JSONObject.NULL)`로 명시적 null을 쓰도록 고치고, 읽는 쪽도 `optString` 대신 `isNull(key)`(키 없음/JSON null 둘 다 true 반환)로 먼저 체크하도록 통일. **교훈**: org.json으로 nullable 필드를 다룰 때 `put(key, kotlinNullableValue)`를 그냥 쓰면 안 되고 항상 `JSONObject.NULL`을 명시할 것.
- **테스트 중 발견한 별개 이슈**: 이 라운드 초반에 앱의 위치 권한이 어느샌가 풀려 있었고(`companySsid`/`monitoringEnabled`가 초기값으로 리셋된 상태로 첫 백업이 떠서 즉시 발견) — 이전 세션의 서명 교체 테스트들 중 한 번의 재설치에서 잃어버린 것으로 추정. 백업 기능 버그가 아니라 테스트 환경 문제였고, 위치 권한을 재부여하고 회사 와이파이(iptime5G)를 재등록해 실제 상태로 복구한 뒤 재검증함.
- **실기기 검증(완전한 왕복)**: ① 정상 상태에서 "백업 저장" → SAF "다운로드" 폴더에 `commute_backup_20260715_1451.json` 저장, 파일 내용에 `companySsid: "iptime5G"`/`monitoringEnabled: true`/이벤트 3건 모두 정확히 포함 확인. ② `adb shell pm clear com.commute.app`로 앱 데이터 전체 삭제(재설치 없이 "삭제 후 재설치"와 동일한 초기화 상태 재현 — uninstall보다 가벼워서 이후 서명 문제를 피할 수 있는 테스트 방법으로 채택). ③ 재실행 → 근무시간 0분/와이파이 미등록 확인(완전 초기화 확인). ④ 설정 → "백업 복원" → 방금 저장한 파일 선택 → "복원 완료 (3건)" 토스트 → 홈 화면에서 회사 와이파이(iptime5G)·모니터링 스위치(ON)·이번주 근무시간(6시간 43분)·그래프 막대까지 전부 정확히 되살아난 것 확인. 위치/알림 권한은 `pm clear`가 권한도 초기화하므로 별도로 재승인(백업 기능과 무관, 예상된 동작).
- **좌표 헌팅 방식 전환**: 이번 라운드에서도 스크린샷 눈대중 좌표가 두 번 빗나갔음(설정 화면을 스크롤한 뒤 좌표 재계산을 깜빡함, 정책 문서 보기 버튼을 실수로 또 누름). 이후로는 `adb shell uiautomator dump`로 XML을 뽑아 `bounds="[x1,y1][x2,y2]"`를 grep해서 정확한 실제 좌표(배율 계산 불필요, 이미 실기기 픽셀)를 구하는 방식으로 전환 — 훨씬 안정적이었음. **다음부터는 설정 화면처럼 버튼이 여러 개 쌓인 화면에서 특정 버튼을 눌러야 할 때, 스크린샷 눈대중보다 `uiautomator dump` + grep을 먼저 시도할 것.**

## 2026-07-15: 그래프 Y축 시작을 06:00 → 07:00으로 변경

- **사용자 지시**: "그래프가 6시부터 표시되는데 아예 7시부터 표시하는게 좋을 듯한데." 바로 앞 라운드에서 근무 인정 시간 하한(07:00)을 구현해 07:00 이전 도착은 막대에도 절대 안 나타나게 됐으므로, Y축이 06:00부터 시작하면 06:00~07:00 구간은 항상 비어 있는 죽은 공간이 됨 — 그 공간을 없애고 07:00부터 꽉 채워 보여달라는 후속 요청.
- **구현**: `HomeTabs.kt`의 `CHART_START_MINUTE`을 `6*60`→`7*60`으로 한 줄 변경. 2시간 간격 그리드라인 로직은 그대로 두되, 범위가 07:00~22:00(15시간, 2시간으로 안 나누어떨어짐)이 되면서 그리드라인이 07,09,11,13,15,17,19,21로 찍히고 22:00 정각에는 선이 안 그려짐(15/2=7.5라 마지막 칸이 21:00에서 끝남) — 사용자가 "아예 7시부터"라고만 했지 눈금 정렬까지 요구한 게 아니라서, 22:00 눈금 하나 없는 정도의 사소한 비대칭은 손대지 않고 그대로 둠(과잉 엔지니어링 지양).
- **자동 정합성**: 막대 위치 계산(`yFor`)과 근무 인정 하한 클램프(`WorkStats.kt`의 `WORK_RECOGNITION_START_MINUTE=7*60`)가 이미 같은 07:00 기준을 쓰고 있어서, 차트 시작점만 맞춰주면 "막대가 축 맨 아래에서 시작"하는 모양이 자연스럽게 나옴 — 두 상수가 우연히 값은 같지만 코드상 완전히 독립적이라는 점은 07:00 클램프 구현 때 이미 기록해둠([[project-gasan-labor-policy]] 계열 최근 항목 참고).
- **실기기 검증**: adb 스크린샷으로 확인 — Y축이 07:00부터 21:00까지 8개 그리드라인으로 꽉 차게 표시되고, 수요일 막대가 축 맨 아래(07:00 라인)에서 시작하는 것을 확인.

## 2026-07-15: 랄프루프 5회 재점검 (직전 라운드부터 쌓인 12개 커밋 전체 대상, 새 결함 없음)

- **사용자 지시**: "다시 랄프 루프 구동해 코드 수정 사항 기능 구현사항등의 모두 점검해서 문제 없는 지 점검 해줘." 직전 랄프루프(6569305, 점심 표시+토글 구현 직전)부터 이번 요청 시점까지 12개 커밋(점심 그래프 오버레이+토글, TRide 서명, 정책 문서 텍스트→이미지 전환, 07:00 근무 인정 클램프, JSON 백업/복원, 차트 Y축 07:00 시작)이 쌓인 상태를 다시 감사.
- **Round 1**: `WorkStats.kt`(07:00 클램프), `HomeTabs.kt`(점심 오버레이+토글+차트 시작점), `CommuteViewModel.kt`(백업 export/import), `SettingsScreen.kt`(백업 카드), `BackupData.kt`, `CommuteDao.kt`, `PolicyDocumentScreen.kt`, `MainActivity.kt` 관련 부분, `AndroidManifest.xml`을 전체 다시 정독. 발견한 것: (1) `recognizedStart >= sessionEnd`일 때(세션 전체가 07:00 이전) 그 날짜의 `DayAccum`을 아예 안 만드는 게 맞는지 재검토 → 그래프/다이얼로그 모두 "0분"으로 정확히 처리되는 걸 로직으로 확인, 문제 없음. (2) `importBackup`이 권한 미승인 상태에서도 `setMonitoringEnabled(true)`를 그대로 호출해 `WifiMonitorService.start()`를 트리거하는 경로 재검토 → 이미 이전 라운드(백업 기능 구현 시)에 `pm clear`로 권한 없는 상태에서 실제로 이 경로를 밟아봤고 크래시 없이 정상 동작한 걸 확인한 바 있음(기존 `stopSelf()` 가드가 이 경로도 커버). 실제 코드 결함은 못 찾음.
- **Round 2**: 최종 커밋 상태로 빌드한 앱을 실기기에 재설치해 전체 플로우 재점검 — 현황 탭(오늘 근무시간 토글, 막대 탭→다이얼로그, 좌우 스와이프→이전 주→"이번주로" 복귀), 기록 탭(이번주 이벤트 목록), 설정 탭(자리비움/점심시간/근거문서/데이터백업 카드 4개 전부 정상 렌더) 순서로 확인. 회귀 없음.
- **Round 3**: 이번 세션에서 안 건드린 파일들(`WifiMonitorService.kt`, `WifiUtils.kt`, `SettingsRepository.kt`, `CommuteEvent.kt`, `CommuteDatabase.kt`, `TimeOfDay.kt`)이 실제로 직전 랄프루프 커밋 이후 diff가 0인지 `git diff <이전 랄프루프 커밋> HEAD -- <경로>`로 재확인(그렙으로 "이석"/"openPolicyDocument"/"FileProvider" 등 죽은 참조도 재검색) — 전부 깨끗함.
- **Round 4**: `gradlew assembleRelease`로 릴리즈 서명 빌드가 최신 코드와도 여전히 문제없이 되는지 확인 + `apksigner verify`로 TRide 인증서(SHA-256 `3c57b1f2...41ea`) 서명이 그대로 유지되는지 재확인.
- **Round 5**: `gradlew clean assembleDebug` 전체 회귀 빌드(40/40 태스크, BUILD SUCCESSFUL) → 재설치 → 재실행 → logcat에 크래시 없음, `Displayed com.commute.app/.MainActivity` 정상 확인.
- **결과**: 이번 라운드는 새로 고친 코드가 없음(`git status`가 감사 시작 전과 끝난 후 동일하게 깨끗) — 직전 몇 라운드 동안 이미 여러 번 자체 검증하며 작업해온 덕에 이번엔 순수 확인용 감사로 마무리됨. **How to apply**: 매번 랄프루프를 새로 돌릴 때 직전 랄프루프 커밋 해시를 찾아 `git diff <prev> HEAD --stat`으로 범위를 먼저 파악하면 어디까지 다시 봐야 하는지 명확해짐 — 이번처럼 즉석에서 그 커밋을 찾아쓰는 방식이 효율적이었음.

## 2026-07-15: 랄프루프 통과 후 최신 코드로 서명된 릴리즈 APK 재빌드

- **사용자 지시**: "좋아 그럼 서명된것을 release용 apk를 만들어놔." 랄프루프 재점검이 끝난 시점의 최신 코드(점심 오버레이/토글, 07:00 클램프+차트 시작점, JSON 백업/복원 등 전부 포함)로 배포용 릴리즈 APK를 다시 만들어달라는 요청 — TRide와 같은 서명 키 재사용 설정([[project-gasan-labor-policy]] 최근 항목 중 "TRide와 동일한 인증서로 릴리즈 APK 서명 구성" 참고)은 이미 돼 있어서 새 설정 없이 그냥 재빌드만 하면 됨.
- **실행**: `gradlew assembleRelease` 재실행 → `app/build/outputs/apk/release/app-release.apk` 생성(52/52 태스크 성공). `apksigner verify --print-certs`로 서명 인증서가 여전히 TRide 것(SHA-256 `3c57b1f2...41ea`)과 일치하는 것 재확인 — `keystore.properties`/`app/tride-release.jks`가 그대로 있어서 별도 설정 없이 바로 서명됨.
- **결과물 위치**: 별도 폴더로 복사하지 않고 Gradle 표준 출력 경로(`app/build/outputs/apk/release/app-release.apk`)에 그대로 둠 — README의 "릴리즈 서명" 절에 이미 이 경로가 문서화돼 있어서 새 배포 스크립트나 위치 규칙을 만들 필요가 없다고 판단.

## 2026-07-15: 그래프 크래시 루프 버그 수정 (`Cannot coerce value to an empty range`) — 랄프루프가 놓친 실제 버그

- **사용자 지시**: "앱이 켜두면 죽는거 같아 확인해봐 adb로 디버깅 확인." 직전 랄프루프 5회 재점검(575ed6b)에서 "새 결함 없음"으로 결론 냈던 바로 그 코드에 실제로 살아있는 크래시 루프가 있었음 — **정적 리뷰만으로는 놓쳤고, 사용자가 실제 사용 중 체감한 증상을 실기기 logcat으로 재현·확인해서야 발견**.
- **증상 재현**: `adb shell dumpsys activity services com.commute.app`가 "(nothing)"을 반환(포그라운드 서비스가 죽어있음)했는데 앱 프로세스 자체는 살아있어서 처음엔 배터리 최적화/Doze로 서비스만 죽었나 의심했음(`dumpsys deviceidle whitelist`에 `com.commute.app`이 없어서 배터리 최적화 대상인 것도 사실이지만, 이건 원인이 아니었음). `adb logcat -d -s AndroidRuntime:E ActivityManager:W | grep commute`로 훑어보니 **15~25초 간격으로 반복되는 진짜 크래시 루프**가 잡힘.
- **정확한 원인**: `java.lang.IllegalArgumentException: Cannot coerce value to an empty range: maximum -60.0 is less than minimum 0.0` at `HomeTabsKt.WeeklyRangeChart$lambda...(HomeTabs.kt:312)`. 그래프의 시각 그리드라인 라벨을 그리는 `drawText(measured, topLeft = Offset(0f, (y - measured.size.height / 2f).coerceIn(0f, size.height - measured.size.height)))`에서, **Canvas의 실제 렌더 높이(`size.height`)가 라벨 텍스트 높이(`measured.size.height`)보다 작아지는 순간**(예: 앱 실행 직후 전환 애니메이션 중 윈도우가 작은 크기로 잠깐 측정되는 프레임) `size.height - measured.size.height`가 음수가 되어 `coerceIn(0f, 음수)`가 "빈 범위" 예외를 던짐. 앱이 포그라운드 액티비티 크래시로 죽으면 시스템이 같은 태스크를 자동 재시작하고, 재시작 직후 또 같은 전환 애니메이션 프레임에서 또 죽는 식으로 무한 루프 — 사용자 입장에서는 "앱을 켜두면 계속 죽는다"로 느껴짐.
- **수정**: `(size.height - measured.size.height).coerceAtLeast(0f)`로 상한을 먼저 0 이상으로 클램프한 뒤 `coerceIn(0f, maxLabelY)`에 넘기도록 변경 — 범위가 항상 유효하게 보장됨. 같은 파일의 다른 `coerceIn` 호출 3곳(요일 인덱스, 분 단위 클램프)은 상수 기반이라 이 문제가 구조적으로 발생할 수 없음을 확인, 손대지 않음.
- **실기기 검증**: 수정 전 앱을 8번 연속 강제종료→재실행 했을 때 매번 예외 없이(원래는 매번 크래시했을 시나리오) 정상 기동 확인, 추가로 90초간 유휴 상태로 두고 logcat 감시해도 크래시 없음 확인.
- **How to apply — 랄프루프의 한계**: 이번 건은 정적 코드 리뷰(랄프루프 Round 1~5)로는 못 찾은 버그였음 — `coerceIn(0f, a - b)`처럼 두 값의 차가 음수일 수 있는 범위 계산은, 코드만 읽어서는 "a가 항상 b보다 크다"는 암묵적 가정이 맞는지 확신하기 어렵고, 실제로 그 가정이 깨지는 경우(전환 애니메이션 중 레이아웃 크기 등)는 실기기에서 크래시가 나봐야 알 수 있었음. **앞으로 `coerceIn(lower, upper)`에서 `upper`가 상수가 아니라 계산식이면, 그 계산식이 음수가 될 수 있는지 항상 의심하고 `coerceAtLeast(lower)`로 방어할 것** — 정적 리뷰만 믿지 말고, 사용자가 "이상하다"고 보고하면 반드시 실기기 logcat부터 확인.

## 2026-07-15: 기록 드래그 순서 조절 기능을 구현 직후 다시 제거 — 시각 직접 지정이면 충분

- **경위**: 직전 라운드(API 서버 오류로 응답이 중간에 끊겼던 라운드)에서 사용자 요청("드랍다운/피커로 날짜·시각 입력" + "리스트 항목 드래그로 순서 조절" + "출퇴근 기록 누락 감지")에 맞춰 세 가지를 한번에 구현: (1) `EditEventDialog`를 텍스트 입력에서 안드로이드 기본 `DatePicker`/`TimePicker`로 전환, (2) `HomeTabs.kt`에 `ReorderableEventList`(길게 눌러 드래그, 드롭 시 앞뒤 이웃의 중간값/±1분으로 시각 자동 재계산) 신설, (3) `WorkStats.kt`에 `findMissingRecords`(ARRIVE/LEAVE 짝 안 맞는 경우 탐지) + 기록 탭 상단 경고 배너 신설. 이 세 가지는 아직 커밋 전 상태였음.
- **사용자가 되짚어본 것**: 기록 추가 시 어차피 `EditEventDialog`가 항상 시각 TimePicker를 띄우고 기본값(현재 시각 또는 누락 기준 ±1시간)을 자유롭게 바꿀 수 있다는 걸 확인하고 나서, "그럼 별도 정렬도 드레그도 필요 없다"고 결론 — 리스트가 항상 시각순 자동 정렬이니, 정확한 시각을 안다면 추가할 때 바로 지정하면 되고, 순서를 맞추기 위해 드래그할 이유가 없다는 판단. (드래그는 애초에 "정확한 시각은 모르지만 순서는 안다"는 상황을 위한 기능이었는데, 실제로는 그런 상황 자체가 잘 안 생긴다고 판단한 것으로 보임.)
- **되돌린 것**: `HomeTabs.kt`에서 `ReorderableEventList` 컴포저블 전체와 관련 제스처 상태(`draggingIndex`, `dragOffsetY`, `itemHeights`, `detectDragGesturesAfterLongPress`)를 삭제하고, 원래의 단순한 `LazyColumn` + `items(events.sortedByDescending { it.timestamp })`로 복원. 안내 문구에서 "길게 눌러서 순서를 바꿀 수 있습니다" 문구 제거. README의 "기록 순서 드래그 정렬" 항목 삭제, 대신 "기록 탭 목록은 항상 시각순으로 자동 정렬되므로 추가/수정 시 원하는 시각을 직접 지정하기만 하면 됨"으로 대체. 이제 안 쓰는 import(`itemsIndexed`→`items`, `detectDragGesturesAfterLongPress`, `mutableStateMapOf`, `graphicsLayer`, `onGloballyPositioned`, `zIndex`) 정리.
- **남긴 것**: DatePicker/TimePicker 전환([[project_commute_absence_threshold_implementation]] 계열과 무관, `EditEventDialog.kt`로 파일 분리도 유지)과 기록 누락 감지+배너는 그대로 유효한 기능이라 손대지 않음 — 이번 되돌림은 오직 드래그 재정렬 기능만 대상.
- **실기기 검증**: `assembleDebug` 정상 빌드(경고/에러 없음) → 재설치 → 기록 탭 진입 시 크래시 없이 목록이 시각 내림차순(07/15 07:55 → 07/14 16:35 → ... )으로 정상 표시되는 것 스크린샷으로 확인. logcat에도 크래시 없음.
- **How to apply**: 기능을 구현하자마자(커밋 전) 사용자가 "이거 필요 없겠다"고 바로 뒤집을 수 있음 — 특히 이번처럼 여러 기능을 한 번에 요청받아 구현한 직후에는, 사용자가 완성된 기능을 보고 나서야 스스로 필요성을 재평가하는 경우가 있으니, 커밋 전이라면 미련 없이 되돌리는 게 맞음(이미 커밋된 코드였다면 새 커밋으로 되돌리는 것도 같은 원칙). [[project_gasan_labor_policy]] 계열의 "스코프 과잉 확장 금지" 원칙과 같은 맥락 — 필요 없어진 게 확인되면 남겨두지 말고 바로 제거할 것.

## 2026-07-16: WifiMonitorService가 재부팅 없이 죽으면 영영 안 살아남는 버그 발견·수정 — 앱 실행 시 watchdog으로 자가 복구

- **증상**: "어제 퇴근 인식이 오늘 출근 인식이 안되고 있다." 실기기 조사 결과 `dumpsys activity services com.commute.app`가 "(nothing)" — 포그라운드 서비스가 아예 안 떠 있었음. DB(`commute_events`)를 직접 확인해보니(sqlite3 로컬 바이너리 `C:\Program Files\NVIDIA Corporation\Nsight Compute 2024.1.0\host\target-windows-x64\sqlite3.exe`로 pull한 db 열람 — 이 머신엔 sqlite3가 PATH엔 없지만 Nsight 번들에 들어있음, 참고) 마지막 이벤트는 전날 20:12 LEAVE, 당일 ARRIVE는 없음. 하지만 실제로는 그 순간 회사 와이파이(iptime5G)에 연결되어 있었음(`dumpsys connectivity`로 확인) — 서비스가 죽어서 감지를 아예 못 하고 있던 것.
- **근본 원인**: `WifiMonitorService.start()`를 호출하는 코드 경로가 딱 두 곳뿐이었음 — (1) 설정 화면에서 모니터링 스위치를 켤 때, (2) `BootReceiver`(기기 재부팅 시). 삼성 기기의 공격적인 백그라운드 프로세스 관리("절전 앱"/딥 슬립 등)가 포그라운드 서비스를 재부팅 없이 죽이면(추정 — 로그캣 버퍼가 밤새 로테이션되어 실제 kill 로그 자체는 못 찾음, 하지만 START_STICKY로 선언돼 있어도 이런 OEM 킬은 시스템 표준 재시작을 우회하는 경우가 흔함), 앱을 그냥 열어보는 것만으로는 서비스가 재시작되지 않는 구조적 공백이 있었음.
- **수정**: `MainActivity.kt`의 `CommuteScreen`에 이미 있던 3초 주기 `LaunchedEffect` 폴링 루프(현재 와이파이 감지 상태 갱신용) 안에 `if (monitoringEnabled && hasLocationPermission) WifiMonitorService.start(context)` 한 줄 추가 — `onStartCommand`가 `monitorJob?.isActive != true`일 때만 새 폴링 잡을 만들고 이미 떠 있으면 그냥 통과하는 구조라 멱등(idempotent)이라, 앱이 열려 있는 동안 3초마다 "혹시 죽어있으면 바로 되살리는" watchdog으로 안전하게 쓸 수 있음.
- **실기기 검증**: `assembleDebug`+`installDebug` 후 `am force-stop`으로 완전히 죽인 뒤 재실행 → `dumpsys activity services`에 `WifiMonitorService`가 즉시 다시 떠 있는 것 확인. 60초 폴링 주기를 기다린 뒤 DB를 다시 열람해 당일 ARRIVE가 정상적으로 새로 기록되는 것까지 확인.
- **검증 중 발견한 2차 버그(데이터 하나 오염시킴, 직접 복구함)**: watchdog이 서비스를 되살리자마자, `WifiMonitorService`의 "날짜 경계 자동 마감" 로직(전날부터 `isAtWork=true`로 남아있고 `lastSeenAt`이 오늘이 아니면 그 시각으로 LEAVE를 강제 기록)이 발동해서, 이미 사용자가 수동으로("빠진 기록 추가" 기능으로, 분 단위 정각 20:12:00인 걸로 미루어 수동 입력이 거의 확실) 채워 넣은 전날 LEAVE(20:12:00)와 별개로 중복 LEAVE(17:50:08)를 하나 더 만들어버림. **원인**: `CommuteViewModel.addEvent`(수동 기록 추가/수정)가 DB에 이벤트만 넣을 뿐, `SettingsRepository`의 `isAtWork`/`lastSeenAt`/`awaySinceAt`(서비스가 폴링마다 참조하는 별도 상태)는 전혀 갱신하지 않음 — 수동으로 기록을 고쳐도 서비스 내부 상태와 events 테이블이 서로 몰라서 어긋날 수 있는 구조적 위험. **이번엔 손대지 않음**(워치독 버그 수정과는 별개의 이슈라 스코프 분리, 사용자에게 별도 보고 예정) — 다만 이 어긋남 자체가 실제로 터진 걸 목격했으므로 다음에 관련 요청이 오면 [[project_commute_wifi_scan_detection]] 계열 참고해서 우선 처리할 것.
- **오염 데이터 복구 방법(재사용 가능한 절차)**: 기기가 화면 잠금 상태라 UI로 삭제 조작이 불가능했음 — ①`adb shell run-as com.commute.app cp databases/commute.db(-wal)`로 로컬에 pull, ②로컬 sqlite3로 `DELETE ... ; PRAGMA wal_checkpoint(FULL);`, ③**주의**: 이 기기는 앱이 `/sdcard`를 직접 읽을 권한이 없어(scoped storage) `run-as ... cp /sdcard/... databases/commute.db`가 "Permission denied"로 실패함 — 대신 로컬 파일을 `base64 -w0`로 인코딩해 `adb shell "run-as com.commute.app sh -c 'base64 -d > databases/commute.db'" < file.b64`로 stdin을 통해 스트리밍하면 앱 프로세스가 sdcard를 거치지 않고 직접 받을 수 있어 우회됨(일반 `adb shell ... < binfile`로 바로 흘려보내는 건 이 환경에서 텍스트 모드 변환으로 파일이 깨짐 — base64를 거쳐야 안전). 마지막으로 `commute.db-wal`/`-shm`을 지워 이전 WAL과 새 db가 안 섞이게 함.
- **How to apply**: (1) `START_STICKY`나 `BootReceiver`만 믿지 말 것 — 삼성 계열 기기는 재부팅 없이도 포그라운드 서비스를 죽일 수 있고, 그 경우 이 앱처럼 "앱을 열면 뭔가 갱신되는 화면"이 있다면 거기에 "죽어있으면 되살리기" watchdog을 넣는 게 가장 저렴하고 확실한 안전망. (2) 이 기기에서 앱 전용 DB를 직접 만지려면 `run-as`가 `/sdcard`를 못 읽는 scoped storage 제약을 항상 먼저 의심하고, 안 되면 base64+stdin 스트리밍으로 우회할 것. (3) 서비스 내부 상태(`isAtWork` 등)와 사용자가 직접 편집하는 이벤트 테이블이 별도로 존재하는 한, 수동 편집 후 서비스가 재시작되면 항상 이런 종류의 불일치가 재발할 수 있다는 걸 염두에 둘 것.
- **후속 조정(같은 날)**: 사용자가 "폴링을 3초는 빠르다, 30초 해도 빠른데, 30초 이상 주기로 검출해도 출퇴근 기록에 큰 영향 없다"고 피드백 — 위 watchdog을 넣은 `LaunchedEffect` 루프(원래 UI용 와이파이 감지 상태 갱신 목적으로 3초 주기였던 것)의 `delay(3_000)`를 `delay(60_000)`로 변경, `WifiMonitorService` 자체의 감지 주기(`CHECK_INTERVAL_MS = 60_000L`)와 동일하게 맞춤. **Why**: 사용자는 배터리/성능 관점에서 3초·30초 모두 과하다고 느꼈고, 실제 출퇴근 감지 정확도는 어차피 서비스의 60초 주기가 좌우하므로 UI 갱신·watchdog 체크를 그보다 더 자주 할 이유가 없다고 판단. **How to apply**: 이 루프가 늦춰지면 상태 카드(근무중/자리비움 등 실시간 라벨)와 watchdog 체크 모두 최대 60초 지연될 수 있음 — 사용자가 이 트레이드오프를 명시적으로 수용했으므로 이후 "상태 갱신이 느리다"는 피드백이 오면 이 결정을 먼저 참고할 것. 실기기에서 `assembleDebug`+`installDebug` 후 강제종료→재실행으로 watchdog이 여전히 즉시 서비스를 되살리는 것 확인(첫 tick은 loop 시작 즉시 실행되므로 지연 값과 무관).

## 2026-07-16: 배터리 최적화 예외 요청을 앱 최초 실행 시 자동으로 띄우도록 추가

- **사용자 지시**: "앱을 죽여도 background로 검출을 하는 거지??"에 대해, "스와이프로 앱을 닫는 것"과 "OS/삼성이 서비스 자체를 강제로 죽이는 것"은 다르고 후자는 지금 watchdog(앱을 다시 열 때만 되살림)으로도 완전히 못 막는다고 설명하자, "절전대상에서 제외시키는 처리를 초기 실행시 하게 하는 처리를 넣어두자.... 안된 상태라면"이라고 확정 지시.
- **구현**: `AndroidManifest.xml`에 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 권한 추가. `MainActivity.kt`의 `CommuteScreen`에 `LaunchedEffect(Unit)` 하나를 새로 추가 — `PowerManager.isIgnoringBatteryOptimizations(packageName)`가 false일 때만 `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 인텐트를 `rememberLauncherForActivityResult(StartActivityForResult)`로 띄움. 이미 허용돼 있으면(재실행 때마다 이 체크가 돌지만) 조용히 통과 — "안된 상태라면"이라는 조건을 정확히 반영.
- **실기기 검증**: 설치 후 강제종료→재실행 시 시스템 다이얼로그("배터리 사용량 최적화 중지 — Commute은(는) 백그라운드에서 실행될 수 있으며, 배터리를 제한 없이 사용할 수 있습니다") 정상 노출 확인. **좌표 실수**: 첫 시도에 스크린샷을 눈대중으로 읽고 "확인" 버튼 좌표를 잘못 계산해(표시 이미지 923x2000에서 읽은 y값에 배율 1.56을 곱했는데,애초에 이미지에서 버튼 위치를 잘못 짚음) 탭했더니 다이얼로그만 닫히고 실제로는 허용이 안 된 상태였음(`dumpsys deviceidle whitelist`에 `com.commute.app` 없음, 재실행하면 다이얼로그가 또 뜸) — `uiautomator dump`로 정확한 실기기 좌표(`bounds`)를 뽑아서 다시 탭하니 `user,com.commute.app,10593`으로 화이트리스트 등록 확인. **교훈**: 시스템 다이얼로그처럼 버튼 클릭 결과를 즉시 검증할 수 있는 경우(이번처럼 `dumpsys` 커맨드로 상태 확인 가능), 스크린샷 눈대중 좌표를 쓰더라도 반드시 사후에 실제 상태 변화를 커맨드로 재확인할 것 — 다이얼로그가 닫혔다고 원하는 버튼이 눌렸다는 보장은 없음. 허용 후 재실행 시 다이얼로그가 다시 뜨지 않는 것(이미 허용된 상태라 스킵)도 확인, 크래시 없음.
- **How to apply**: 이 예외 요청은 매 실행마다 상태를 체크하지만 이미 허용된 경우 시스템이 다이얼로그를 안 띄우므로 사용자를 반복해서 귀찮게 하지 않음 — 별도의 "한 번만 물어보기" 플래그를 앱에서 직접 관리할 필요 없음. [[project_gasan_labor_policy]] 계열 문서에 이 배터리 예외 관련 안내를 추가할지는 아직 결정 안 됨.
