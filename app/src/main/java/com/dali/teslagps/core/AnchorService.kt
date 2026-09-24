package com.dali.teslagps.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.dali.teslagps.MainActivity
import com.dali.teslagps.R
import com.dali.teslagps.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 앱 UI 와 서비스가 공유하는 상태.
 * 바인딩 없이 상태만 주고받으면 되므로 단일 객체로 노출한다.
 */
object AnchorState {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _remainingSec = MutableStateFlow(0)
    val remainingSec: StateFlow<Int> = _remainingSec.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal fun setActive(v: Boolean) { _active.value = v }
    internal fun setRemaining(v: Int) { _remainingSec.value = v }
    internal fun setError(v: String?) { _error.value = v }
    fun clearError() { _error.value = null }
}

/**
 * 모의 위치 주입을 담당하는 포그라운드 서비스.
 *
 * - 1초 주기로 목표 좌표를 재주입한다.
 * - 자동 복구 타이머(기본 5분) 만료 시 스스로 종료하며 실제 GPS 로 되돌린다.
 * - 알림의 "사용 종료" 버튼으로 즉시 중단할 수 있다.
 */
class AnchorService : Service() {

    private lateinit var injector: MockLocationInjector
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var deadlineElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        injector = MockLocationInjector(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_UPDATE -> handleUpdate(intent)
            ACTION_STOP -> stopEverything()
            else -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        val minutes = intent.getIntExtra(EXTRA_MINUTES, DEFAULT_MINUTES)
        if (lat.isNaN() || lng.isNaN()) {
            stopEverything()
            return
        }

        val failure = injector.start(Geo(lat, lng))
        if (failure != null) {
            DiagLog.e("ANCHOR", "앵커 시작 실패: ${failure.replace('\n', ' ')}")
            AnchorState.setError(failure)
            stopEverything()
            return
        }

        deadlineElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        AnchorState.setActive(true)
        AnchorState.setError(null)

        startForeground(NOTIF_ID, buildNotification(minutes * 60))

        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        job = s.launch {
            while (isActive) {
                val remainMs = deadlineElapsed - SystemClock.elapsedRealtime()
                if (remainMs <= 0) {
                    DiagLog.i("ANCHOR", "자동 복구 타이머 만료")
                    // 자동 복구: 사용자가 종료를 잊어도 원래 GPS 로 돌아간다.
                    withMain { stopEverything() }
                    break
                }
                if (!injector.pushOnce()) {
                    DiagLog.e("ANCHOR", "pushOnce 실패로 중단")
                    AnchorState.setError("모의 위치 권한이 해제되어 중단했습니다.")
                    withMain { stopEverything() }
                    break
                }
                val remainSec = (remainMs / 1000).toInt()
                AnchorState.setRemaining(remainSec)
                notifyManager().notify(NOTIF_ID, buildNotification(remainSec))
                delay(1_000L)
            }
        }
    }

    private fun handleUpdate(intent: Intent) {
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        if (!lat.isNaN() && !lng.isNaN() && injector.isRunning) {
            injector.updateTarget(Geo(lat, lng))
        }
    }

    private suspend fun withMain(block: () -> Unit) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
    }

    private fun stopEverything() {
        job?.cancel()
        scope?.cancel()
        scope = null
        injector.stop()
        AnchorState.setActive(false)
        AnchorState.setRemaining(0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 서비스가 어떤 경로로 종료되든 실제 GPS 로 반드시 복귀시킨다.
        injector.stop()
        AnchorState.setActive(false)
        AnchorState.setRemaining(0)
        scope?.cancel()
        super.onDestroy()
    }

    // ── 알림 ────────────────────────────────────────────────────────────────

    private fun notifyManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "GPS 고정",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "차량호출용 위치 고정이 동작 중임을 알립니다." }
            notifyManager().createNotificationChannel(ch)
        }
    }

    private fun buildNotification(remainSec: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AnchorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val mm = remainSec / 60
        val ss = remainSec % 60

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_anchor)
            .setContentTitle("GPS 위치 고정 중")
            .setContentText("%d분 %02d초 후 자동 복구".format(mm, ss))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "사용 종료", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.dali.teslagps.START"
        const val ACTION_UPDATE = "com.dali.teslagps.UPDATE"
        const val ACTION_STOP = "com.dali.teslagps.STOP"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_MINUTES = "minutes"

        const val DEFAULT_MINUTES = 5

        private const val CHANNEL_ID = "anchor"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context, geo: Geo, minutes: Int = DEFAULT_MINUTES) {
            val i = Intent(ctx, AnchorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, geo.lat)
                .putExtra(EXTRA_LNG, geo.lng)
                .putExtra(EXTRA_MINUTES, minutes)
            ctx.startForegroundService(i)
        }

        fun update(ctx: Context, geo: Geo) {
            val i = Intent(ctx, AnchorService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_LAT, geo.lat)
                .putExtra(EXTRA_LNG, geo.lng)
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, AnchorService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
