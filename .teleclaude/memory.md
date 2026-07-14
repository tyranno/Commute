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
