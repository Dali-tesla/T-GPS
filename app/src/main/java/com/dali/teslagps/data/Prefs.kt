package com.dali.teslagps.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dali.teslagps.core.Offset
import com.dali.teslagps.tesla.KeyMaterial
import com.dali.teslagps.tesla.KeyUtil
import com.dali.teslagps.tesla.Messages
import java.security.MessageDigest

/**
 * BLE 페어링 키(개인키)는 EncryptedSharedPreferences 에, 일반 설정은 평문 SharedPreferences 에 둔다.
 * 개인키는 차량에 명령을 보낼 수 있는 권한이므로 평문 저장하지 않는다.
 */
class Prefs(context: Context) {

    private val secure: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ble_key",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ── BLE 키 ──────────────────────────────────────────────────────────────

    /** 저장된 키를 읽고, 없으면 새로 만든다. 새 키는 차량에 다시 페어링해야 한다. */
    @Synchronized
    fun key(): KeyMaterial {
        val priv = secure.getString(K_PRIV, null)
        val pub = secure.getString(K_PUB, null)
        if (priv != null && pub != null) {
            try {
                return KeyUtil.restore(Base64.decode(priv, Base64.NO_WRAP), Base64.decode(pub, Base64.NO_WRAP))
            } catch (e: Exception) {
                // 손상되었으면 새로 만든다
            }
        }
        return newKey()
    }

    @Synchronized
    fun newKey(): KeyMaterial {
        val fresh = KeyUtil.generate()
        secure.edit()
            .putString(K_PRIV, Base64.encodeToString(fresh.pkcs8(), Base64.NO_WRAP))
            .putString(K_PUB, Base64.encodeToString(fresh.publicBytes, Base64.NO_WRAP))
            .apply()
        paired = false
        return fresh
    }

    fun keyFingerprint(): String {
        val d = MessageDigest.getInstance("SHA-256").digest(key().publicBytes)
        return d.copyOf(4).joinToString("") { "%02X".format(it) }
    }

    // ── 차량 ────────────────────────────────────────────────────────────────

    var vin: String
        get() = plain.getString(K_VIN, "") ?: ""
        set(v) = plain.edit().putString(K_VIN, v.trim().uppercase()).apply()

    /** 이 키가 차량에 등록되었음을 확인했는가 */
    var paired: Boolean
        get() = plain.getBoolean(K_PAIRED, false)
        set(v) = plain.edit().putBoolean(K_PAIRED, v).apply()

    /** 페어링 시 등록할 키 역할. 기본은 Driver, 위치가 안 읽히면 Owner 로 다시 등록. */
    var role: Long
        get() = plain.getLong(K_ROLE, Messages.ROLE_DRIVER)
        set(v) = plain.edit().putLong(K_ROLE, v).apply()

    val isConfigured: Boolean
        get() = vin.length == 17 && paired

    // ── 위치 오프셋 설정 ────────────────────────────────────────────────────

    var offset: Offset
        get() = Offset(
            plain.getFloat(K_FORWARD, 4.0f).toDouble(),
            plain.getFloat(K_RIGHT, 0.0f).toDouble(),
        )
        set(v) = plain.edit()
            .putFloat(K_FORWARD, v.forwardM.toFloat())
            .putFloat(K_RIGHT, v.rightM.toFloat())
            .apply()

    /** 자동 복구까지의 시간(분) */
    var autoStopMinutes: Int
        get() = plain.getInt(K_MINUTES, 5)
        set(v) = plain.edit().putInt(K_MINUTES, v).apply()

    private companion object {
        const val K_PRIV = "key_priv"
        const val K_PUB = "key_pub"
        const val K_VIN = "vin"
        const val K_PAIRED = "paired"
        const val K_ROLE = "role"

        const val K_FORWARD = "offset_forward"
        const val K_RIGHT = "offset_right"
        const val K_MINUTES = "auto_stop_minutes"
    }
}
