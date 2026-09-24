// Portions of this file are a Kotlin port of logic and message definitions from
// teslamotors/vehicle-command (https://github.com/teslamotors/vehicle-command),
// Copyright Tesla, Inc., licensed under the Apache License 2.0.
// Modified: rewritten in Kotlin for Android BLE. Unofficial; not affiliated with Tesla, Inc.
// See the NOTICE and LICENSE files in the repository root.

package com.dali.teslagps.tesla

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Tesla vehicle-command 프로토콜(pkg/protocol/protocol.md, internal/authentication)의 Kotlin 포팅.
 * 기준 소스: github.com/teslamotors/vehicle-command (2026-09-21 main).
 */

object Tag {
    const val SIGNATURE_TYPE = 0
    const val DOMAIN = 1
    const val PERSONALIZATION = 2
    const val EPOCH = 3
    const val EXPIRES_AT = 4
    const val COUNTER = 5
    const val CHALLENGE = 6
    const val FLAGS = 7
    const val REQUEST_HASH = 8
    const val FAULT = 9
    const val END = 255
}

object SigType {
    const val HMAC = 6
    const val AES_GCM_PERSONALIZED = 5
    const val AES_GCM_RESPONSE = 9
}

interface Sink {
    fun write(b: ByteArray)
    fun sum(): ByteArray
}

class Sha256Sink : Sink {
    private val md = MessageDigest.getInstance("SHA-256")
    override fun write(b: ByteArray) = md.update(b)
    override fun sum(): ByteArray = md.digest()
}

class HmacSink(key: ByteArray) : Sink {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    override fun write(b: ByteArray) = mac.update(b)
    override fun sum(): ByteArray = mac.doFinal()
}

/** internal/authentication/metadata.go 와 동일한 TLV 직렬화 (태그는 오름차순으로만 추가 가능). */
class Metadata(private val sink: Sink) {
    private var last = 0

    fun add(tag: Int, value: ByteArray?) {
        require(tag >= last) { "metadata 태그는 오름차순이어야 함" }
        if (value == null) return
        require(value.size <= 255) { "metadata 필드는 255바이트 이하" }
        last = tag
        sink.write(byteArrayOf(tag.toByte()))
        sink.write(byteArrayOf(value.size.toByte()))
        sink.write(value)
    }

    fun addU32(tag: Int, value: Long) {
        add(
            tag,
            byteArrayOf(
                ((value shr 24) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte(),
            ),
        )
    }

    fun checksum(message: ByteArray): ByteArray {
        sink.write(byteArrayOf(Tag.END.toByte()))
        sink.write(message)
        return sink.sum()
    }
}

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

object KeyUtil {
    private val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    fun leftPad(src: ByteArray, size: Int): ByteArray {
        if (src.size == size) return src
        if (src.size > size) {
            // BigInteger.toByteArray()가 붙인 부호용 0x00 제거
            val extra = src.size - size
            require(src.take(extra).all { it == 0.toByte() }) { "값이 너무 큼" }
            return src.copyOfRange(extra, src.size)
        }
        val out = ByteArray(size)
        System.arraycopy(src, 0, out, size - src.size, src.size)
        return out
    }

    fun publicBytes(pub: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + leftPad(pub.w.affineX.toByteArray(), 32) + leftPad(pub.w.affineY.toByteArray(), 32)

    fun decodePublic(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "잘못된 공개키 형식" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        // 곡선 위의 점인지 확인: y^2 = x^3 - 3x + b (mod p)
        val lhs = y.multiply(y).mod(P)
        val rhs = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        require(lhs == rhs && x.signum() > 0) { "곡선 위의 점이 아님" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    fun privateFromScalar(scalar: ByteArray): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params)) as ECPrivateKey

    fun generate(): KeyMaterial {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = gen.generateKeyPair()
        return KeyMaterial(pair.private as ECPrivateKey, publicBytes(pair.public as ECPublicKey))
    }

    fun restore(pkcs8: ByteArray, publicBytes: ByteArray): KeyMaterial {
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as ECPrivateKey
        return KeyMaterial(priv, publicBytes)
    }

    /** P-256 ECDH 결과의 X좌표(32바이트). */
    fun sharedSecret(priv: ECPrivateKey, peerPublic: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(decodePublic(peerPublic), true)
        val secret = leftPad(ka.generateSecret(), 32)
        require(secret.any { it != 0.toByte() }) { "잘못된 ECDH 결과" }
        return secret
    }

    /** SHA-1(shared secret)의 앞 16바이트 = 세션 키 (차량 호환을 위해 SHA-1 사용). */
    fun sessionKey(priv: ECPrivateKey, peerPublic: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(sharedSecret(priv, peerPublic)).copyOf(16)
}

class KeyMaterial(val privateKey: ECPrivateKey, val publicBytes: ByteArray) {
    fun pkcs8(): ByteArray = privateKey.encoded
}

class EncryptedCommand(
    val counter: Long,
    val expiresAt: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val tag: ByteArray,
)

class SessionInfo(
    val counter: Long,
    val publicKey: ByteArray,
    val epoch: ByteArray,
    val clockTime: Long,
    val status: Int,
) {
    companion object {
        fun parse(bytes: ByteArray): SessionInfo {
            val f = Pb.parse(bytes)
            return SessionInfo(
                counter = f.varintOf(1) ?: 0,
                publicKey = f.bytesOf(2) ?: ByteArray(0),
                epoch = f.bytesOf(3) ?: ByteArray(0),
                clockTime = f.fixed32Of(4) ?: 0,
                status = (f.varintOf(5) ?: 0).toInt(),
            )
        }
    }
}

/** 한 도메인(VCSEC 또는 Infotainment)과의 인증된 세션. */
class DomainSession(
    val domain: Int,
    private val vin: ByteArray,
    private val sessionKey: ByteArray,
    val localPublic: ByteArray,
    val epoch: ByteArray,
    private var counter: Long,
    private val timeZeroMs: Long,
) {
    private val rng = SecureRandom()

    companion object {
        const val STATUS_OK = 0
        const val STATUS_KEY_NOT_ON_WHITELIST = 1

        fun sessionInfoHmac(sessionKey: ByteArray, vin: ByteArray, challenge: ByteArray?, encodedInfo: ByteArray): ByteArray {
            val sub = hmacSha256(sessionKey, "session info".toByteArray(Charsets.US_ASCII))
            val meta = Metadata(HmacSink(sub))
            meta.add(Tag.SIGNATURE_TYPE, byteArrayOf(SigType.HMAC.toByte()))
            meta.add(Tag.PERSONALIZATION, vin)
            meta.add(Tag.CHALLENGE, challenge)
            return meta.checksum(encodedInfo)
        }

        /** 세션 정보를 HMAC으로 검증한 뒤 세션을 만든다. */
        fun establish(
            key: KeyMaterial,
            vin: ByteArray,
            domain: Int,
            encodedInfo: ByteArray,
            challenge: ByteArray?,
            tag: ByteArray,
            nowMs: Long,
        ): DomainSession {
            val info = SessionInfo.parse(encodedInfo)
            if (info.status == STATUS_KEY_NOT_ON_WHITELIST) throw VehicleException(VehicleError.NOT_PAIRED, "차량에 등록되지 않은 키입니다")
            if (info.status != STATUS_OK) throw VehicleException(VehicleError.PROTOCOL, "세션 상태 이상(${info.status})")
            val sk = KeyUtil.sessionKey(key.privateKey, info.publicKey)
            val expected = sessionInfoHmac(sk, vin, challenge, encodedInfo)
            if (!MessageDigest.isEqual(expected, tag)) {
                throw VehicleException(VehicleError.PROTOCOL, "세션 정보 HMAC 불일치 (VIN 또는 키 확인)")
            }
            return DomainSession(
                domain = domain,
                vin = vin,
                sessionKey = sk,
                localPublic = key.publicBytes,
                epoch = info.epoch,
                counter = info.counter,
                timeZeroMs = nowMs - info.clockTime * 1000,
            )
        }
    }

    /** internal/authentication/signer.go 의 Encrypt 와 동일. */
    fun encrypt(plaintext: ByteArray, flags: Long, expiresInSec: Long = 5): EncryptedCommand {
        if (counter == 0xFFFFFFFFL) throw VehicleException(VehicleError.PROTOCOL, "counter rollover")
        counter += 1
        val expiresAt = ((System.currentTimeMillis() + expiresInSec * 1000 - timeZeroMs) / 1000) and 0xFFFFFFFFL

        val meta = Metadata(Sha256Sink())
        meta.add(Tag.SIGNATURE_TYPE, byteArrayOf(SigType.AES_GCM_PERSONALIZED.toByte()))
        meta.add(Tag.DOMAIN, byteArrayOf(domain.toByte()))
        meta.add(Tag.PERSONALIZATION, vin)
        meta.add(Tag.EPOCH, epoch)
        meta.addU32(Tag.EXPIRES_AT, expiresAt)
        meta.addU32(Tag.COUNTER, counter)
        if (flags > 0) meta.addU32(Tag.FLAGS, flags)
        val aad = meta.checksum(ByteArray(0))

        val nonce = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val out = cipher.doFinal(plaintext)
        val ct = out.copyOfRange(0, out.size - 16)
        val tag = out.copyOfRange(out.size - 16, out.size)
        return EncryptedCommand(counter, expiresAt, nonce, ct, tag)
    }

    /** 차량이 암호화해서 보낸 응답(FLAG_ENCRYPT_RESPONSE, 펌웨어 2024.38+)을 복호화. */
    fun decryptResponse(resp: Routable, requestTag: ByteArray): ByteArray {
        val nonce = resp.gcmNonce ?: throw VehicleException(VehicleError.PROTOCOL, "응답 nonce 없음")
        val tag = resp.gcmTag ?: throw VehicleException(VehicleError.PROTOCOL, "응답 tag 없음")
        val requestId = byteArrayOf(SigType.AES_GCM_PERSONALIZED.toByte()) + requestTag

        val meta = Metadata(Sha256Sink())
        meta.add(Tag.SIGNATURE_TYPE, byteArrayOf(SigType.AES_GCM_RESPONSE.toByte()))
        meta.add(Tag.DOMAIN, byteArrayOf((resp.fromDomain ?: domain).toByte()))
        meta.add(Tag.PERSONALIZATION, vin)
        meta.addU32(Tag.COUNTER, resp.gcmCounter)
        meta.addU32(Tag.FLAGS, resp.flags)
        meta.add(Tag.REQUEST_HASH, requestId)
        meta.addU32(Tag.FAULT, resp.fault.toLong())
        val aad = meta.checksum(ByteArray(0))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal((resp.payload ?: ByteArray(0)) + tag)
        } catch (e: Exception) {
            throw VehicleException(VehicleError.PROTOCOL, "응답 복호화 실패")
        }
    }
}

enum class VehicleError { NOT_PAIRED, /** 키 등록 직후 차량이 아직 동기화 중 */ NOT_SYNCED, TIMEOUT, FAULT, PROTOCOL, TRANSPORT, ASLEEP_NO_WAKE }

class VehicleException(val kind: VehicleError, message: String) : Exception(message)
