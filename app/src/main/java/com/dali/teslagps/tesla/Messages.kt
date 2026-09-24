// Portions of this file are a Kotlin port of logic and message definitions from
// teslamotors/vehicle-command (https://github.com/teslamotors/vehicle-command),
// Copyright Tesla, Inc., licensed under the Apache License 2.0.
// Modified: rewritten in Kotlin for Android BLE. Unofficial; not affiliated with Tesla, Inc.
// See the NOTICE and LICENSE files in the repository root.

package com.dali.teslagps.tesla

object Domain {
    const val VCSEC = 2
    const val INFOTAINMENT = 3
}

/** universal_message.proto 의 MessageFault_E */
object Fault {
    const val NONE = 0
    const val BUSY = 1
    const val TIMEOUT = 2
    const val UNKNOWN_KEY_ID = 3
    const val INACTIVE_KEY = 4
    const val INVALID_SIGNATURE = 5
    const val INVALID_TOKEN_OR_COUNTER = 6
    const val INSUFFICIENT_PRIVILEGES = 7
    const val INTERNAL = 11
    const val INCORRECT_EPOCH = 15
    const val TIME_EXPIRED = 17
    const val REMOTE_ACCESS_DISABLED = 21
    const val REQUIRES_RESPONSE_ENCRYPTION = 28

    /** 세션을 다시 맺으면 해결되는 오류 */
    val RESYNC = setOf(INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INCORRECT_EPOCH, TIME_EXPIRED)

    /** 잠시 뒤 재시도하면 되는 오류 */
    val TRANSIENT = setOf(BUSY, TIMEOUT, INTERNAL)

    fun describe(code: Int): String = when (code) {
        1 -> "차량 하위 시스템이 바쁨(BUSY)"
        2 -> "차량 응답 시간 초과(TIMEOUT)"
        3 -> "차량이 이 키를 모름(UNKNOWN_KEY_ID) — 페어링 필요"
        4 -> "비활성화된 키(INACTIVE_KEY)"
        5 -> "서명 오류(INVALID_SIGNATURE)"
        6 -> "카운터 불일치(INVALID_TOKEN_OR_COUNTER)"
        7 -> "권한 부족(INSUFFICIENT_PRIVILEGES) — 키 역할을 Owner로 등록해 보세요"
        11 -> "차량 내부 오류(INTERNAL) — 아직 부팅 중일 수 있음"
        12 -> "다른 VIN으로 보낸 명령(WRONG_PERSONALIZATION) — VIN 확인"
        15 -> "세션 epoch 불일치(INCORRECT_EPOCH)"
        17 -> "명령 만료(TIME_EXPIRED)"
        21 -> "차량에서 모바일 접근이 꺼져 있음(REMOTE_ACCESS_DISABLED)"
        28 -> "응답 암호화 필요(REQUIRES_RESPONSE_ENCRYPTION)"
        else -> "차량 오류 코드 $code"
    }
}

object Flags {
    /** FLAG_ENCRYPT_RESPONSE = 1  →  비트마스크 (1 shl 1) */
    const val ENCRYPT_RESPONSE = 2L
}

/** 차량에서 받은 RoutableMessage (필요한 필드만 파싱). */
class Routable(
    val fromDomain: Int?,
    val toAddress: ByteArray?,
    val payload: ByteArray?,
    val sessionInfo: ByteArray?,
    val sessionInfoTag: ByteArray?,
    val gcmNonce: ByteArray?,
    val gcmCounter: Long,
    val gcmTag: ByteArray?,
    val opStatus: Int,
    val fault: Int,
    val requestUuid: ByteArray?,
    val flags: Long,
) {
    companion object {
        fun parse(bytes: ByteArray): Routable {
            val f = Pb.parse(bytes)
            val from = f.subOf(7)
            val to = f.subOf(6)
            val sig = f.subOf(13)
            val gcm = sig?.subOf(9)
            val status = f.subOf(12)
            return Routable(
                fromDomain = from?.varintOf(1)?.toInt(),
                toAddress = to?.bytesOf(2),
                payload = f.bytesOf(10),
                sessionInfo = f.bytesOf(15),
                sessionInfoTag = sig?.subOf(6)?.bytesOf(1),
                gcmNonce = gcm?.bytesOf(1),
                gcmCounter = gcm?.varintOf(2) ?: 0,
                gcmTag = gcm?.bytesOf(3),
                opStatus = (status?.varintOf(1) ?: 0).toInt(),
                fault = (status?.varintOf(2) ?: 0).toInt(),
                requestUuid = f.bytesOf(50),
                flags = f.varintOf(52) ?: 0,
            )
        }
    }
}

object Messages {
    private fun PbWriter.toDomain(domain: Int) = message(6) { varint(1, domain.toLong()) }
    private fun PbWriter.fromAddress(address: ByteArray) = message(7) { bytes(2, address) }

    /** 핸드셰이크 요청: 내 공개키를 보내면 차량이 세션 정보(epoch/counter/clock/공개키)로 응답. */
    fun sessionInfoRequest(domain: Int, address: ByteArray, uuid: ByteArray, publicKey: ByteArray): ByteArray =
        PbWriter().apply {
            toDomain(domain)
            fromAddress(address)
            message(14) { bytes(1, publicKey) }
            bytes(51, uuid)
            varint(52, Flags.ENCRYPT_RESPONSE)
        }.toByteArray()

    /** 인증/암호화 없이 보내는 명령 (예: VCSEC GetStatus). */
    fun plainCommand(domain: Int, address: ByteArray, uuid: ByteArray, payload: ByteArray): ByteArray =
        PbWriter().apply {
            toDomain(domain)
            fromAddress(address)
            bytes(10, payload)
            bytes(51, uuid)
            varint(52, Flags.ENCRYPT_RESPONSE)
        }.toByteArray()

    /** AES-GCM 으로 암호화한 명령. */
    fun encryptedCommand(
        domain: Int,
        address: ByteArray,
        uuid: ByteArray,
        session: DomainSession,
        enc: EncryptedCommand,
    ): ByteArray = PbWriter().apply {
        toDomain(domain)
        fromAddress(address)
        bytes(10, enc.ciphertext)
        message(13) {
            message(1) { bytes(1, session.localPublic) }
            message(5) {
                bytes(1, session.epoch)
                bytes(2, enc.nonce)
                varint(3, enc.counter)
                fixed32(4, enc.expiresAt)
                bytes(5, enc.tag)
            }
        }
        bytes(51, uuid)
        varint(52, Flags.ENCRYPT_RESPONSE)
    }.toByteArray()

    // ---- VCSEC ----

    /** UnsignedMessage{ InformationRequest{ GET_STATUS } } */
    val vcsecGetStatus: ByteArray = PbWriter().apply { message(1) { } }.toByteArray()

    /** UnsignedMessage{ RKEAction = WAKE_VEHICLE(30) } */
    val vcsecWake: ByteArray = PbWriter().apply { varint(2, 30) }.toByteArray()

    const val ROLE_OWNER = 2L
    const val ROLE_DRIVER = 3L
    const val FORM_FACTOR_ANDROID_DEVICE = 7L

    /**
     * 페어링(화이트리스트 추가) 요청.
     * 라우팅 없이 ToVCSECMessage 를 그대로 BLE 로 보낸다 (SDK 의 SendAddKeyRequestWithRole 과 동일).
     * 차량 센터콘솔에 NFC 카드를 대고 화면에서 승인해야 등록된다.
     */
    fun addKeyRequest(publicKey: ByteArray, role: Long, formFactor: Long = FORM_FACTOR_ANDROID_DEVICE): ByteArray {
        val unsigned = PbWriter().apply {
            message(16) { // WhitelistOperation
                message(5) { // addKeyToWhitelistAndAddPermissions
                    message(1) { bytes(1, publicKey) }
                    varint(4, role)
                }
                message(6) { varint(1, formFactor) } // metadataForKey.keyFormFactor
            }
        }.toByteArray()
        return PbWriter().apply {
            message(1) { // ToVCSECMessage.signedMessage
                bytes(2, unsigned)
                varint(3, 2) // SIGNATURE_TYPE_PRESENT_KEY
            }
        }.toByteArray()
    }

    // ---- CarServer ----

    /** Action{ vehicleAction{ getVehicleData{ getLocationState{} } } }  =  12 04 0A 02 3A 00 */
    val carServerGetLocation: ByteArray = PbWriter().apply {
        message(2) { message(1) { message(7) { } } }
    }.toByteArray()
}

enum class SleepStatus { UNKNOWN, AWAKE, ASLEEP }

class VcsecStatus(val sleep: SleepStatus, val lockState: Int, val userPresent: Boolean?) {
    val lockText: String
        get() = when (lockState) {
            0 -> "잠금 해제"
            1 -> "잠김"
            2 -> "내부 잠금"
            3 -> "일부 해제"
            else -> "알 수 없음"
        }

    companion object {
        fun parse(fromVcsec: ByteArray): VcsecStatus? {
            val status = Pb.parse(fromVcsec).subOf(1) ?: return null
            val sleep = when (status.varintOf(3)?.toInt()) {
                1 -> SleepStatus.AWAKE
                2 -> SleepStatus.ASLEEP
                else -> SleepStatus.UNKNOWN
            }
            val presence = when (status.varintOf(4)?.toInt()) {
                1 -> false
                2 -> true
                else -> null
            }
            return VcsecStatus(sleep, (status.varintOf(2) ?: 0).toInt(), presence)
        }
    }
}

class VehiclePosition(
    val latitude: Double,
    val longitude: Double,
    val heading: Int?,
    val gpsAsOfEpochSec: Long?,
    val source: String,
    val geoAccuracyM: Float?,
    val estimatedToRawDistanceM: Float?,
) {
    companion object {
        /** carserver.Response 를 파싱해 위치를 꺼낸다. */
        fun fromResponse(responseBytes: ByteArray): VehiclePosition {
            val resp = Pb.parse(responseBytes)
            val status = resp.subOf(1)
            if (status?.varintOf(1)?.toInt() == 1) { // OPERATIONSTATUS_ERROR
                val reason = status?.subOf(2)?.bytesOf(1)?.toString(Charsets.UTF_8) ?: "unspecified error"
                throw VehicleException(VehicleError.FAULT, "차량이 명령을 거부: $reason")
            }
            val loc = resp.subOf(2)?.subOf(8)
                ?: throw VehicleException(VehicleError.PROTOCOL, "응답에 위치 정보(LocationState)가 없음")

            val lat = loc.floatOf(101)
            val lon = loc.floatOf(102)
            val nLat = loc.floatOf(106)
            val nLon = loc.floatOf(107)
            val nativeSupported = loc.varintOf(105) == 1L
            // native_type(8) = GPSCoordinateType{ oneof { Void GCJ = 1; Void WGS = 2 } }, Void 는 length-delimited
            val nativeIsGcj = loc.subOf(8)?.any { it.number == 1 } == true
            val gLat = loc.floatOf(114)
            val gLon = loc.floatOf(115)

            // 폰 GPS(WGS-84)에 쓸 좌표를 고른다: WGS 네이티브 > 일반 latitude/longitude > raw geo
            val (la, lo, src) = when {
                nativeSupported && !nativeIsGcj && nLat != null && nLon != null -> Triple(nLat, nLon, "native(WGS)")
                lat != null && lon != null -> Triple(lat, lon, "latitude/longitude")
                gLat != null && gLon != null -> Triple(gLat, gLon, "geo(raw GPS)")
                else -> throw VehicleException(VehicleError.PROTOCOL, "위치 좌표 필드가 비어 있음 (차량이 위치를 아직 못 잡았을 수 있음)")
            }
            return VehiclePosition(
                latitude = la.toDouble(),
                longitude = lo.toDouble(),
                heading = loc.varintOf(103)?.toInt(),
                gpsAsOfEpochSec = loc.varintOf(104),
                source = src,
                geoAccuracyM = loc.floatOf(118),
                estimatedToRawDistanceM = loc.floatOf(120),
            )
        }
    }
}

/** 페어링 요청에 대한 VCSEC 응답 (FromVCSECMessage). BLE 에서 RoutableMessage 로 감싸 오거나 그대로 올 수 있어 둘 다 처리. */
class PairingReply(
    /** CommandStatus.operationStatus: 0 OK, 1 WAIT(NFC 카드 대기), 2 ERROR(구형 호환용, 무시) */
    val operationStatus: Int?,
    /** WhitelistOperation_status.information: 0 이면 성공, 그 외는 실패 사유. 필드가 없으면 null */
    val whitelistInfo: Int?,
    val nominalError: Int?,
) {
    companion object {
        fun parse(frame: ByteArray): PairingReply? {
            val outer = try { Pb.parse(frame) } catch (e: Exception) { return null }
            val isRoutable = outer.any { it.number == 6 || it.number == 7 || it.number == 12 }
            val body = if (isRoutable) (outer.bytesOf(10) ?: return null) else frame
            val f = try { Pb.parse(body) } catch (e: Exception) { return null }
            val cs = f.subOf(4)
            return PairingReply(
                operationStatus = cs?.varintOf(1)?.toInt(),
                whitelistInfo = cs?.subOf(3)?.let { (it.varintOf(1) ?: 0L).toInt() },
                nominalError = f.subOf(46)?.varintOf(1)?.toInt(),
            )
        }

        fun describeWhitelist(code: Int): String = when (code) {
            0 -> "성공"
            1 -> "차량이 알 수 없는 오류를 반환했습니다"
            3 -> "키카드/키fob 슬롯이 가득 찼습니다"
            4 -> "차량의 키 목록이 가득 찼습니다 — 안 쓰는 키를 삭제하세요"
            5 -> "키를 추가할 권한이 없습니다"
            6 -> "공개키가 올바르지 않습니다 (키를 새로 만들어 보세요)"
            13 -> "이미 등록된 키입니다 — [페어링 확인]을 눌러 보세요"
            14 -> "카드 리더에서 승인해야 추가할 수 있습니다"
            23 -> "차량이 카드 인증을 시작하지 못했습니다 (차량이 깨어 있고 주차 상태인지 확인)"
            24 -> "차량 화면에서 거부/취소되었습니다"
            25 -> "요청 후 약 35초 안에 NFC 카드를 대지 않아 시간이 지났습니다 — ①을 다시 누르고 바로 카드를 대세요"
            26 -> "카드를 댄 뒤 차량 화면의 확인(Confirm)을 제때 누르지 않았습니다 — 다시 시도하세요"
            else -> "화이트리스트 오류 코드 $code"
        }
    }
}
