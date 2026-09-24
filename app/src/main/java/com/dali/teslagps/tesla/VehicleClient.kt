// Portions of this file are a Kotlin port of logic and message definitions from
// teslamotors/vehicle-command (https://github.com/teslamotors/vehicle-command),
// Copyright Tesla, Inc., licensed under the Apache License 2.0.
// Modified: rewritten in Kotlin for Android BLE. Unofficial; not affiliated with Tesla, Inc.
// See the NOTICE and LICENSE files in the repository root.

package com.dali.teslagps.tesla

import java.security.MessageDigest
import java.security.SecureRandom

/** BLE 프레임 단위 전송 계층 (2바이트 길이 헤더/분할은 구현체가 처리). 블로킹 API. */
interface Transport {
    /** 메시지 하나(RoutableMessage 등)를 전송. */
    fun send(message: ByteArray)

    /** 다음 메시지를 기다린다. 시간 초과 시 null. */
    fun receive(timeoutMs: Long): ByteArray?

    fun close()
}

/**
 * 차량 1대와의 BLE 대화. 스레드 하나에서 순차적으로만 사용한다 (요청 1개씩).
 * 프로토콜 기준: teslamotors/vehicle-command (dispatcher, signer, vehicle 패키지).
 */
class VehicleClient(
    private val transport: Transport,
    vin: String,
    private val key: KeyMaterial,
    /** 진단용 상세 추적(프레임 크기, 오류 코드 등). 화면에는 표시하지 않는다. */
    private val trace: (String) -> Unit = {},
    /** 사용자에게 보여줄 진행 상황 */
    private val log: (String) -> Unit = {},
) {
    private val vinBytes = vin.trim().uppercase().toByteArray(Charsets.US_ASCII)
    private val rng = SecureRandom()
    private val infotainmentAddress = random(16)
    private val sessions = HashMap<Int, DomainSession>()

    private fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    private class Pending(val domain: Int, val address: ByteArray, val uuid: ByteArray)

    private fun awaitReply(p: Pending, timeoutMs: Long): Routable {
        val deadline = System.currentTimeMillis() + timeoutMs
        var received = 0
        var ignored = 0
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val raw = if (remaining > 0) transport.receive(remaining) else null
            if (raw == null) {
                trace("응답 시간 초과(${domainName(p.domain)}): 수신 프레임 ${received}개 중 무시 ${ignored}개")
                throw VehicleException(
                    VehicleError.TIMEOUT,
                    "차량 응답 없음 (${domainName(p.domain)}, 수신 ${received}개/무시 ${ignored}개)",
                )
            }
            received++
            val msg = try {
                Routable.parse(raw)
            } catch (e: Exception) {
                ignored++
                trace("파싱할 수 없는 프레임 무시(${raw.size}B): ${e.message}")
                continue
            }
            if (msg.fromDomain != p.domain) {
                ignored++
                trace("RX 무시: 도메인 ${msg.fromDomain} (기대 ${p.domain})")
                continue
            }
            if (msg.toAddress == null || !msg.toAddress.contentEquals(p.address)) {
                ignored++
                trace("RX 무시: 수신 주소 불일치")
                continue
            }
            val ru = msg.requestUuid
            if (ru != null && ru.isNotEmpty() && !ru.contentEquals(p.uuid)) {
                ignored++
                trace("RX 무시: request_uuid 불일치")
                continue
            }
            trace(
                "RX ${domainName(p.domain)} fault=${msg.fault} op=${msg.opStatus} payload=${msg.payload?.size ?: 0}B " +
                    "sessionInfo=${msg.sessionInfo != null} sessionTag=${msg.sessionInfoTag != null} gcm=${msg.gcmTag != null}",
            )
            return msg
        }
    }

    private fun tx(what: String, frame: ByteArray) {
        trace("TX $what ${frame.size}B")
        transport.send(frame)
    }

    private fun domainName(d: Int) = if (d == Domain.VCSEC) "VCSEC" else "Infotainment"

    private fun addressFor(domain: Int) = if (domain == Domain.INFOTAINMENT) infotainmentAddress else random(16)

    private fun checkFault(reply: Routable) {
        when {
            reply.fault == Fault.UNKNOWN_KEY_ID ->
                throw VehicleException(VehicleError.NOT_PAIRED, Fault.describe(reply.fault))
            reply.fault != Fault.NONE ->
                throw VehicleException(VehicleError.FAULT, Fault.describe(reply.fault))
        }
    }

    // ------------------------------------------------------------------ 상태 / 핸드셰이크

    /** 인증 없이 읽는 VCSEC 상태(잠금/수면). 인포테인먼트가 자고 있어도 동작. */
    fun vcsecStatus(timeoutMs: Long = 5000): VcsecStatus {
        val address = random(16)
        val uuid = random(16)
        val pending = Pending(Domain.VCSEC, address, uuid)
        tx("VCSEC GET_STATUS", Messages.plainCommand(Domain.VCSEC, address, uuid, Messages.vcsecGetStatus))
        val reply = awaitReply(pending, timeoutMs)
        checkFault(reply)
        val payload = reply.payload ?: throw VehicleException(VehicleError.PROTOCOL, "VCSEC 상태 응답에 payload 없음")
        return VcsecStatus.parse(payload) ?: throw VehicleException(VehicleError.PROTOCOL, "VCSEC 상태 파싱 실패")
    }

    /** 도메인과 세션을 맺는다. 키가 등록되어 있지 않으면 NOT_PAIRED. */
    fun handshake(domain: Int, timeoutMs: Long = 4000): DomainSession {
        val address = addressFor(domain)
        val uuid = random(16)
        val pending = Pending(domain, address, uuid)
        log("${domainName(domain)} 핸드셰이크 요청")
        tx("${domainName(domain)} SessionInfoRequest", Messages.sessionInfoRequest(domain, address, uuid, key.publicBytes))
        val reply = awaitReply(pending, timeoutMs)
        checkFault(reply)
        val info = reply.sessionInfo ?: throw VehicleException(VehicleError.PROTOCOL, "세션 정보 없음")
        val tag = reply.sessionInfoTag
        val parsed = SessionInfo.parse(info)
        trace("세션정보: status=${parsed.status} counter=${parsed.counter} clock=${parsed.clockTime} epoch=${parsed.epoch.size}B key=${parsed.publicKey.size}B")
        if (parsed.status == DomainSession.STATUS_KEY_NOT_ON_WHITELIST) {
            throw VehicleException(VehicleError.NOT_PAIRED, "차량에 등록되지 않은 키입니다 — 페어링이 필요합니다")
        }
        if (tag == null) {
            // 키 등록 직후에는 차량이 태그 없는 세션 정보를 먼저 돌려줄 수 있다 — 잠시 뒤 재시도하면 정상
            trace("세션정보에 인증 태그 없음 (등록 직후 동기화 중일 수 있음)")
            throw VehicleException(VehicleError.NOT_SYNCED, "차량이 키 등록을 아직 동기화 중입니다")
        }
        val challenge = reply.requestUuid?.takeIf { it.isNotEmpty() } ?: uuid
        val session = try {
            DomainSession.establish(key, vinBytes, domain, info, challenge, tag, System.currentTimeMillis())
        } catch (e: IllegalArgumentException) {
            trace("차량 공개키 처리 실패: ${e.message}")
            throw VehicleException(VehicleError.PROTOCOL, "차량 공개키 처리 실패: ${e.message}")
        } catch (e: VehicleException) {
            trace("세션 수립 실패: ${e.message}")
            throw e
        }
        sessions[domain] = session
        log("${domainName(domain)} 세션 수립")
        return session
    }

    // ------------------------------------------------------------------ 암호화 명령

    /** 암호화 명령을 보내고 (복호화된) 응답 payload 를 돌려준다. 재동기화/재시도 포함. */
    private fun sendEncrypted(domain: Int, payload: ByteArray, timeoutMs: Long = 6000): ByteArray {
        var lastError: VehicleException? = null
        for (attempt in 1..4) {
            val session = sessions[domain] ?: handshake(domain)
            val enc = session.encrypt(payload, Flags.ENCRYPT_RESPONSE)
            val address = addressFor(domain)
            val uuid = random(16)
            val pending = Pending(domain, address, uuid)
            tx("${domainName(domain)} 암호화 명령 counter=${enc.counter}", Messages.encryptedCommand(domain, address, uuid, session, enc))
            val reply = awaitReply(pending, timeoutMs)

            if (reply.fault in Fault.RESYNC) {
                log("세션 재동기화 필요(${Fault.describe(reply.fault)})")
                sessions.remove(domain)
                lastError = VehicleException(VehicleError.FAULT, Fault.describe(reply.fault))
                continue
            }
            if (reply.fault in Fault.TRANSIENT || (reply.fault == Fault.NONE && reply.opStatus == 1)) {
                log("차량이 바쁨 — 재시도 $attempt")
                lastError = VehicleException(VehicleError.FAULT, "차량이 바쁨")
                Thread.sleep(1000)
                continue
            }
            checkFault(reply)
            return if (reply.gcmTag != null) {
                session.decryptResponse(reply, enc.tag)
            } else {
                reply.payload ?: ByteArray(0)
            }
        }
        throw lastError ?: VehicleException(VehicleError.TIMEOUT, "명령 실패")
    }

    // ------------------------------------------------------------------ 기능

    /** 차량 깨우기 (VCSEC RKE WAKE). VCSEC 세션(등록된 키)이 필요. */
    fun wake() {
        log("VCSEC 로 깨우기 명령 전송")
        sendEncrypted(Domain.VCSEC, Messages.vcsecWake, 6000)
    }

    /**
     * 차량 위치 읽기.
     * 1) VCSEC 상태로 수면 여부 확인 → 자고 있으면 먼저 깨움
     * 2) 인포테인먼트 세션 수립 (깨어나는 동안 재시도)
     * 3) GetVehicleData(location) 요청
     */
    fun readLocation(overallTimeoutMs: Long = 60_000): VehiclePosition {
        val deadline = System.currentTimeMillis() + overallTimeoutMs
        var woke = false

        val status = try {
            vcsecStatus().also { log("차량 상태: ${it.sleep}, ${it.lockText}") }
        } catch (e: VehicleException) {
            if (e.kind == VehicleError.TIMEOUT) throw e
            null
        }
        if (status?.sleep == SleepStatus.ASLEEP) {
            wake()
            woke = true
        }

        var lastError: VehicleException? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                handshake(Domain.INFOTAINMENT, 3500)
                lastError = null
                break
            } catch (e: VehicleException) {
                if (e.kind == VehicleError.NOT_PAIRED) throw e
                lastError = e
                if (!woke && e.kind == VehicleError.TIMEOUT) {
                    wake()
                    woke = true
                } else {
                    Thread.sleep(1000)
                }
            }
        }
        lastError?.let { throw VehicleException(it.kind, "인포테인먼트 연결 실패: ${it.message}") }

        val response = sendEncrypted(Domain.INFOTAINMENT, Messages.carServerGetLocation, 8000)
        val pos = VehiclePosition.fromResponse(response)
        trace(
            "위치 파싱: source=${pos.source} heading=${pos.heading} gpsAsOf=${pos.gpsAsOfEpochSec} " +
                "geoAcc=${pos.geoAccuracyM} estToRaw=${pos.estimatedToRawDistanceM}",
        )
        return pos
    }

    /** 페어링 요청을 보낸다. 이후 센터콘솔에 NFC 카드를 대고 차량 화면에서 승인해야 한다. */
    fun sendAddKeyRequest(role: Long) {
        log("페어링 요청 전송")
        tx("AddKey role=$role", Messages.addKeyRequest(key.publicBytes, role))
    }

    /**
     * 페어링 요청을 보내고, 차량 반응을 최대 [waitMs] 동안 지켜본다.
     * 정상 흐름: 요청 → (차량 화면에는 아무것도 안 뜸) → NFC 카드를 센터콘솔에 태그 → 차량 화면 Confirm → 성공 응답.
     * 차량은 요청을 받으면 OPERATIONSTATUS_WAIT 로 "카드 대기 중"임을 알려 준다.
     */
    fun pairWithCard(role: Long, waitMs: Long = 75_000): PairResult {
        sendAddKeyRequest(role)
        // 차량은 요청을 받아도 별도 응답을 주지 않을 수 있다(실측). 응답을 기다리지 말고 바로 카드를 대야 한다.
        log("요청 전송 완료 — 지금 바로 센터콘솔 NFC 리더(컵홀더 뒤쪽)에 카드를 대세요 (약 35초 이내)")
        val started = System.currentTimeMillis()
        val deadline = started + waitMs
        var sawWait = false
        var anyReply = false
        var lastReminder = started
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = transport.receive(minOf(remaining, 1000))
            if (frame == null) {
                val now = System.currentTimeMillis()
                if (now - lastReminder >= 10_000) {
                    lastReminder = now
                    log("카드 태그/확인 대기 중… ${(now - started) / 1000}초 경과 (요청 후 약 35초 이내에 카드를 대야 합니다)")
                }
                continue
            }
            anyReply = true
            trace("페어링 응답 ${frame.size}B: " + frame.take(48).joinToString("") { "%02x".format(it) })
            val r = PairingReply.parse(frame) ?: continue
            val info = r.whitelistInfo
            when {
                info != null && info != 0 ->
                    return PairResult(PairOutcome.REJECTED, PairingReply.describeWhitelist(info))
                info == 0 ->
                    return PairResult(PairOutcome.APPROVED, "차량이 키 등록을 승인했습니다")
                r.nominalError != null && r.nominalError != 0 ->
                    return PairResult(PairOutcome.REJECTED, "차량 오류(${r.nominalError})")
                r.operationStatus == 1 && !sawWait -> {
                    sawWait = true
                    log("차량이 카드 태그를 기다리는 중입니다")
                }
            }
        }
        return when {
            sawWait -> PairResult(PairOutcome.TIMEOUT_AFTER_ACK, "요청은 접수됐지만 카드 태그/확인이 끝나지 않았습니다")
            anyReply -> PairResult(PairOutcome.TIMEOUT_AFTER_ACK, "카드 태그/확인이 끝나지 않았습니다")
            else -> PairResult(PairOutcome.NO_REPLY, "요청 후 ${waitMs / 1000}초 동안 차량에서 아무 신호도 없었습니다")
        }
    }

    /** 키가 차량에 등록되어 있는지 VCSEC 핸드셰이크로 확인. 등록 직후 동기화 지연은 몇 번 재시도한다. */
    fun isPaired(): Boolean {
        var attempt = 0
        while (true) {
            attempt++
            try {
                handshake(Domain.VCSEC, 4000)
                return true
            } catch (e: VehicleException) {
                if (e.kind == VehicleError.NOT_PAIRED) return false
                if (e.kind == VehicleError.NOT_SYNCED && attempt < 6) {
                    log("차량이 키 등록을 동기화하는 중… ($attempt/6)")
                    Thread.sleep(1500)
                    continue
                }
                throw e
            }
        }
    }

    companion object {
        /** BLE 광고 이름: "S" + SHA1(VIN)[0..8] 소문자 hex + "C" */
        fun advertisedName(vin: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(vin.trim().uppercase().toByteArray(Charsets.US_ASCII))
            return "S" + digest.copyOf(8).joinToString("") { "%02x".format(it) } + "C"
        }
    }
}

enum class PairOutcome { APPROVED, REJECTED, TIMEOUT_AFTER_ACK, NO_REPLY }

class PairResult(val outcome: PairOutcome, val message: String)
