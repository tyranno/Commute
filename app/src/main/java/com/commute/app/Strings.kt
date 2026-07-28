package com.commute.app

import androidx.compose.runtime.staticCompositionLocalOf
import com.commute.app.data.CommuteEventType
import com.commute.app.data.LeaveType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The user-selectable UI language. Stored as [tag] in settings; [SYSTEM] means "follow the device"
 * (resolved to Korean unless the device language is English — 기본은 한글). Only Korean and English
 * are supported.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM("system"),
    KOREAN("ko"),
    ENGLISH("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

/** The concrete language actually rendered — [AppLanguage.SYSTEM] collapses to one of these. */
enum class Lang { KO, EN }

/** [AppLanguage.SYSTEM] follows the device: English only when the device language is English,
 * otherwise Korean (the app's default market). */
fun AppLanguage.resolve(deviceLanguage: String): Lang = when (this) {
    AppLanguage.KOREAN -> Lang.KO
    AppLanguage.ENGLISH -> Lang.EN
    AppLanguage.SYSTEM -> if (deviceLanguage.lowercase(Locale.ROOT).startsWith("en")) Lang.EN else Lang.KO
}

fun stringsFor(lang: Lang): Strings = when (lang) {
    Lang.KO -> KoStrings
    Lang.EN -> EnStrings
}

/** For non-Compose call sites (service, notifications, ViewModel toasts) that only hold the stored
 * preference — [AppLanguage.SYSTEM] is resolved against the current device locale. */
fun stringsFor(language: AppLanguage): Strings =
    stringsFor(language.resolve(Locale.getDefault().language))

/** Defaults to Korean so a composable that renders before the provider is set still reads Korean. */
val LocalStrings = staticCompositionLocalOf<Strings> { KoStrings }

/**
 * Every user-visible string in the app, in one language. Static text is a `val`; text with runtime
 * values or locale-dependent date/number formatting is a `fun`. [KoStrings] and [EnStrings] are the
 * two implementations.
 */
interface Strings {
    // --- common ---
    val back: String
    val close: String
    val confirm: String
    val cancel: String
    val save: String
    val add: String
    val delete: String
    val reallyDelete: String
    val deleteIrreversible: String
    val searching: String

    // --- 기록 exclude/restore (soft delete: hidden from 기록보기, kept in the DB, listable) ---
    /** Button label for removing a record from 기록보기 — replaces [delete]'s label in that
     * dialog only, since this action is reversible where a plain delete implies it isn't. */
    val excludeAction: String
    val reallyExclude: String
    val excludeExplain: String
    val restoreAction: String
    /** e.g. "예외처리된 기록 (3)" / "Excluded records (3)" — opens the list of excluded records. */
    fun excludedRecordsButton(n: Int): String
    val excludedRecordsTitle: String
    val noExcludedRecords: String

    // --- event types (RF-detected) ---
    val eventArrive: String
    val eventLeave: String
    val eventAway: String
    fun eventLabel(type: CommuteEventType): String = when (type) {
        CommuteEventType.ARRIVE -> eventArrive
        CommuteEventType.LEAVE -> eventLeave
        CommuteEventType.AWAY -> eventAway
    }

    // --- leave types (declared) ---
    val leaveAnnual: String
    val leaveHalfAm: String
    val leaveHalfPm: String
    val leaveOuting: String
    fun leaveLabel(type: LeaveType): String = when (type) {
        LeaveType.ANNUAL -> leaveAnnual
        LeaveType.HALF_AM -> leaveHalfAm
        LeaveType.HALF_PM -> leaveHalfPm
        LeaveType.OUTING -> leaveOuting
    }
    /** Short two-line label used inside the narrow chart bars. */
    fun leaveShortLabel(type: LeaveType): String

    // --- home (MainActivity) ---
    val settingsTitle: String
    val locationPermTitle: String
    val locationPermMessage: String
    val grantPermission: String
    val locationOffTitle: String
    val locationOffMessage: String
    val statusWorking: String
    val statusArrivalDetected: String
    val statusLeft: String
    val statusAway: String
    val lunchTag: String
    fun companyWifiLabel(ssid: String): String
    val companyWifiNone: String
    fun companyBeaconLabel(detected: Boolean): String
    val beaconDetectedDesc: String
    val beaconNotDetectedDesc: String
    val searchNearbyWifiDesc: String
    val wifiNoneUnknown: String
    fun currentWifiLabel(ssid: String): String
    val registerAsCompanyWifi: String
    val tabStatus: String
    val tabRecords: String
    val tabLeave: String
    val searchWifiTitle: String
    val noNearbyWifi: String

    // --- home tabs (status/records) ---
    val todayWorked: String
    val todayWorkedInclLunch: String
    val weeklyTotal: String
    val weeklyOvertime: String
    fun weekCommuteHeader(range: String): String
    val gotoThisWeek: String
    val rangeThisWeek: String
    val rangeTwoWeeks: String
    val rangeThisMonth: String
    val rangeThreeMonths: String
    val rangeSixMonths: String
    val rangeAll: String
    val noRecords: String
    val noRecordsInRange: String
    val addMissingRecordDesc: String
    fun missingRecordsCount(n: Int): String
    fun leaveMissingMsg(range: String): String
    fun arriveMissingMsg(range: String): String
    val addLeaveShort: String
    val addArriveShort: String
    val stillWorking: String
    val creditedTime: String
    val addMissingForDay: String
    val noRecordsThisDay: String

    // --- leave tab ---
    val addLeaveOutingDesc: String
    val emptyLeaveMessage: String
    val leaveAddTitle: String
    val leaveEditTitle: String
    val dateLabel: String
    val startLabel: String
    val endLabel: String
    val endAfterStart: String
    /** Shown when a blank "add" would create a second 출근/퇴근 on a day that already has one —
     * guides the user to edit the existing record instead of duplicating it. */
    val duplicateArriveError: String
    val duplicateLeaveError: String
    val noteOptional: String
    val startTimePickerTitle: String
    val endTimePickerTitle: String

    // --- edit event dialog ---
    val recordAddTitle: String
    val recordEditTitle: String
    val awayStartTimeLabel: String
    val timeLabel: String
    val awayEndTimeLabel: String
    val timePickerSelect: String

    // --- settings ---
    val tabDetection: String
    val tabRules: String
    val tabData: String
    val tabApp: String
    fun companyApTitle(count: Int): String
    val apNotRegisteredWarning: String
    fun registerVisibleAp(name: String): String
    val office: String
    val beaconTitle: String
    val beaconDescription: String
    val on: String
    val off: String
    fun registeredBeacon(id: String): String
    val noRegisteredBeacon: String
    val searchNearbyBeacon: String
    val unregister: String
    val bluetoothOff: String
    val noBeaconFound: String
    val absenceThresholdTitle: String
    val absenceThresholdLabel: String
    val autoLeaveTitle: String
    val autoLeaveDescription: String
    val awayDurationLabel: String
    val workEndLabel: String
    val leaveMarginTitle: String
    val leaveMarginDescription: String
    val leaveMarginPickerLabel: String
    val lunchTitle: String
    val halfDayTitle: String
    val halfDayDescription: String
    val weekendTitle: String
    val shown: String
    val hidden: String
    val policyDocCardTitle: String
    val viewPolicy: String
    val backupCardTitle: String
    val backupSave: String
    val backupRestore: String
    val backupRecoveryNote: String
    fun recoverFromLog(n: Int): String
    val noRecoverable: String
    val languageCardTitle: String
    val languageSystem: String
    val languageKorean: String
    val languageEnglish: String
    val resetCardTitle: String
    val resetDescription: String
    val resetButton: String
    val resetDialogMessage: String

    // --- policy document ---
    val policyTitle: String
    val policyDocDesc: String

    // --- app update ---
    val updateCardTitle: String
    fun currentVersionLabel(version: String): String
    val checkForUpdate: String
    val checkingForUpdate: String
    val upToDate: String
    fun updateAvailable(version: String): String
    val downloadAndInstall: String
    fun downloadingUpdate(percent: Int): String
    val installNow: String
    fun updateCheckFailed(message: String?): String
    val tryAgain: String
    val allowUnknownSourcesNote: String
    val openSettings: String

    // --- notifications / service ---
    val channelMonitorName: String
    val channelEventName: String
    val monitorStatus: String
    val notifClockOutTitle: String
    fun notifDayChangeClose(time: String): String
    val notifClockInTitle: String
    fun notifClockInBody(label: String, time: String): String
    val notifAwayEndTitle: String
    fun notifAwayEndBody(time: String, minutes: Long): String
    fun notifAutoCloseAwayBody(time: String): String
    val notifLeaveRevertedTitle: String
    fun notifLeaveRevertedBody(leaveTime: String, backTime: String): String

    // --- ViewModel toasts ---
    val apNotFoundNameOnly: String
    fun apNotVisible(ssid: String): String
    fun apRegistered(count: Int): String
    fun recoveredFromLog(n: Int): String
    val nothingToRecover: String
    val fileOpenFail: String
    val fileReadFail: String
    fun backupDone(events: Int, leaves: Int): String
    fun backupFail(message: String?): String
    val restoreApNote: String
    fun restoreDone(events: Int, leaves: Int, note: String): String
    fun restoreFail(message: String?): String
    val resetDone: String

    // --- locale-dependent formatting ---
    /** e.g. "8시간 0분" / "8h 0m" — always shows minutes when hours > 0 (matches worked-time tiles). */
    fun workHoursMinutes(minutes: Long): String
    /** Signed over/under total, e.g. "+1시간 20분" / "-45m", "0분" / "0m". */
    fun signedMinutes(minutes: Long): String
    /** Compact duration that omits a zero component, e.g. "1시간" / "1h", "30분" / "30m". */
    fun durationLabel(minutes: Int): String
    /** One-letter/short weekday for a day-start timestamp. */
    fun weekday(dayStart: Long): String
    /** Dialog header for a day, e.g. "7월 23일 (수)" / "Jul 23 (Wed)". */
    fun dayHeader(day: Long): String
    /** Trailing "(30분)" / "(4시간 46분)" duration for a record row — hours and minutes once it
     * passes an hour, since a long 자리비움 shown as a bare "(286분)" takes arithmetic to read. */
    fun minutesParen(minutes: Long): String
    /** Trailing away duration with the lunch break removed, e.g. "(40분, 점심 제외)" /
     * "(40m, excl. lunch)" — shown on a 자리비움 row whose span crossed the lunch window. */
    fun minutesParenExLunch(minutes: Long): String
    /** Full date with weekday for leave/record pickers, e.g. "2026-07-23 (수)" / "2026-07-23 (Wed)". */
    fun leaveDate(timestamp: Long): String
}

// ---------------------------------------------------------------------------------------------
// Shared numeric formatting (locale-independent) reused by both implementations.
// ---------------------------------------------------------------------------------------------

private fun hmParts(minutes: Long): Pair<Long, Long> = (minutes / 60) to (minutes % 60)

// ---------------------------------------------------------------------------------------------

object KoStrings : Strings {
    private val locale = Locale.KOREAN

    override val back = "뒤로"
    override val close = "닫기"
    override val confirm = "확인"
    override val cancel = "취소"
    override val save = "저장"
    override val add = "추가"
    override val delete = "삭제"
    override val reallyDelete = "정말 삭제"
    override val deleteIrreversible = "삭제하면 되돌릴 수 없습니다. 한 번 더 누르면 삭제됩니다."

    override val excludeAction = "예외처리"
    override val reallyExclude = "정말 예외처리"
    override val excludeExplain = "예외처리하면 기록 목록과 근무시간 계산에서 빠집니다. 데이터는 남아 있어 나중에 다시 넣을 수 있습니다. 한 번 더 누르면 예외처리됩니다."
    override val restoreAction = "복원"
    override fun excludedRecordsButton(n: Int) = "예외처리된 기록 ($n)"
    override val excludedRecordsTitle = "예외처리된 기록"
    override val noExcludedRecords = "예외처리된 기록이 없습니다"
    override val searching = "검색 중..."

    override val eventArrive = "출근"
    override val eventLeave = "퇴근"
    override val eventAway = "자리비움"

    override val leaveAnnual = "연차"
    override val leaveHalfAm = "오전 반차"
    override val leaveHalfPm = "오후 반차"
    override val leaveOuting = "외출"
    override fun leaveShortLabel(type: LeaveType) = when (type) {
        LeaveType.ANNUAL -> "연차"
        LeaveType.HALF_AM -> "오전\n반차"
        LeaveType.HALF_PM -> "오후\n반차"
        LeaveType.OUTING -> "외출"
    }

    override val settingsTitle = "근무 규칙 설정"
    override val locationPermTitle = "위치 권한 필요"
    override val locationPermMessage = "와이파이 이름을 읽고 출퇴근을 감지하려면 위치 권한이 필요합니다."
    override val grantPermission = "권한 허용"
    override val locationOffTitle = "위치 서비스 꺼짐"
    override val locationOffMessage = "위치 권한은 허용되어 있지만 기기의 위치 서비스(GPS)가 꺼져 있어 와이파이 이름을 읽을 수 없습니다. 설정에서 위치 서비스를 켜주세요."
    override val statusWorking = "근무중"
    override val statusArrivalDetected = "출근 인식됨"
    override val statusLeft = "퇴근"
    override val statusAway = "자리비움"
    override val lunchTag = "점심시간"
    override fun companyWifiLabel(ssid: String) = "회사 와이파이: $ssid"
    override val companyWifiNone = "회사 와이파이 미등록"
    override fun companyBeaconLabel(detected: Boolean) = "회사 비콘: ${if (detected) "감지됨" else "미감지"}"
    override val beaconDetectedDesc = "회사 비콘 감지됨"
    override val beaconNotDetectedDesc = "회사 비콘 미감지"
    override val searchNearbyWifiDesc = "주변 와이파이 검색"
    override val wifiNoneUnknown = "없음/알 수 없음"
    override fun currentWifiLabel(ssid: String) = "현재 와이파이: $ssid"
    override val registerAsCompanyWifi = "회사 와이파이로 등록"
    override val tabStatus = "현황"
    override val tabRecords = "기록"
    override val tabLeave = "연차·외출"
    override val searchWifiTitle = "주변 와이파이 검색"
    override val noNearbyWifi = "주변에서 와이파이를 찾지 못했습니다."

    override val todayWorked = "오늘 근무시간"
    override val todayWorkedInclLunch = "오늘 근무시간 (점심 포함)"
    override val weeklyTotal = "이번주 총 근무시간"
    override val weeklyOvertime = "이번주 초과근무"
    override fun weekCommuteHeader(range: String) = "이번주 출퇴근 시간($range)"
    override val gotoThisWeek = "이번주로"
    override val rangeThisWeek = "이번주"
    override val rangeTwoWeeks = "최근 2주"
    override val rangeThisMonth = "이번달"
    override val rangeThreeMonths = "최근 3개월"
    override val rangeSixMonths = "최근 6개월"
    override val rangeAll = "전체"
    override val noRecords = "기록이 없습니다."
    override val noRecordsInRange = "이 기간에 기록이 없습니다."
    override val addMissingRecordDesc = "빠진 기록 추가"
    override fun missingRecordsCount(n: Int) = "기록 누락 ${n}건"
    override fun leaveMissingMsg(range: String) = "$range 출근 이후 퇴근 기록 없음"
    override fun arriveMissingMsg(range: String) = "$range 퇴근 이전 출근 기록 없음"
    override val addLeaveShort = "퇴근 추가"
    override val addArriveShort = "출근 추가"
    override val stillWorking = "근무 중"
    override val creditedTime = "인정시간"
    override val addMissingForDay = "이 날짜에 빠진 기록 추가"
    override val noRecordsThisDay = "이 날의 기록이 없습니다."

    override val addLeaveOutingDesc = "연차/외출 추가"
    override val emptyLeaveMessage = "연차·반차·외출 기록이 없습니다.\n아래 + 버튼으로 추가하세요."
    override val leaveAddTitle = "연차/외출 추가"
    override val leaveEditTitle = "연차/외출 수정"
    override val dateLabel = "날짜"
    override val startLabel = "시작"
    override val endLabel = "종료"
    override val endAfterStart = "종료 시각은 시작 시각보다 늦어야 합니다."
    override val duplicateArriveError = "이 날짜에 이미 출근 기록이 있습니다. 기존 기록을 눌러 수정해주세요."
    override val duplicateLeaveError = "이 날짜에 이미 퇴근 기록이 있습니다. 기존 기록을 눌러 수정해주세요."
    override val noteOptional = "메모 (선택)"
    override val startTimePickerTitle = "시작 시각 선택"
    override val endTimePickerTitle = "종료 시각 선택"

    override val recordAddTitle = "기록 추가"
    override val recordEditTitle = "기록 수정"
    override val awayStartTimeLabel = "시작 시각"
    override val timeLabel = "시각"
    override val awayEndTimeLabel = "종료 시각"
    override val timePickerSelect = "시각 선택"

    override val tabDetection = "감지"
    override val tabRules = "근무 규칙"
    override val tabData = "데이터"
    override val tabApp = "앱"
    override fun companyApTitle(count: Int) = "회사 AP 등록 (${count}대)"
    override val apNotRegisteredWarning = "AP가 등록되지 않아 와이파이 이름만으로 감지합니다. 같은 이름의 다른 와이파이도 회사로 인식될 수 있으니, 회사에서 아래 버튼을 눌러 등록하세요."
    override fun registerVisibleAp(name: String) = "지금 보이는 $name AP 등록"
    override val office = "회사"
    override val beaconTitle = "회사 비콘(BLE) 병행 감지"
    override val beaconDescription = "와이파이가 안 잡혀도(예: 와이파이 끄고 LTE 사용) 자리 근처 비콘으로 회사를 감지합니다."
    override val on = "사용함"
    override val off = "사용 안 함"
    override fun registeredBeacon(id: String) = "등록된 비콘: $id"
    override val noRegisteredBeacon = "등록된 비콘 없음"
    override val searchNearbyBeacon = "주변 비콘 검색"
    override val unregister = "등록 해제"
    override val bluetoothOff = "블루투스가 꺼져 있습니다. 블루투스를 켜고 다시 시도하세요."
    override val noBeaconFound = "주변에서 회사 비콘을 찾지 못했습니다. 비콘(노트북·ESP32)이 켜져 있는지 확인하세요."
    override val absenceThresholdTitle = "자리비움 인정 기준(분)"
    override val absenceThresholdLabel = "자리비움 인정 기준"
    override val autoLeaveTitle = "자동 퇴근 처리"
    override val autoLeaveDescription = "자리비움이 아래 기준에 닿으면 퇴근으로 확정합니다. 퇴근 시각은 자리비움이 시작된 시각으로 기록되고, 그 전에 회사 와이파이가 다시 잡히면 자리비움으로 끝납니다."
    override val awayDurationLabel = "자리비움 지속 시간"
    override val workEndLabel = "근무 인정 시간 종료"
    override val leaveMarginTitle = "퇴근 시각 마진"
    override val leaveMarginDescription = "자리를 떠도 신호가 몇 분 더 잡혀 퇴근이 늦게 찍힙니다. 그만큼 앞당겨 기록합니다. (0분이면 보정 안 함)"
    override val leaveMarginPickerLabel = "퇴근 시각 앞당김"
    override val lunchTitle = "점심시간"
    override val halfDayTitle = "반차 시간대"
    override val halfDayDescription = "연구소 운영 방안엔 연차/반차 규정이 없어 일반 관행을 기본값으로 둡니다. 연차는 오전 시작~오후 종료 전체로 계산됩니다."
    override val weekendTitle = "주말(토·일) 표시"
    override val shown = "표시함"
    override val hidden = "숨김"
    override val policyDocCardTitle = "근거 문서"
    override val viewPolicy = "가산 연구소 운영 방안 보기"
    override val backupCardTitle = "데이터 백업"
    override val backupSave = "백업 저장"
    override val backupRestore = "백업 복원"
    override val backupRecoveryNote = "기록은 백업과 별개로 앱 내부 로그에 자동 저장됩니다. 재설치·DB 손상으로 기록이 사라져도 아래 버튼으로 되살릴 수 있습니다."
    override fun recoverFromLog(n: Int) = "기록 로그에서 복구 (${n}건)"
    override val noRecoverable = "복구할 기록 없음"
    override val languageCardTitle = "언어"
    override val languageSystem = "시스템 기본"
    override val languageKorean = "한국어"
    override val languageEnglish = "English"
    override val resetCardTitle = "데이터 초기화"
    override val resetDescription = "출퇴근 기록과 연차/반차/외출 기록을 모두 삭제합니다. 회사 Wi-Fi·비콘 등록과 설정은 그대로 유지됩니다. 삭제된 기록은 되돌릴 수 없습니다."
    override val resetButton = "기록 전체 삭제"
    override val resetDialogMessage = "모든 출퇴근·연차 기록이 영구 삭제됩니다. 계속할까요?"

    override val policyTitle = "가산 연구소 운영 방안"
    override val policyDocDesc = "가산 연구소 운영 방안 문서"

    override val updateCardTitle = "앱 업데이트"
    override fun currentVersionLabel(version: String) = "현재 버전: $version"
    override val checkForUpdate = "업데이트 확인"
    override val checkingForUpdate = "확인 중…"
    override val upToDate = "최신 버전입니다"
    override fun updateAvailable(version: String) = "새 버전 $version 사용 가능"
    override val downloadAndInstall = "다운로드 후 설치"
    override fun downloadingUpdate(percent: Int) = "다운로드 중 (${percent}%)"
    override val installNow = "설치"
    override fun updateCheckFailed(message: String?) = "업데이트 확인 실패: $message"
    override val tryAgain = "다시 시도"
    override val allowUnknownSourcesNote = "설치하려면 \"출처를 알 수 없는 앱 설치\"를 이 앱에 허용해야 합니다."
    override val openSettings = "설정 열기"

    override val channelMonitorName = "출퇴근 감지 서비스"
    override val channelEventName = "출퇴근 기록 알림"
    override val monitorStatus = "출퇴근 감지 중"
    override val notifClockOutTitle = "퇴근 기록됨"
    override fun notifDayChangeClose(time: String) = "날짜 변경으로 자동 마감 $time"
    override val notifClockInTitle = "출근 기록됨"
    override fun notifClockInBody(label: String, time: String) = "회사($label) 감지 $time"
    override val notifAwayEndTitle = "자리비움 종료"
    override fun notifAwayEndBody(time: String, minutes: Long) = "복귀 $time (자리비움 ${minutes}분)"
    override fun notifAutoCloseAwayBody(time: String) = "자리비움이 이어져 자동 마감 $time"
    override val notifLeaveRevertedTitle = "퇴근이 자리비움으로 정정됨"
    override fun notifLeaveRevertedBody(leaveTime: String, backTime: String) =
        "복귀 $backTime — $leaveTime 퇴근 기록을 자리비움으로 바꿨습니다"

    override val apNotFoundNameOnly = "AP를 찾지 못해 이름만으로 감지합니다. 회사에서 설정 > 회사 AP 등록을 눌러주세요"
    override fun apNotVisible(ssid: String) = "주변에 $ssid AP가 보이지 않습니다"
    override fun apRegistered(count: Int) = "회사 AP ${count}대 등록됨"
    override fun recoveredFromLog(n: Int) = "로그에서 ${n}건 복구됨"
    override val nothingToRecover = "복구할 기록이 없습니다"
    override val fileOpenFail = "파일을 열 수 없습니다"
    override val fileReadFail = "파일을 읽을 수 없습니다"
    override fun backupDone(events: Int, leaves: Int) = "백업 완료 (기록 ${events}건 · 연차/외출 ${leaves}건)"
    override fun backupFail(message: String?) = "백업 실패: $message"
    override val restoreApNote = " · 회사 AP 정보가 없어 이름만으로 감지합니다"
    override fun restoreDone(events: Int, leaves: Int, note: String) = "복원 완료 (기록 ${events}건 · 연차/외출 ${leaves}건)$note"
    override fun restoreFail(message: String?) = "복원 실패: $message"
    override val resetDone = "기록을 모두 삭제했습니다"

    override fun workHoursMinutes(minutes: Long): String {
        val (h, m) = hmParts(minutes)
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
    }

    override fun signedMinutes(minutes: Long): String = when {
        minutes == 0L -> "0분"
        minutes > 0 -> "+${workHoursMinutes(minutes)}"
        else -> "-${workHoursMinutes(-minutes)}"
    }

    override fun durationLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${minutes}분"
            m == 0 -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    private val weekdayNames = arrayOf("일", "월", "화", "수", "목", "금", "토")
    override fun weekday(dayStart: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
        return weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    override fun dayHeader(day: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = day }
        val fmt = SimpleDateFormat("M월 d일", locale)
        return "${fmt.format(Date(day))} (${weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]})"
    }

    override fun minutesParen(minutes: Long) = "(${workHoursMinutes(minutes)})"
    override fun minutesParenExLunch(minutes: Long) = "(${workHoursMinutes(minutes)}, 점심 제외)"

    override fun leaveDate(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd (E)", locale).format(Date(timestamp))
}

object EnStrings : Strings {
    private val locale = Locale.ENGLISH

    override val back = "Back"
    override val close = "Close"
    override val confirm = "OK"
    override val cancel = "Cancel"
    override val save = "Save"
    override val add = "Add"
    override val delete = "Delete"
    override val reallyDelete = "Delete?"
    override val deleteIrreversible = "This can't be undone. Tap again to delete."

    override val excludeAction = "Exclude"
    override val reallyExclude = "Exclude?"
    override val excludeExplain = "Excluding removes this from the record list and worked-time totals. The data stays, so it can be brought back later. Tap again to exclude."
    override val restoreAction = "Restore"
    override fun excludedRecordsButton(n: Int) = "Excluded records ($n)"
    override val excludedRecordsTitle = "Excluded records"
    override val noExcludedRecords = "No excluded records"
    override val searching = "Searching..."

    override val eventArrive = "Clock in"
    override val eventLeave = "Clock out"
    override val eventAway = "Away"

    override val leaveAnnual = "Annual leave"
    override val leaveHalfAm = "AM half-day"
    override val leaveHalfPm = "PM half-day"
    override val leaveOuting = "Outing"
    override fun leaveShortLabel(type: LeaveType) = when (type) {
        LeaveType.ANNUAL -> "Annual"
        LeaveType.HALF_AM -> "AM\nhalf"
        LeaveType.HALF_PM -> "PM\nhalf"
        LeaveType.OUTING -> "Outing"
    }

    override val settingsTitle = "Settings"
    override val locationPermTitle = "Location permission required"
    override val locationPermMessage = "Location permission is needed to read the Wi-Fi name and detect your commute."
    override val grantPermission = "Grant permission"
    override val locationOffTitle = "Location services off"
    override val locationOffMessage = "Location permission is granted, but the device's location service (GPS) is off, so the Wi-Fi name can't be read. Turn on location services in Settings."
    override val statusWorking = "Working"
    override val statusArrivalDetected = "Arrival detected"
    override val statusLeft = "Clocked out"
    override val statusAway = "Away"
    override val lunchTag = "Lunch"
    override fun companyWifiLabel(ssid: String) = "Office Wi-Fi: $ssid"
    override val companyWifiNone = "No office Wi-Fi set"
    override fun companyBeaconLabel(detected: Boolean) = "Office beacon: ${if (detected) "Detected" else "Not detected"}"
    override val beaconDetectedDesc = "Office beacon detected"
    override val beaconNotDetectedDesc = "Office beacon not detected"
    override val searchNearbyWifiDesc = "Search nearby Wi-Fi"
    override val wifiNoneUnknown = "None/Unknown"
    override fun currentWifiLabel(ssid: String) = "Current Wi-Fi: $ssid"
    override val registerAsCompanyWifi = "Register as office Wi-Fi"
    override val tabStatus = "Status"
    override val tabRecords = "Records"
    override val tabLeave = "Leave"
    override val searchWifiTitle = "Search nearby Wi-Fi"
    override val noNearbyWifi = "No nearby Wi-Fi found."

    override val todayWorked = "Today"
    override val todayWorkedInclLunch = "Today (incl. lunch)"
    override val weeklyTotal = "This week"
    override val weeklyOvertime = "This week overtime"
    override fun weekCommuteHeader(range: String) = "This week's hours ($range)"
    override val gotoThisWeek = "This week"
    override val rangeThisWeek = "This week"
    override val rangeTwoWeeks = "Last 2 weeks"
    override val rangeThisMonth = "This month"
    override val rangeThreeMonths = "Last 3 months"
    override val rangeSixMonths = "Last 6 months"
    override val rangeAll = "All"
    override val noRecords = "No records."
    override val noRecordsInRange = "No records in this range."
    override val addMissingRecordDesc = "Add missing record"
    override fun missingRecordsCount(n: Int) = "$n missing record${if (n == 1) "" else "s"}"
    override fun leaveMissingMsg(range: String) = "No clock-out after clock-in on $range"
    override fun arriveMissingMsg(range: String) = "No clock-in before clock-out on $range"
    override val addLeaveShort = "Add clock-out"
    override val addArriveShort = "Add clock-in"
    override val stillWorking = "Working"
    override val creditedTime = "Credited"
    override val addMissingForDay = "Add a missing record for this day"
    override val noRecordsThisDay = "No records for this day."

    override val addLeaveOutingDesc = "Add leave/outing"
    override val emptyLeaveMessage = "No leave records yet.\nTap + below to add one."
    override val leaveAddTitle = "Add leave/outing"
    override val leaveEditTitle = "Edit leave/outing"
    override val dateLabel = "Date"
    override val startLabel = "Start"
    override val endLabel = "End"
    override val endAfterStart = "End time must be after start time."
    override val duplicateArriveError = "This day already has a clock-in record. Tap it to edit instead."
    override val duplicateLeaveError = "This day already has a clock-out record. Tap it to edit instead."
    override val noteOptional = "Note (optional)"
    override val startTimePickerTitle = "Select start time"
    override val endTimePickerTitle = "Select end time"

    override val recordAddTitle = "Add record"
    override val recordEditTitle = "Edit record"
    override val awayStartTimeLabel = "Start time"
    override val timeLabel = "Time"
    override val awayEndTimeLabel = "End time"
    override val timePickerSelect = "Select time"

    override val tabDetection = "Detection"
    override val tabRules = "Work rules"
    override val tabData = "Data"
    override val tabApp = "App"
    override fun companyApTitle(count: Int) = "Office APs ($count)"
    override val apNotRegisteredWarning = "No AP registered, so detection uses the Wi-Fi name only. A different network with the same name could be mistaken for the office — tap the button below while at the office to register."
    override fun registerVisibleAp(name: String) = "Register visible $name APs"
    override val office = "office"
    override val beaconTitle = "Office beacon (BLE)"
    override val beaconDescription = "Detects the office by a nearby beacon even when Wi-Fi isn't available (e.g. Wi-Fi off, on LTE)."
    override val on = "On"
    override val off = "Off"
    override fun registeredBeacon(id: String) = "Registered beacon: $id"
    override val noRegisteredBeacon = "No beacon registered"
    override val searchNearbyBeacon = "Search nearby beacons"
    override val unregister = "Unregister"
    override val bluetoothOff = "Bluetooth is off. Turn it on and try again."
    override val noBeaconFound = "No office beacon found nearby. Check that the beacon (laptop/ESP32) is on."
    override val absenceThresholdTitle = "Away threshold (min)"
    override val absenceThresholdLabel = "Away threshold"
    override val autoLeaveTitle = "Auto clock-out"
    override val autoLeaveDescription = "When an away period reaches the limits below, it's confirmed as a clock-out. The clock-out time is when the away started; if the office Wi-Fi reappears before then, it ends as an away instead."
    override val awayDurationLabel = "Away duration"
    override val workEndLabel = "Work recognition end"
    override val leaveMarginTitle = "Clock-out margin"
    override val leaveMarginDescription = "The signal lingers for a few minutes after you leave, so clock-out is stamped late. It's pulled back by this much. (0 = no adjustment)"
    override val leaveMarginPickerLabel = "Clock-out pull-back"
    override val lunchTitle = "Lunch time"
    override val halfDayTitle = "Half-day windows"
    override val halfDayDescription = "The lab policy has no leave/half-day rules, so common practice is the default. Annual leave is counted from the AM start to the PM end."
    override val weekendTitle = "Show weekend (Sat/Sun)"
    override val shown = "Shown"
    override val hidden = "Hidden"
    override val policyDocCardTitle = "Reference document"
    override val viewPolicy = "View lab operating policy"
    override val backupCardTitle = "Data backup"
    override val backupSave = "Save backup"
    override val backupRestore = "Restore backup"
    override val backupRecoveryNote = "Records are also saved automatically to an internal log, separate from backups. If records are lost to a reinstall or DB corruption, restore them with the button below."
    override fun recoverFromLog(n: Int) = "Recover from log ($n)"
    override val noRecoverable = "Nothing to recover"
    override val languageCardTitle = "Language"
    override val languageSystem = "System default"
    override val languageKorean = "한국어"
    override val languageEnglish = "English"
    override val resetCardTitle = "Reset data"
    override val resetDescription = "Deletes all commute records and leave entries. Office Wi-Fi/beacon registration and settings are kept. Deleted records can't be recovered."
    override val resetButton = "Delete all records"
    override val resetDialogMessage = "All commute and leave records will be permanently deleted. Continue?"

    override val policyTitle = "Lab operating policy"
    override val policyDocDesc = "Lab operating policy document"

    override val updateCardTitle = "App update"
    override fun currentVersionLabel(version: String) = "Current version: $version"
    override val checkForUpdate = "Check for update"
    override val checkingForUpdate = "Checking…"
    override val upToDate = "You're on the latest version"
    override fun updateAvailable(version: String) = "Version $version is available"
    override val downloadAndInstall = "Download & install"
    override fun downloadingUpdate(percent: Int) = "Downloading ($percent%)"
    override val installNow = "Install"
    override fun updateCheckFailed(message: String?) = "Update check failed: $message"
    override val tryAgain = "Try again"
    override val allowUnknownSourcesNote = "To install, allow \"install unknown apps\" for this app."
    override val openSettings = "Open settings"

    override val channelMonitorName = "Commute detection service"
    override val channelEventName = "Commute record alerts"
    override val monitorStatus = "Detecting commute"
    override val notifClockOutTitle = "Clock-out recorded"
    override fun notifDayChangeClose(time: String) = "Auto-closed at day change $time"
    override val notifClockInTitle = "Clock-in recorded"
    override fun notifClockInBody(label: String, time: String) = "Office ($label) detected $time"
    override val notifAwayEndTitle = "Away ended"
    override fun notifAwayEndBody(time: String, minutes: Long) = "Back at $time (away ${minutes}m)"
    override fun notifAutoCloseAwayBody(time: String) = "Auto-closed after extended away $time"
    override val notifLeaveRevertedTitle = "Clock-out changed to away"
    override fun notifLeaveRevertedBody(leaveTime: String, backTime: String) =
        "Back at $backTime — the $leaveTime clock-out is now an away"

    override val apNotFoundNameOnly = "Couldn't find the AP, detecting by name only. At the office, tap Settings > Office APs."
    override fun apNotVisible(ssid: String) = "No $ssid AP visible nearby"
    override fun apRegistered(count: Int) = "$count office AP${if (count == 1) "" else "s"} registered"
    override fun recoveredFromLog(n: Int) = "Recovered $n from log"
    override val nothingToRecover = "Nothing to recover"
    override val fileOpenFail = "Couldn't open the file"
    override val fileReadFail = "Couldn't read the file"
    override fun backupDone(events: Int, leaves: Int) = "Backup complete ($events records · $leaves leave)"
    override fun backupFail(message: String?) = "Backup failed: $message"
    override val restoreApNote = " · No office AP info, detecting by name only"
    override fun restoreDone(events: Int, leaves: Int, note: String) = "Restore complete ($events records · $leaves leave)$note"
    override fun restoreFail(message: String?) = "Restore failed: $message"
    override val resetDone = "All records deleted"

    override fun workHoursMinutes(minutes: Long): String {
        val (h, m) = hmParts(minutes)
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    override fun signedMinutes(minutes: Long): String = when {
        minutes == 0L -> "0m"
        minutes > 0 -> "+${workHoursMinutes(minutes)}"
        else -> "-${workHoursMinutes(-minutes)}"
    }

    override fun durationLabel(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${minutes}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    private val weekdayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    override fun weekday(dayStart: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
        return weekdayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    override fun dayHeader(day: Long): String =
        SimpleDateFormat("MMM d (EEE)", locale).format(Date(day))

    override fun minutesParen(minutes: Long) = "(${workHoursMinutes(minutes)})"
    override fun minutesParenExLunch(minutes: Long) = "(${workHoursMinutes(minutes)}, excl. lunch)"

    override fun leaveDate(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd (EEE)", locale).format(Date(timestamp))
}
