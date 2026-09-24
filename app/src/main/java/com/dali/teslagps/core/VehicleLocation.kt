package com.dali.teslagps.core

/** UI/앵커 계산에 쓰는 차량 위치. BLE 로 읽은 값(VehiclePosition)에서 변환한다. */
data class VehicleLocation(
    val geo: Geo,
    val headingDeg: Double,
    /** GPS 측정 시각(epoch seconds). 0 이면 알 수 없음. */
    val gpsAsOf: Long,
    /** 차량이 heading 을 주지 않아 0°(북)로 가정했는가 */
    val headingAssumed: Boolean = false,
)
