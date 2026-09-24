package com.dali.teslagps.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.dali.teslagps.diag.DiagLog
import java.util.concurrent.atomic.AtomicReference

/**
 * 시스템 위치 프로바이더에 모의 위치를 반복 주입한다.
 *
 * 반복 주입이 필요한 이유: 위치를 구독하는 앱들은 "최신 값"만 신뢰하므로
 * 1회만 넣으면 수 초 내에 오래된 값으로 판정되어 실제 위치로 되돌아간다.
 *
 * 사전 조건
 *   1. 개발자 옵션 활성화
 *   2. 개발자 옵션 → "모의 위치 앱 선택" 에서 이 앱을 지정
 *   3. ACCESS_FINE_LOCATION 런타임 권한 승인
 */
class MockLocationInjector(context: Context) {

    private val lm = context.applicationContext
        .getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val target = AtomicReference<Geo?>(null)
    private val added = mutableSetOf<String>()

    @Volatile
    private var running = false

    private val providers: List<String> = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        // FUSED_PROVIDER 는 API 31+ 에만 존재한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }

    val isRunning: Boolean get() = running

    /**
     * 주입을 시작한다. 이미 실행 중이면 목표 좌표만 교체한다.
     * @return null 이면 성공, 아니면 실패 사유
     */
    @SuppressLint("MissingPermission")
    fun start(initial: Geo): String? {
        target.set(initial)
        if (running) return null

        try {
            providers.forEach { p ->
                // 이전 세션의 잔재가 남아 있으면 addTestProvider 가 실패한다.
                runCatching { lm.removeTestProvider(p) }
                lm.addTestProvider(
                    p,
                    false,  // requiresNetwork
                    false,  // requiresSatellite
                    false,  // requiresCell
                    false,  // hasMonetaryCost
                    true,   // supportsAltitude
                    true,   // supportsSpeed
                    true,   // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE,
                )
                lm.setTestProviderEnabled(p, true)
                added += p
            }
        } catch (e: SecurityException) {
            DiagLog.e("ANCHOR", "모의 위치 앱 미지정(SecurityException)", e)
            cleanup()
            return "모의 위치 앱으로 지정되지 않았습니다.\n" +
                "설정 → 개발자 옵션 → 모의 위치 앱 선택 에서 이 앱을 지정해 주세요."
        } catch (e: Exception) {
            DiagLog.e("ANCHOR", "테스트 프로바이더 등록 실패", e)
            cleanup()
            return "위치 프로바이더 등록 실패: ${e.message}"
        }

        running = true
        DiagLog.i("ANCHOR", "모의 위치 시작 providers=${added.joinToString()} target=%.6f,%.6f".format(initial.lat, initial.lng))
        return null
    }

    /** 실행 중 목표 좌표만 변경한다. (미세조정 슬라이더용) */
    fun updateTarget(geo: Geo) {
        target.set(geo)
    }

    /**
     * 현재 목표 좌표를 1회 주입한다. 서비스의 반복 루프에서 호출한다.
     * @return 계속 진행 가능하면 true
     */
    @SuppressLint("MissingPermission")
    fun pushOnce(accuracyM: Float = 3.0f): Boolean {
        if (!running) return false
        val t = target.get() ?: return true
        val now = System.currentTimeMillis()

        return try {
            added.forEach { p ->
                val loc = Location(p).apply {
                    latitude = t.lat
                    longitude = t.lng
                    altitude = 0.0
                    accuracy = accuracyM
                    speed = 0f
                    bearing = 0f
                    time = now
                    // API 17+ 필수. 누락하면 프레임워크가 조용히 위치를 버린다.
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 3f
                        speedAccuracyMetersPerSecond = 0.1f
                        bearingAccuracyDegrees = 1f
                    }
                }
                lm.setTestProviderLocation(p, loc)
            }
            true
        } catch (e: SecurityException) {
            // 사용 중 모의 위치 앱 지정이 해제된 경우
            Log.w(TAG, "mock permission lost", e)
            DiagLog.e("ANCHOR", "모의 위치 권한이 해제됨", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "push failed", e)
            DiagLog.e("ANCHOR", "위치 주입 실패", e)
            false
        }
    }

    /** 주입을 중단하고 실제 GPS 로 즉시 복귀시킨다. */
    fun stop() {
        if (running) DiagLog.i("ANCHOR", "모의 위치 중단 — 실제 GPS 로 복구")
        running = false
        target.set(null)
        cleanup()
    }

    private fun cleanup() {
        added.forEach { p ->
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        added.clear()
    }

    companion object {
        private const val TAG = "MockInjector"
    }
}
