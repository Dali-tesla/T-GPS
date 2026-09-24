package com.dali.teslagps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dali.teslagps.core.AnchorGeometry
import kotlin.math.roundToInt

@Composable
fun AnchorScreen(vm: AnchorViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val remaining by vm.remainingSec.collectAsStateWithLifecycle()

    // 메인 화면은 항상 그려두고, 설정이 안 된 최초 실행에는 팝업으로 입력을 받는다.
    // (전체 화면으로 가리지 않아 뒤로 배경이 살짝 비쳐 보이는 편이 자연스럽다)
    if (!ui.configured || ui.showSetup) {
        SetupDialog(ui, vm)
    }

    LaunchedEffect(ui.configured) {
        if (ui.configured && ui.vehicleLocation == null) vm.refreshVehicle()
    }

    ui.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text("알림") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("확인") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GuideBanner()
        TopPanel(ui, active, remaining)
        OffsetControls(ui, vm)
        ActionButtons(ui, vm, active)
        TextButton(onClick = vm::openSetup, modifier = Modifier.fillMaxWidth()) {
            Text("BLE 설정 / 페어링", fontSize = 12.sp)
        }
        LegalNotice()
    }
}

// ── 사용 안내 ───────────────────────────────────────────────────────────────

@Composable
private fun GuideBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "!",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "차량 가까이(블루투스 범위)에서 사용하세요.\n" +
                    "절전 중인 차량은 앱이 블루투스로 깨운 뒤 위치를 읽어 수 초~수십 초 걸릴 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

// ── 상단: 좌 다이어그램 / 우 정보 ───────────────────────────────────────────

@Composable
private fun TopPanel(ui: UiState, active: Boolean, remaining: Int) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                VehicleDiagram(
                    offset = ui.offset,
                    hasVehicle = ui.vehicleLocation != null,
                    modifier = Modifier.fillMaxSize(),
                )
                if (ui.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }
            }

            InfoColumn(ui, active, remaining, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun InfoColumn(
    ui: UiState,
    active: Boolean,
    remaining: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (active)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(8.dp)) {
                Text(
                    if (active) "위치 고정 중" else "대기 중",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (active) {
                    Text(
                        "%d:%02d 후 자동 복구".format(remaining / 60, remaining % 60),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                    )
                } else if (ui.statusText.isNotBlank()) {
                    Text(
                        ui.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        ui.vehicleLocation?.let { loc ->
            InfoRow("차량", ui.vehicleName.ifBlank { "-" })
            InfoRow("방향", "${loc.headingDeg.roundToInt()}°")
            InfoRow("위도", "%.6f".format(loc.geo.lat), mono = true)
            InfoRow("경도", "%.6f".format(loc.geo.lng), mono = true)
        } ?: Text(
            "차량 위치 없음\n아래 새로고침을 눌러 주세요",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ui.targetGeo?.let { t ->
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            InfoRow("폰 위도", "%.6f".format(t.lat), mono = true)
            InfoRow("폰 경도", "%.6f".format(t.lng), mono = true)
            InfoRow("거리", "%.2fm".format(ui.offset.distanceM), highlight = ui.outOfRange)
        }

    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    highlight: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            color = if (highlight) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── 폰 위치 조정 (컴팩트) ───────────────────────────────────────────────────

@Composable
private fun OffsetControls(ui: UiState, vm: AnchorViewModel) {
    Card {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "폰 위치 조정",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    AnchorGeometry.describe(ui.offset),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = if (ui.outOfRange) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompactButton("전방2m", Modifier.weight(1.3f)) {
                    vm.applyPreset(front = true, distanceM = 2.0)
                }
                CompactButton("후방2m", Modifier.weight(1.3f)) {
                    vm.applyPreset(front = false, distanceM = 2.0)
                }
                CompactOutlined("↑", Modifier.weight(0.75f)) { vm.nudge(0.25, 0.0) }
                CompactOutlined("↓", Modifier.weight(0.75f)) { vm.nudge(-0.25, 0.0) }
                CompactOutlined("←", Modifier.weight(0.75f)) { vm.nudge(0.0, -0.25) }
                CompactOutlined("→", Modifier.weight(0.75f)) { vm.nudge(0.0, 0.25) }
            }

            CompactSlider(
                label = "전후",
                value = ui.offset.forwardM.toFloat(),
                onChange = { vm.setOffset(it.toDouble(), ui.offset.rightM) },
            )
            CompactSlider(
                label = "좌우",
                value = ui.offset.rightM.toFloat(),
                onChange = { vm.setOffset(ui.offset.forwardM, it.toDouble()) },
            )
            CompactSlider(
                label = "복구",
                value = ui.autoStopMinutes.toFloat(),
                onChange = { vm.setAutoStopMinutes(it.roundToInt()) },
                range = 1f..15f,
                valueText = "${ui.autoStopMinutes}분",
            )
        }
    }
}

@Composable
private fun CompactSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = -6f..6f,
    valueText: String? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            modifier = Modifier.width(30.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(32.dp),
        )
        Text(
            valueText ?: "%.1fm".format(value),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(50.dp),
        )
    }
}

@Composable
private fun CompactButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        contentPadding = PaddingValues(2.dp),
    ) { Text(text, fontSize = 11.sp, maxLines = 1) }
}

@Composable
private fun CompactOutlined(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        contentPadding = PaddingValues(0.dp),
    ) { Text(text, fontSize = 13.sp, maxLines = 1) }
}

// ── 액션 ────────────────────────────────────────────────────────────────────

@Composable
private fun ActionButtons(ui: UiState, vm: AnchorViewModel, active: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = vm::refreshVehicle,
            enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) { Text("차량 위치 새로고침", fontSize = 13.sp) }

        if (!active) {
            Button(
                onClick = { vm.startAnchor() },
                enabled = ui.vehicleLocation != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("GPS 위치 고정 시작") }
        } else {
            Button(
                onClick = vm::stopAnchor,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("사용 종료 (원래 GPS 로 복구)") }
        }
    }
}

// ── 법적 고지 ───────────────────────────────────────────────────────────────

@Composable
private fun LegalNotice() {
    Text(
        "이 앱은 폰의 위치 정보를 실제와 다르게 표시합니다.\n" +
            "GPS 위치 변경으로 인해 발생하는 모든 법적 책임은 사용자 본인에게 있으며,\n" +
            "차량호출 사용 중에는 관련 법규를 준수하고 차량을 직접 주시해야 합니다.",
        style = MaterialTheme.typography.bodySmall,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

// ── BLE 설정 / 페어링 팝업 ──────────────────────────────────────────────────

@Composable
private fun SetupDialog(ui: UiState, vm: AnchorViewModel) {
    var vinText by remember { mutableStateOf(ui.vin) }
    val vinOk = vinText.trim().length == 17

    Dialog(onDismissRequest = { if (ui.configured) vm.closeSetup() }) {
        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "BLE 연결 설정",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Tesla 계정 없이 차량과 블루투스로 직접 연결합니다. " +
                        "처음 한 번, 이 앱의 키를 차량에 등록(페어링)해야 합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = vinText,
                    onValueChange = { vinText = it.uppercase().take(17) },
                    label = { Text("차량 VIN (17자리)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (ui.role == com.dali.teslagps.tesla.Messages.ROLE_OWNER) "키 역할: Owner" else "키 역할: Driver (기본)",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                    )
                    Switch(
                        checked = ui.role == com.dali.teslagps.tesla.Messages.ROLE_OWNER,
                        onCheckedChange = vm::setRole,
                    )
                }
                Text(
                    "위치가 읽히지 않고 '권한 부족' 오류가 나면 Owner 로 바꿔 새 키로 다시 페어링하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                Text(
                    "① 차 화면이 켜진 상태에서 아래 버튼을 누르세요.\n" +
                        "② 누른 뒤 바로(약 35초 이내) 센터콘솔의 NFC 카드 리더(컵홀더 뒤쪽)에 키카드를 대세요. " +
                        "차량은 요청을 받아도 아무 반응이 없는 것이 정상이며, 앱의 안내를 기다릴 필요가 없습니다.\n" +
                        "③ 카드를 대면 차량 화면에 확인(Confirm)이 뜹니다. 누르면 완료되고 앱이 자동으로 확인합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Button(
                    onClick = {
                        vm.saveVin(vinText)
                        vm.requestPairing()
                    },
                    enabled = vinOk && !ui.loading,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) { Text("① 페어링 요청 보내기") }

                Button(
                    onClick = {
                        vm.saveVin(vinText)
                        vm.confirmPairing()
                    },
                    enabled = vinOk && !ui.loading,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) { Text("페어링 확인 (이미 등록했다면)") }

                if (ui.loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(ui.statusText, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                } else if (ui.statusText.isNotBlank()) {
                    Text(ui.statusText, fontSize = 11.sp, lineHeight = 15.sp)
                }

                HorizontalDivider()

                Text(
                    "키 지문: ${ui.keyFingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = vm::resetKey,
                    enabled = !ui.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("키 새로 만들기 (재페어링 필요)", fontSize = 12.sp) }

                if (ui.configured) {
                    TextButton(onClick = vm::closeSetup, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
                }
            }
        }
    }
}
