// Portions of this file are a Kotlin port of logic and message definitions from
// teslamotors/vehicle-command (https://github.com/teslamotors/vehicle-command),
// Copyright Tesla, Inc., licensed under the Apache License 2.0.
// Modified: rewritten in Kotlin for Android BLE. Unofficial; not affiliated with Tesla, Inc.
// See the NOTICE and LICENSE files in the repository root.

package com.dali.teslagps.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.dali.teslagps.tesla.KeyMaterial
import com.dali.teslagps.tesla.Transport
import com.dali.teslagps.tesla.VehicleClient
import com.dali.teslagps.tesla.VehicleError
import com.dali.teslagps.tesla.VehicleException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Tesla 차량 BLE 식별자 (vehicle-command/pkg/protocol/protocol.md) */
object TeslaUuids {
    val SERVICE: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
    val TO_VEHICLE: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
    val FROM_VEHICLE: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** 안드로이드 GATT status 코드를 사람이 읽을 수 있게 */
fun gattStatusName(status: Int): String = when (status) {
    0 -> "SUCCESS"
    8 -> "CONN_TIMEOUT(8)"
    19 -> "REMOTE_TERMINATED(19) 차량이 연결을 끊음"
    22 -> "LOCAL_TERMINATED(22) 폰이 연결을 끊음"
    62 -> "CONN_FAIL_ESTABLISH(62)"
    133 -> "GATT_ERROR(133) 일반 연결 실패(재시도/블루투스 껐다 켜기)"
    257 -> "GATT_FAILURE(257)"
    else -> "status $status"
}

object TeslaBle {
    /**
     * 차량을 찾아 GATT 로 연결하고 [block] 을 실행한 뒤 반드시 연결을 끊는다.
     * 블로킹 호출이므로 반드시 백그라운드 스레드에서 부를 것.
     */
    fun <T> withVehicle(
        context: Context,
        vin: String,
        key: KeyMaterial,
        log: (String) -> Unit,
        trace: (String) -> Unit,
        block: (VehicleClient) -> T,
    ): T {
        val name = VehicleClient.advertisedName(vin)
        log("차량 검색 중… ($name)")
        val found = BleScan.findVehicle(context, name, 20_000, trace)
        if (!found.isConnectable) {
            throw VehicleException(
                VehicleError.TRANSPORT,
                "차량이 BLE 연결 슬롯 3개를 모두 쓰는 중입니다. 폰키/키카드 등 다른 연결을 잠시 끊고 다시 시도하세요.",
            )
        }
        log("차량 발견 (RSSI ${found.rssi} dBm) — 연결 중")
        trace("스캔 결과: rssi=${found.rssi} connectable=${found.isConnectable}")

        var lastError: Exception? = null
        for (attempt in 1..3) {
            val link = GattLink(context, found.device, log, trace)
            try {
                link.open()
                return block(VehicleClient(link, vin, key, trace, log))
            } catch (e: VehicleException) {
                // 연결 단계 실패만 재시도, 프로토콜 오류는 그대로 전달
                if (e.kind != VehicleError.TRANSPORT) throw e
                lastError = e
                trace("연결 시도 $attempt/3 실패: ${e.message}")
                log("연결 재시도 ($attempt/3): ${e.message}")
                Thread.sleep(800)
            } finally {
                link.close()
            }
        }
        throw lastError ?: VehicleException(VehicleError.TRANSPORT, "BLE 연결 실패")
    }
}

object BleScan {
    @SuppressLint("MissingPermission")
    fun findVehicle(context: Context, localName: String, timeoutMs: Long, trace: (String) -> Unit = {}): ScanResult {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
            ?: throw VehicleException(VehicleError.TRANSPORT, "이 기기는 블루투스를 지원하지 않습니다")
        if (!adapter.isEnabled) throw VehicleException(VehicleError.TRANSPORT, "블루투스를 켜 주세요")
        val scanner = adapter.bluetoothLeScanner
            ?: throw VehicleException(VehicleError.TRANSPORT, "BLE 스캐너를 사용할 수 없습니다 (블루투스/위치 설정 확인)")

        val results = LinkedBlockingQueue<ScanResult>()
        var failure: Int? = null
        var seen = 0
        // 주변 Tesla 로 보이는 광고 이름(S + 16자리 hex + C) → 내 VIN 이름과 비교하려는 진단용
        val teslaLike = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val teslaName = Regex("^S[0-9a-f]{16}C$")
        trace("스캔 시작: 찾는 이름=$localName, 제한시간=${timeoutMs}ms, SDK=${Build.VERSION.SDK_INT}")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                seen++
                val name = result.scanRecord?.deviceName ?: try {
                    result.device.name
                } catch (e: SecurityException) {
                    null
                }
                if (name != null && teslaName.matches(name)) teslaLike[name] = result.rssi
                if (name == localName) results.offer(result)
            }

            override fun onScanFailed(errorCode: Int) {
                failure = errorCode
            }
        }

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    trace("스캔 종료(못 찾음): 광고 ${seen}건 수신, 주변 Tesla 이름=${teslaLike.entries.joinToString { "${it.key}(${it.value}dBm)" }.ifEmpty { "없음" }}")
                    throw VehicleException(
                        VehicleError.TIMEOUT,
                        "차량 BLE 신호를 찾지 못했습니다. 차량 가까이(수 m 이내)에서 다시 시도하세요. (지하주차장/차량 완전 절전 시 지연될 수 있음)",
                    )
                }
                val hit = results.poll(minOf(remaining, 500), TimeUnit.MILLISECONDS)
                if (hit != null) {
                    trace("스캔 성공: 광고 ${seen}건 중 발견")
                    return hit
                }
                failure?.let {
                    trace("스캔 실패 코드 $it")
                    throw VehicleException(VehicleError.TRANSPORT, "BLE 스캔 실패(코드 $it)")
                }
            }
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // 이미 종료된 경우 무시
            }
        }
    }
}

/**
 * 차량 GATT 연결. 메시지는 2바이트 big-endian 길이 헤더를 앞에 붙여 MTU 크기로 쪼개 쓰고,
 * 수신도 같은 방식으로 재조립한다 (pkg/connector/ble/ble.go 와 동일).
 */
@SuppressLint("MissingPermission")
class GattLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
    private val trace: (String) -> Unit = {},
) : Transport {

    private sealed class Ev {
        class State(val status: Int, val newState: Int) : Ev()
        class Services(val status: Int) : Ev()
        class DescriptorWrite(val status: Int) : Ev()
        class Mtu(val mtu: Int, val status: Int) : Ev()
        class CharWrite(val status: Int) : Ev()
    }

    private val events = LinkedBlockingQueue<Ev>()
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var blockLength = 20
    private var buffer = ByteArray(0)
    private var lastRxAt = 0L

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            trace("GATT 상태: newState=$newState status=${gattStatusName(status)}")
            events.offer(Ev.State(status, newState))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            trace("서비스 검색: ${gattStatusName(status)}")
            events.offer(Ev.Services(status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            trace("CCCD 쓰기: ${gattStatusName(status)}")
            events.offer(Ev.DescriptorWrite(status))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            trace("MTU: $mtu ${gattStatusName(status)}")
            events.offer(Ev.Mtu(mtu, status))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            events.offer(Ev.CharWrite(status))
        }

        // Android 13+ 는 value 를 인자로 전달
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onRx(value)
        }

        // Android 12 이하
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val v = characteristic.value
                if (v != null) onRx(v)
            }
        }
    }

    private fun fail(msg: String): Nothing = throw VehicleException(VehicleError.TRANSPORT, msg)

    private inline fun <reified T : Ev> await(timeoutMs: Long, what: String): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) fail("$what 시간 초과")
            val ev = events.poll(remaining, TimeUnit.MILLISECONDS) ?: fail("$what 시간 초과")
            if (ev is T) return ev
            if (ev is Ev.State && ev.newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("$what 중 연결이 끊어졌습니다 (${gattStatusName(ev.status)})")
            }
        }
    }

    fun open() {
        events.clear()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: fail("GATT 연결을 시작하지 못했습니다")
        gatt = g

        val state = await<Ev.State>(15_000, "GATT 연결")
        if (state.newState != BluetoothProfile.STATE_CONNECTED) fail("GATT 연결 실패 (${gattStatusName(state.status)})")

        if (!g.discoverServices()) fail("서비스 검색을 시작하지 못했습니다")
        val services = await<Ev.Services>(10_000, "서비스 검색")
        if (services.status != BluetoothGatt.GATT_SUCCESS) fail("서비스 검색 실패 (${gattStatusName(services.status)})")

        val service = g.getService(TeslaUuids.SERVICE) ?: run {
            trace("발견된 서비스: ${g.services.joinToString { it.uuid.toString().take(8) }}")
            fail("Tesla BLE 서비스를 찾지 못했습니다")
        }
        val tx = service.getCharacteristic(TeslaUuids.TO_VEHICLE) ?: fail("쓰기 특성을 찾지 못했습니다")
        val rx = service.getCharacteristic(TeslaUuids.FROM_VEHICLE) ?: fail("읽기 특성을 찾지 못했습니다")
        txChar = tx

        if (!g.setCharacteristicNotification(rx, true)) fail("알림 구독 실패")
        val cccd = rx.getDescriptor(TeslaUuids.CCCD) ?: fail("CCCD 를 찾지 못했습니다")
        // 차량은 indication 으로 응답을 보낸다 (SDK: Subscribe(rxChar, ind=true))
        val cccdValue = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        writeDescriptor(g, cccd, cccdValue)
        val dw = await<Ev.DescriptorWrite>(5_000, "알림 구독")
        if (dw.status != BluetoothGatt.GATT_SUCCESS) fail("알림 구독 실패 (${gattStatusName(dw.status)})")

        if (g.requestMtu(512)) {
            try {
                val mtu = await<Ev.Mtu>(3_000, "MTU 협상")
                if (mtu.status == BluetoothGatt.GATT_SUCCESS) blockLength = minOf(mtu.mtu, 1024) - 3
            } catch (e: VehicleException) {
                log("MTU 협상 생략 (기본값 사용)")
            }
        }
        log("BLE 연결 완료 (블록 ${blockLength}B)")
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (g.writeDescriptor(d, value) != BluetoothStatusCodes.SUCCESS) fail("CCCD 쓰기 실패")
        } else {
            d.value = value
            if (!g.writeDescriptor(d)) fail("CCCD 쓰기 실패")
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChunk(g: BluetoothGatt, c: BluetoothGattCharacteristic, chunk: ByteArray) {
        // protocol.md: "write with response" 사용
        if (Build.VERSION.SDK_INT >= 33) {
            val r = g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (r != BluetoothStatusCodes.SUCCESS) fail("BLE 쓰기 실패 (코드 $r)")
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = chunk
            if (!g.writeCharacteristic(c)) fail("BLE 쓰기 실패")
        }
    }

    override fun send(message: ByteArray) {
        val g = gatt ?: fail("연결되어 있지 않습니다")
        val tx = txChar ?: fail("연결되어 있지 않습니다")
        val framed = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + blockLength, framed.size)
            events.clear()
            trace("BLE 쓰기 ${end - offset}B (${end}/${framed.size})")
            writeChunk(g, tx, framed.copyOfRange(offset, end))
            val w = await<Ev.CharWrite>(5_000, "BLE 쓰기")
            if (w.status != BluetoothGatt.GATT_SUCCESS) fail("BLE 쓰기 실패 (${gattStatusName(w.status)})")
            offset = end
        }
    }

    private fun onRx(p: ByteArray) {
        trace("BLE 수신 조각 ${p.size}B")
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRxAt > 1000) buffer = ByteArray(0) // 조각 사이 1초 초과 시 버림
            lastRxAt = now
            buffer += p
            while (buffer.size >= 2) {
                val len = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                if (len > 1024) {
                    buffer = ByteArray(0)
                    return
                }
                if (buffer.size < 2 + len) return
                inbox.offer(buffer.copyOfRange(2, 2 + len))
                buffer = buffer.copyOfRange(2 + len, buffer.size)
            }
        }
    }

    override fun receive(timeoutMs: Long): ByteArray? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
        } catch (e: Exception) {
            // ignore
        }
        try {
            g.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}
