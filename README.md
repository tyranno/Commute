# Commute

회사 와이파이 연결/해제를 감지해서 출근·퇴근을 자동으로 기록하는 순수 코틀린 + Jetpack Compose 네이티브 안드로이드 앱입니다.

## 주요 기능

- **회사 와이파이 등록**: 현재 연결된 와이파이를 버튼 한 번으로 "회사 와이파이"로 등록
- **자동 감지**: 포그라운드 서비스가 `ConnectivityManager`로 와이파이 연결 상태를 실시간 감지
- **출퇴근 자동 기록**: 등록된 SSID에 연결되면 출근(ARRIVE), 끊기면 퇴근(LEAVE) 이벤트를 Room DB에 기록
- **알림**: 출퇴근 기록 시 알림 표시
- **재부팅 대응**: 자동 감지가 켜져 있으면 재부팅 후에도 서비스 자동 재시작

## 기술 스택

- Kotlin 2.1.0 + Jetpack Compose (Compose Compiler Gradle 플러그인)
- Room (KSP) — 출퇴근 이벤트 저장
- DataStore Preferences — 설정(회사 SSID, 감지 on/off, 현재 상태) 저장
- AGP 8.13.0 / Gradle 8.14.3
- compileSdk / targetSdk 36, minSdk 24

## 프로젝트 구조

```
app/src/main/java/com/commute/app/
├── MainActivity.kt           # 메인 화면 (Compose UI)
├── CommuteViewModel.kt        # 화면 상태 관리
├── data/
│   ├── CommuteEvent.kt        # Room 엔티티 (ARRIVE/LEAVE)
│   ├── CommuteDao.kt
│   ├── CommuteDatabase.kt
│   ├── SettingsRepository.kt  # DataStore 기반 설정 저장소
│   └── Converters.kt
├── wifi/
│   ├── WifiMonitorService.kt  # 와이파이 감지 포그라운드 서비스
│   ├── WifiUtils.kt
│   ├── BootReceiver.kt        # 재부팅 후 서비스 재시작
│   └── Notifications.kt
└── ui/theme/                  # Material3 테마
```

## 빌드

```
.\gradlew.bat assembleDebug --no-daemon
```

또는 `build-apk.bat` 실행 시 빌드 후 연결된 디바이스에 바로 설치됩니다.

필요 환경 변수 (Windows 네이티브 경로):
- `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`
- `ANDROID_HOME=C:\Program Files (x86)\Android\android-sdk`

## 권한

- `ACCESS_FINE_LOCATION` — 연결된 와이파이 SSID 판독에 필요 (Android 8+)
- `POST_NOTIFICATIONS` — 출퇴근 기록 알림
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` — 와이파이 감지 서비스
- `RECEIVE_BOOT_COMPLETED` — 재부팅 후 감지 서비스 자동 재시작

## 현재 상태 / TODO

- 와이파이 감지 → 출퇴근 자동 기록 1차 구현 완료, 에뮬레이터 빌드 검증 완료
- 실기기에서 실제 회사 와이파이로 감지 동작 테스트는 아직 미검증
- 근무 시간 통계, 기록 수정/삭제 등 화면은 아직 없음
