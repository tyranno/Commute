# Commute

회사 와이파이 연결/해제를 감지해서 출근·퇴근을 자동으로 기록하는 순수 코틀린 + Jetpack Compose 네이티브 안드로이드 앱입니다.

## 주요 기능

- **회사 와이파이 등록**: 현재 연결된 와이파이를 버튼 한 번으로 "회사 와이파이"로 등록
- **자동 감지**: 포그라운드 서비스가 1분마다 현재 연결된 와이파이 SSID를 폴링
- **출퇴근 자동 기록**: 등록된 SSID에 최초 연결되면 출근(ARRIVE), 마지막으로 연결이 확인된 시각을 퇴근(LEAVE)으로 Room DB에 기록
- **이석(자리비움) 구분**: 설정한 "이석 인정 기준(분)"보다 짧게 와이파이가 끊겼다 다시 연결되면 퇴근으로 처리하지 않고 이석(AWAY)으로 기록 — 기본값은 가산 연구소 운영 방안 문서 기준 10분이며 앱 내 설정에서 조정 가능
- **점심시간 설정**: 설정한 점심시간 구간(기본 12:00~13:00) 동안의 단절은 이석 인정 기준과 무관하게 퇴근으로 마감하지 않고, 종료 후에도 이석 인정 기준만큼 추가 유예를 둠
- **알림**: 출근/퇴근/이석 종료 시 알림 표시
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
├── MainActivity.kt           # 메인 화면 (Compose UI) + 내비게이션(NavHost) 설정
├── SettingsScreen.kt          # 근무 규칙 설정 화면(이석 인정 기준, 점심시간 등)
├── CommuteViewModel.kt        # 화면 상태 관리
├── data/
│   ├── CommuteEvent.kt        # Room 엔티티 (ARRIVE/LEAVE/AWAY)
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

## 근무 규칙

`doc/가산 연구소 운영 방안_0923 (1).pdf`(자율출퇴근제 운영 방안)를 근거로 앱이 지원해야 할 근태 규칙이며, 값은 메인 화면 우측 상단 "근무 규칙 설정" 버튼으로 진입하는 별도 설정 화면(`SettingsScreen.kt`)에서 조정 가능(현재 구현된 항목만 표시):

- 이석 인정 기준: 10분 (구현 완료, 기본값 조정 가능)
- 점심시간: 12:00~13:00 (구현 완료, 기본값 조정 가능 — 이 구간은 이석 인정 기준과 무관하게 퇴근으로 마감하지 않음)
- 근무 인정 시간 07:00~22:00, 출근 인정 시간 07:00~13:00, 구간별 휴게시간 공제(4h마다 30분/8h 점심1h/12h+ 1h30m) — 아직 미구현

## 현재 상태 / TODO

- 와이파이 감지 → 출퇴근 자동 기록, 이석/출근/퇴근 구분(점심시간 설정 포함) 1차 구현 완료, 에뮬레이터 빌드 검증 완료
- 실기기에서 실제 회사 와이파이로 감지 동작 테스트는 아직 미검증
- 실 근무시간 계산(휴게시간 공제), 근무/출근 인정 시간 적용, 주 40시간 통계, 기록 수정/삭제 등 화면은 아직 없음
