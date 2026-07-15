package com.commute.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One line of the 가산 연구소 운영 방안 document, at its original outline depth (0 = "1.",
 * "2." top level, down to 3 = "a., b., c." and the "예)" note under it). */
private data class PolicyLine(val indent: Int, val text: String, val emphasis: Boolean = false)

private val POLICY_LINES = listOf(
    PolicyLine(0, "1. 식대 지원", emphasis = true),
    PolicyLine(1, "A. 주변 식당 섭외 후 월말 정산 지원 – 식당 선정 및 범위는 현지 사정 검토 후 확정"),
    PolicyLine(1, "B. 기존 월급에 포함된 식대(10만원)은 월급에서 제외 함"),
    PolicyLine(0, "2. 자율출퇴근제 운영", emphasis = true),
    PolicyLine(
        1,
        "A. 운영목적: 연구원의 복지 향상을 통한 업무 만족도 증가 및 생산성 향상, 연구소 우수 인력 채용 유인책 마련 및 애사심과 충성도 재고, 이직률 감소 목적"
    ),
    PolicyLine(1, "B. 운영 방안: 주 40시간 이내에서 출퇴근 시간을 자유롭게 사용."),
    PolicyLine(1, "C. 집중 근무시간 운영: 집중근무시간 운영으로 업무 집중도 향상"),
    PolicyLine(1, "D. 회의시간 사전 공지 운영: 사전 업무일정 협의를 통한 업무 공백 최소화"),
    PolicyLine(1, "E. 상세 운영 방안"),
    PolicyLine(2, "i. 주 40시간 기본 근무"),
    PolicyLine(2, "ii. 근무 인정 시간: 07:00~22:00"),
    PolicyLine(2, "iii. 출근 인정 시간: 07:00~13:00"),
    PolicyLine(2, "iv. 일 최소 근무 시간: 4시간 30분 (업무시간 4시간 + 휴게시간 30분)"),
    PolicyLine(2, "v. 집중 근무 시간: 10:00~12:00, 14:00~16:00"),
    PolicyLine(2, "vi. 휴게 시간 적용: 법정 휴게시간 반영"),
    PolicyLine(3, "a. 매 4시간 근무마다 30분 휴게시간 적용."),
    PolicyLine(3, "b. 8시간 근무 시 점심시간 1시간으로 휴게시간 적용"),
    PolicyLine(3, "c. 12시간 이상 근무 시 휴게시간 1시간 30분 적용"),
    PolicyLine(4, "예) 08:00~21:30 근무 시 휴게시간 1시간 30분 적용으로 실 근무시간은 12시간임."),
    PolicyLine(2, "vii. 근무 제외 시간: 10분 이상 이석 시 근무 시간에서 제외 함."),
    PolicyLine(2, "viii. 업무로 인한 이석 시간은 소명 후 근무 시간 인정 처리 함."),
    PolicyLine(2, "ix. 외근 및 출장으로 인한 시간은 별도 결재를 통하여 소명.(연구소장 전결)")
)

/** In-app view of the 가산 연구소 운영 방안 document — it's short, plain text, so there's no
 * need to ship the original PDF and hand off to an external viewer for it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyDocumentScreen(onBack: () -> Unit = {}) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("가산 연구소 운영 방안") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("2022.09.23", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("연구소", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                POLICY_LINES.forEach { line ->
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (line.emphasis) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(start = (line.indent * 16).dp)
                    )
                }
            }
        }
    }
}
