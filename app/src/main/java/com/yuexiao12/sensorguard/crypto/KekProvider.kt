package com.yuexiao12.sensorguard.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * W7 (文档 §8.2):KEK 提供方 —— Android Keystore AES-256-GCM。
 *
 * - setUnlockedDeviceRequired(false):允许锁屏加解密(静默常驻必需)。
 * - StrongBox 优先:先尝试 setIsStrongBoxBacked(true);设备无 StrongBox 或与锁屏
 *   常驻约束冲突(StrongBox 密钥在锁定后不可用)时回退 TEE/软件 Keystore。
 * - KEK 永不出芯片:Keystore 密钥不可导出(AndroidKeyStore 保证)。
 */
class KekProvider {

    @Synchronized
    fun getOrCreate(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        // StrongBox 优先;失败回退 TEE/软件(保证锁屏可加解密)。
        val strongBox = runCatching {
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KEK_PURPOSE)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUnlockedDeviceRequired(false)
                    .setIsStrongBoxBacked(true)
                    .build()
            )
            gen.generateKey()
        }
        if (strongBox.isSuccess) {
            Log.i(TAG, "KEK 创建于 StrongBox")
            return strongBox.getOrThrow()
        }
        Log.w(TAG, "StrongBox 不可用(${strongBox.exceptionOrNull()}),回退 TEE/软件 Keystore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KEK_PURPOSE)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /** 遗忘权(§8.2):销毁 KEK,keychain 中全部包裹 DEK 立即不可解。*/
    fun delete() {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    companion object {
        const val ALIAS = "sg_kek_v1"
        private const val TAG = "SgKek"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val KEK_PURPOSE = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    }
}
