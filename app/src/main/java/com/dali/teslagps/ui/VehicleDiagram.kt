package com.dali.teslagps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset as DrawOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dali.teslagps.core.AnchorGeometry
import com.dali.teslagps.core.Offset as AnchorOffset
import kotlin.math.min

/**
 * 차량 기준 상대 위치를 레이더 스타일로 표현한다.
 *
 * 지도 타일을 쓰지 않는 이유: 지하주차장에서는 위성사진이나 도로지도가
 * 아무 의미가 없고, 실제로 필요한 정보는 "차 기준으로 내가 어디에
 * 서 있는가" 뿐이다. 검은 배경에 네온 링으로 그려 레이더/HUD 느낌을 준다.
 *
 * 화면은 항상 차량 진행방향이 위쪽을 향하도록 그린다(차량 고정 시점).
 */

private val RadarBg = Color(0xFF06060A)
private val GridLine = Color(0xFF1C2A28)
private val RingDim = Color(0xFF2A3A38)
private val RingMain = Color(0xFF00E5C7)
private val CarFill = Color(0xFFB8FFF3)
private val CarStroke = Color(0xFF00E5C7)
private val CarGlass = Color(0xFF0A2E29)
private val PhoneColor = Color(0xFFFF5470)
private val PhoneColorOut = Color(0xFFFF1744)

@Composable
fun VehicleDiagram(
    offset: AnchorOffset,
    modifier: Modifier = Modifier,
    hasVehicle: Boolean = true,
) {
    val measurer = rememberTextMeasurer()

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            drawRoundRect(RadarBg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(20f, 20f))

            val center = DrawOffset(size.width / 2f, size.height / 2f)
            val radiusPx = min(size.width, size.height) * 0.42f
            val pxPerMeter = radiusPx / AnchorGeometry.SUMMON_RADIUS_M.toFloat()

            drawGrid(center, radiusPx)
            drawRangeRings(center, pxPerMeter, measurer)

            if (!hasVehicle) {
                val hint = measurer.measure(
                    "차량 위치 없음",
                    TextStyle(color = Color(0xFF5A5A66), fontSize = 11.sp),
                )
                drawText(
                    hint,
                    topLeft = DrawOffset(
                        center.x - hint.size.width / 2f,
                        center.y - hint.size.height / 2f,
                    ),
                )
                return@Canvas
            }

            drawCarTopDown(center, pxPerMeter)

            val phone = DrawOffset(
                center.x + offset.rightM.toFloat() * pxPerMeter,
                center.y - offset.forwardM.toFloat() * pxPerMeter,
            )
            val outOfRange = offset.distanceM > AnchorGeometry.SUMMON_RADIUS_M

            drawConnector(center, phone, outOfRange)
            drawPhoneMarker(phone, outOfRange)
        }
    }
}

/** 은은한 배경 격자 — 레이더 화면 느낌을 더한다 */
private fun DrawScope.drawGrid(center: DrawOffset, radiusPx: Float) {
    val step = radiusPx / 3f
    var x = center.x - radiusPx
    while (x <= center.x + radiusPx) {
        drawLine(GridLine, DrawOffset(x, center.y - radiusPx), DrawOffset(x, center.y + radiusPx), 1f)
        x += step
    }
    var y = center.y - radiusPx
    while (y <= center.y + radiusPx) {
        drawLine(GridLine, DrawOffset(center.x - radiusPx, y), DrawOffset(center.x + radiusPx, y), 1f)
        y += step
    }
}

/** 6m(네온 실선) 및 2m/4m(보조 점선) 반경 링 */
private fun DrawScope.drawRangeRings(
    center: DrawOffset,
    pxPerMeter: Float,
    measurer: TextMeasurer,
) {
    val dashed = PathEffect.dashPathEffect(floatArrayOf(5f, 9f), 0f)

    listOf(2f, 4f).forEach { m ->
        drawCircle(
            color = RingDim,
            radius = m * pxPerMeter,
            center = center,
            style = Stroke(width = 1.5f, pathEffect = dashed),
        )
    }

    val r6 = AnchorGeometry.SUMMON_RADIUS_M.toFloat() * pxPerMeter

    // 은은한 발광 느낌: 반투명 채움 + 두 겹 스트로크
    drawCircle(color = RingMain.copy(alpha = 0.06f), radius = r6, center = center)
    drawCircle(color = RingMain.copy(alpha = 0.25f), radius = r6, center = center, style = Stroke(width = 7f))
    drawCircle(color = RingMain, radius = r6, center = center, style = Stroke(width = 2f))

    // 중심 십자선
    drawLine(RingDim, DrawOffset(center.x - 8f, center.y), DrawOffset(center.x + 8f, center.y), 1.5f)
    drawLine(RingDim, DrawOffset(center.x, center.y - 8f), DrawOffset(center.x, center.y + 8f), 1.5f)

    val label = measurer.measure(
        "6m",
        TextStyle(color = RingMain, fontSize = 11.sp, fontWeight = FontWeight.Bold),
    )
    drawText(
        label,
        topLeft = DrawOffset(center.x - label.size.width / 2f, center.y - r6 - label.size.height - 3f),
    )
}

/** 위에서 내려다본 차량. 진행방향은 항상 화면 위쪽. 네온 아웃라인 스타일. */
private fun DrawScope.drawCarTopDown(center: DrawOffset, pxPerMeter: Float) {
    val halfLen = 4.75f / 2f * pxPerMeter
    val halfWid = 1.92f / 2f * pxPerMeter
    val corner = halfWid * 0.35f

    translate(center.x, center.y) {
        val body = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = -halfWid, top = -halfLen, right = halfWid, bottom = halfLen,
                    radiusX = corner, radiusY = corner,
                )
            )
        }
        // 은은한 발광 느낌의 채움 + 네온 아웃라인
        drawPath(body, CarFill.copy(alpha = 0.12f))
        drawPath(body, CarStroke, style = Stroke(width = 2.5f))

        val glass = Path().apply {
            moveTo(-halfWid * 0.72f, -halfLen * 0.60f)
            lineTo(halfWid * 0.72f, -halfLen * 0.60f)
            lineTo(halfWid * 0.55f, -halfLen * 0.26f)
            lineTo(-halfWid * 0.55f, -halfLen * 0.26f)
            close()
        }
        drawPath(glass, CarGlass)

        val rear = Path().apply {
            moveTo(-halfWid * 0.55f, halfLen * 0.30f)
            lineTo(halfWid * 0.55f, halfLen * 0.30f)
            lineTo(halfWid * 0.68f, halfLen * 0.60f)
            lineTo(-halfWid * 0.68f, halfLen * 0.60f)
            close()
        }
        drawPath(rear, CarGlass.copy(alpha = 0.7f))

        val arrow = Path().apply {
            moveTo(0f, -halfLen - 11f)
            lineTo(-5f, -halfLen - 2f)
            lineTo(5f, -halfLen - 2f)
            close()
        }
        drawPath(arrow, RingMain)
    }
}

/** 차량 중심과 폰 위치를 잇는 점선 */
private fun DrawScope.drawConnector(from: DrawOffset, to: DrawOffset, outOfRange: Boolean) {
    drawLine(
        color = if (outOfRange) PhoneColorOut.copy(alpha = 0.6f) else Color(0xFF5A5A66),
        start = from,
        end = to,
        strokeWidth = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 6f), 0f),
    )
}

/** 폰 위치 마커. 6m 를 벗어나면 더 강한 붉은색으로 표시한다. */
private fun DrawScope.drawPhoneMarker(at: DrawOffset, outOfRange: Boolean) {
    val color = if (outOfRange) PhoneColorOut else PhoneColor
    drawCircle(color.copy(alpha = 0.22f), radius = 15f, center = at)
    drawCircle(color, radius = 6.5f, center = at)
    drawCircle(Color(0xFF0A0A0D), radius = 6.5f, center = at, style = Stroke(width = 2f))
}
