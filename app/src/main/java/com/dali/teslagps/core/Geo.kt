package com.dali.teslagps.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 위경도 좌표 */
data class Geo(val lat: Double, val lng: Double)

/**
 * 차량을 기준으로 한 상대 위치.
 *
 * @param forwardM 차량 진행방향(+전방 / -후방) 오프셋, 단위 m
 * @param rightM   차량 오른쪽(+우 / -좌) 오프셋, 단위 m
 */
data class Offset(val forwardM: Double, val rightM: Double) {
    /** 차량 중심으로부터의 직선거리(m) */
    val distanceM: Double get() = hypot(forwardM, rightM)
}

object AnchorGeometry {

    /** 구형 Smart Summon 의 폰-차량 거리 상한 */
    const val SUMMON_RADIUS_M = 6.0

    private const val M_PER_DEG_LAT = 111_320.0

    /**
     * 차량 좌표/헤딩 기준으로 사용자가 설 지점의 절대 좌표를 계산한다.
     *
     * heading 은 차량이 향한 방향(진북 기준 시계방향 0~359).
     * 전방 단위벡터는 (북, 동) = (cos h, sin h),
     * 우측 단위벡터는 (북, 동) = (-sin h, cos h) 이다.
     *
     * 수 미터 범위에서는 구면 계산과 평면 근사의 차이가 1cm 미만이므로
     * 평면 근사를 사용한다.
     */
    fun resolve(car: Geo, headingDeg: Double, offset: Offset): Geo {
        val h = Math.toRadians(headingDeg)

        val dNorth = offset.forwardM * cos(h) - offset.rightM * sin(h)
        val dEast = offset.forwardM * sin(h) + offset.rightM * cos(h)

        val dLat = dNorth / M_PER_DEG_LAT
        // 위도가 높을수록 경도 1도의 실제 거리가 짧아진다.
        val dLng = dEast / (M_PER_DEG_LAT * cos(Math.toRadians(car.lat)).coerceAtLeast(1e-6))

        return Geo(car.lat + dLat, car.lng + dLng)
    }

    /** 두 좌표 사이의 근사 거리(m). 짧은 거리 전용. */
    fun distanceM(a: Geo, b: Geo): Double {
        val dNorth = (b.lat - a.lat) * M_PER_DEG_LAT
        val dEast = (b.lng - a.lng) * M_PER_DEG_LAT * cos(Math.toRadians(a.lat))
        return hypot(dNorth, dEast)
    }

    /** 오프셋이 Summon 반경을 벗어나면 방향을 유지한 채 반경 안쪽으로 당긴다. */
    fun clampToRadius(offset: Offset, radiusM: Double = SUMMON_RADIUS_M): Offset {
        val d = offset.distanceM
        if (d <= radiusM || d < 1e-9) return offset
        val k = radiusM / d
        return Offset(offset.forwardM * k, offset.rightM * k)
    }

    /** UI 표시용: "전방 4.0m / 우 1.5m" 같은 문자열 */
    fun describe(offset: Offset): String {
        val fwd = if (offset.forwardM >= 0) "전방 %.1fm".format(offset.forwardM)
        else "후방 %.1fm".format(abs(offset.forwardM))
        val side = when {
            abs(offset.rightM) < 0.05 -> "정중앙"
            offset.rightM > 0 -> "우 %.1fm".format(offset.rightM)
            else -> "좌 %.1fm".format(abs(offset.rightM))
        }
        return "$fwd / $side"
    }
}
