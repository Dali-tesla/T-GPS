package com.dali.teslagps.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dali.teslagps.ble.TeslaBle
import com.dali.teslagps.core.AnchorGeometry
import com.dali.teslagps.core.AnchorService
import com.dali.teslagps.core.Geo
import com.dali.teslagps.core.Offset
import com.dali.teslagps.core.VehicleLocation
import com.dali.teslagps.data.Prefs
import com.dali.teslagps.diag.DiagLog
import com.dali.teslagps.tesla.Messages
import com.dali.teslagps.tesla.PairOutcome
import com.dali.teslagps.tesla.VehicleClient
import com.dali.teslagps.tesla.VehicleError
import com.dali.teslagps.tesla.VehicleException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    /** VIN 이 입력되고 이 앱의 BLE 키가 차량에 등록된 상태 */
    val configured: Boolean = false,
    val showSetup: Boolean = false,
    val loading: Boolean = false,
    val statusText: String = "",
    val error: String? = null,

    val vehicleName: String = "",
    val vin: String = "",
    val role: Long = Messages.ROLE_DRIVER,
    val keyFingerprint: String = "",
    val vehicleLocation: VehicleLocation? = null,

    val offset: Offset = Offset(4.0, 0.0),
    val autoStopMinutes: Int = 5,
) {
    /** 현재 오프셋을 적용한 폰의 목표 좌표 */
    val targetGeo: Geo?
        get() = vehicleLocation?.let {
            AnchorGeometry.resolve(it.geo, it.headingDeg, offset)
        }

    val outOfRange: Boolean get() = offset.distanceM > AnchorGeometry.SUMMON_RADIUS_M
}

class AnchorViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    private val _ui = MutableStateFlow(
        UiState(
            configured = prefs.isConfigured,
            offset = prefs.offset,
            autoStopMinutes = prefs.autoStopMinutes,
            vin = prefs.vin,
            vehicleName = nameFor(prefs.vin),
            role = prefs.role,
            keyFingerprint = prefs.keyFingerprint(),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val active = com.dali.teslagps.core.AnchorState.active
    val remainingSec = com.dali.teslagps.core.AnchorState.remainingSec

    init {
        DiagLog.init(app)
    }

    private fun nameFor(vin: String) = if (vin.length == 17) "Tesla …${vin.takeLast(6)}" else ""

    fun dismissError() = _ui.update { it.copy(error = null) }
    fun openSetup() = _ui.update { it.copy(showSetup = true) }
    fun closeSetup() = _ui.update { it.copy(showSetup = false) }

    fun setAutoStopMinutes(min: Int) {
        prefs.autoStopMinutes = min
        _ui.update { it.copy(autoStopMinutes = min) }
    }

    // ── BLE 설정: VIN / 역할 / 키 ──────────────────────────────────────────

    fun saveVin(raw: String) {
        val vin = raw.trim().uppercase()
        if (vin != prefs.vin) prefs.paired = false // 다른 차량이면 다시 페어링해야 한다
        prefs.vin = vin
        _ui.update {
            it.copy(
                vin = vin,
                vehicleName = nameFor(vin),
                configured = prefs.isConfigured,
                vehicleLocation = if (vin == it.vin) it.vehicleLocation else null,
            )
        }
    }

    fun setRole(owner: Boolean) {
        val r = if (owner) Messages.ROLE_OWNER else Messages.ROLE_DRIVER
        prefs.role = r
        _ui.update { it.copy(role = r) }
    }

    /** 새 키를 만든다. 차량에 다시 페어링해야 한다. */
    fun resetKey() {
        prefs.newKey()
        _ui.update {
            it.copy(
                keyFingerprint = prefs.keyFingerprint(),
                configured = false,
                vehicleLocation = null,
                statusText = "새 키를 만들었습니다. 다시 페어링하세요.",
            )
        }
    }

    // ── BLE 실행 공통 ───────────────────────────────────────────────────────

    private fun hasBlePermissions(): Boolean {
        val ctx = getApplication<Application>()
        fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ok(Manifest.permission.BLUETOOTH_SCAN) &&
                ok(Manifest.permission.BLUETOOTH_CONNECT) &&
                ok(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            ok(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun <T> runBle(title: String, block: (VehicleClient) -> T, onOk: (T) -> Unit) {
        if (_ui.value.loading) return
        val vin = prefs.vin
        if (vin.length != 17) {
            DiagLog.w("ACTION", "$title 중단: VIN 형식 오류(${vin.length}자)")
            _ui.update { it.copy(error = "VIN(17자리)을 먼저 입력해 주세요.") }
            return
        }
        if (!hasBlePermissions()) {
            DiagLog.w("ACTION", "$title 중단: 블루투스/위치 권한 없음")
            _ui.update { it.copy(error = "블루투스·위치 권한이 필요합니다. 앱 설정에서 권한을 허용해 주세요.") }
            return
        }
        DiagLog.i("ACTION", "시작: $title (VIN …${vin.takeLast(6)})")
        _ui.update { it.copy(loading = true, error = null, statusText = title) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TeslaBle.withVehicle(
                        getApplication(),
                        vin,
                        prefs.key(),
                        { msg ->
                            DiagLog.i("BLE", msg)
                            _ui.update { s -> s.copy(statusText = msg) }
                        },
                        { msg -> DiagLog.d("BLE", msg) },
                        block,
                    )
                }
                DiagLog.i("ACTION", "성공: $title")
                onOk(result)
            } catch (e: VehicleException) {
                DiagLog.e("ACTION", "실패: $title → ${e.kind} ${e.message}", e)
                if (e.kind == VehicleError.NOT_PAIRED) {
                    prefs.paired = false
                    _ui.update { it.copy(configured = false, showSetup = true) }
                }
                _ui.update { it.copy(loading = false, statusText = "", error = e.message ?: "차량 통신 오류") }
            } catch (e: SecurityException) {
                DiagLog.e("ACTION", "실패: $title → 권한 예외", e)
                _ui.update { it.copy(loading = false, statusText = "", error = "블루투스·위치 권한이 필요합니다.") }
            } catch (e: Exception) {
                DiagLog.e("ACTION", "실패: $title → 예기치 못한 오류", e)
                _ui.update { it.copy(loading = false, statusText = "", error = e.message ?: "알 수 없는 오류") }
            }
        }
    }

    /**
     * ① 차량에 이 앱의 키 등록을 요청하고 최대 75초 기다린다.
     * 요청 직후 차량은 아무 반응도 하지 않는 것이 정상이다. 바로(약 35초 이내) NFC 카드를 태그해야
     * 차량 화면에 확인 버튼이 나온다. (실측: 차량은 요청 접수 신호를 보내지 않았고, 시간 초과 시 코드 25 를 보냄)
     */
    fun requestPairing() {
        runBle(
            "페어링 요청 전송 중…",
            { client -> client.pairWithCard(prefs.role) },
            { result ->
                DiagLog.i("PAIR", "결과: ${result.outcome} ${result.message}")
                when (result.outcome) {
                    PairOutcome.APPROVED -> {
                        _ui.update { it.copy(loading = false, statusText = "등록 승인됨 — 확인 중…") }
                        confirmPairing()
                    }
                    PairOutcome.REJECTED -> _ui.update {
                        it.copy(loading = false, statusText = "", error = "페어링 거부: ${result.message}")
                    }
                    PairOutcome.TIMEOUT_AFTER_ACK -> _ui.update {
                        it.copy(
                            loading = false,
                            statusText = "",
                            error = "${result.message}\n\n카드를 태그하고 차량 화면에서 확인까지 눌렀다면 [페어링 확인]을 눌러 보세요. 아니면 ①을 다시 눌러 처음부터 하세요.",
                        )
                    }
                    PairOutcome.NO_REPLY -> _ui.update {
                        it.copy(
                            loading = false,
                            statusText = "",
                            error = "${result.message}\n\n차 화면이 켜져 있는지, 폰이 차 가까이 있는지 확인하고 ①을 다시 누른 뒤 바로 카드를 대세요. ",
                        )
                    }
                }
            },
        )
    }

    /** ② 차량이 이 앱의 키를 인식하는지 확인한다. */
    fun confirmPairing() {
        runBle(
            "페어링 확인 중…",
            { client -> client.isPaired() },
            { paired ->
                prefs.paired = paired
                _ui.update {
                    it.copy(
                        loading = false,
                        configured = prefs.isConfigured,
                        statusText = if (paired) "페어링 완료" else "",
                        error = if (paired) null else "아직 등록되지 않았습니다. 차량 화면 승인과 NFC 카드 태그를 확인해 주세요.",
                    )
                }
                if (paired) refreshVehicle()
            },
        )
    }

    // ── 차량 위치 (BLE) ─────────────────────────────────────────────────────

    fun refreshVehicle() {
        if (!prefs.isConfigured) {
            _ui.update { it.copy(showSetup = true) }
            return
        }
        runBle(
            "차량 검색 중…",
            { client -> client.readLocation() },
            { pos ->
                val heading = pos.heading
                val loc = VehicleLocation(
                    geo = Geo(pos.latitude, pos.longitude),
                    headingDeg = (heading ?: 0).toDouble(),
                    gpsAsOf = pos.gpsAsOfEpochSec ?: 0L,
                    headingAssumed = heading == null,
                )
                prefs.paired = true
                DiagLog.i("LOC", "위치 수신: %.6f, %.6f heading=%s (%s)".format(pos.latitude, pos.longitude, heading?.toString() ?: "없음", pos.source))
                _ui.update {
                    it.copy(
                        loading = false,
                        vehicleLocation = loc,
                        configured = true,
                        statusText = if (heading == null) "위치 확인 (방향 정보 없음 — 북쪽 기준으로 계산)" else "차량 위치 확인 완료",
                    )
                }
            },
        )
    }

    // ── 오프셋 조정 ─────────────────────────────────────────────────────────

    fun setOffset(forwardM: Double, rightM: Double) {
        val o = Offset(forwardM, rightM)
        prefs.offset = o
        _ui.update { it.copy(offset = o) }
        // 주입 중이면 목표 좌표를 즉시 반영한다.
        if (active.value) {
            _ui.value.copy(offset = o).targetGeo?.let {
                AnchorService.update(getApplication(), it)
            }
        }
    }

    fun nudge(dForward: Double, dRight: Double) {
        val o = _ui.value.offset
        setOffset(
            (o.forwardM + dForward).coerceIn(-6.0, 6.0),
            (o.rightM + dRight).coerceIn(-6.0, 6.0),
        )
    }

    fun applyPreset(front: Boolean, distanceM: Double) {
        setOffset(if (front) distanceM else -distanceM, 0.0)
    }

    // ── 주입 시작/종료 ──────────────────────────────────────────────────────

    fun startAnchor() {
        val target = _ui.value.targetGeo
        if (target == null) {
            _ui.update { it.copy(error = "먼저 차량 위치를 조회해 주세요.") }
            return
        }
        DiagLog.i("ANCHOR", "시작 요청: 오프셋 ${AnchorGeometry.describe(_ui.value.offset)}, 자동복구 ${_ui.value.autoStopMinutes}분")
        AnchorService.start(getApplication(), target, _ui.value.autoStopMinutes)
    }

    fun stopAnchor() {
        DiagLog.i("ANCHOR", "사용 종료 요청")
        AnchorService.stop(getApplication())
    }
}
