package io.github.lovislog.lexi

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class ProviderConfig(
    val baseUrl: String, val model: String, val apiKey: String, val visionModel: String = "",
) {
    override fun toString() = "ProviderConfig(model=$model)"
}

internal data class ProviderInfo(
    val baseUrl: String, val model: String, val hasKey: Boolean, val visionModel: String = "",
)

internal class ProviderSettings(context: Context) {
    private val store = PreferenceDataStoreFactory.create {
        File(context.noBackupFilesDir, "provider.preferences_pb")
    }
    private val baseKey = stringPreferencesKey("base_url")
    private val modelKey = stringPreferencesKey("model")
    private val secretKey = stringPreferencesKey("key_ciphertext")
    private val visionKey = stringPreferencesKey("vision_model")

    suspend fun info(): ProviderInfo {
        val prefs = store.data.first()
        return ProviderInfo(prefs[baseKey] ?: DefaultBaseUrl, prefs[modelKey].orEmpty(), prefs[secretKey] != null,
            prefs[visionKey].orEmpty())
    }

    suspend fun config(): ProviderConfig? = withContext(Dispatchers.IO) {
        val prefs = store.data.first()
        val ciphertext = prefs[secretKey] ?: return@withContext null
        val model = prefs[modelKey] ?: return@withContext null
        val key = readKey(ciphertext)
        ProviderConfig(prefs[baseKey] ?: DefaultBaseUrl, model, key, prefs[visionKey].orEmpty())
    }

    suspend fun draft(base: String, model: String, apiKey: String, vision: String = ""): ProviderConfig =
        withContext(Dispatchers.IO) {
        val url = endpoint(base) ?: throw LookupFailure("请输入有效的 HTTPS 接口地址。", "Enter a valid HTTPS endpoint.")
        val name = model.trim()
        if (listOf(name, vision.trim()).any { it.length > 150 }) throw LookupFailure("模型名称过长，请从模型列表选择。", "The model ID is too long. Choose a model from the list.")
        val typed = apiKey.trim()
        if (typed.length > 2048 || typed.any { it.code !in 33..126 }) {
            throw LookupFailure("API Key 含有无效字符。", "The API key contains invalid characters.")
        }
        if (typed.isNotEmpty()) return@withContext ProviderConfig(base.trim(), name, typed, vision.trim())
        val prefs = store.data.first()
        val ciphertext = prefs[secretKey]
        if (ciphertext == null || endpoint(prefs[baseKey] ?: DefaultBaseUrl) != url) {
            throw LookupFailure("首次配置或切换接口时，请填写 API Key。", "Enter an API key when setting up or switching endpoints.")
        }
        ProviderConfig(base.trim(), name, readKey(ciphertext), vision.trim())
    }

    suspend fun save(config: ProviderConfig): ProviderInfo = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            prefs[secretKey] = encrypt(config.apiKey)
            prefs[baseKey] = config.baseUrl
            prefs[modelKey] = config.model
            prefs[visionKey] = config.visionModel
        }
        ProviderInfo(config.baseUrl, config.model, hasKey = true, config.visionModel)
    }

    private fun readKey(ciphertext: String): String = try {
        decrypt(ciphertext)
    } catch (_: java.security.GeneralSecurityException) {
        throw LookupFailure("无法读取保存的 API Key，请在设置中重新填写。", "The saved API key could not be read. Enter it again in Settings.", opensSettings = true)
    } catch (_: IllegalArgumentException) {
        throw LookupFailure("无法读取保存的 API Key，请在设置中重新填写。", "The saved API key could not be read. Enter it again in Settings.", opensSettings = true)
    }

    private fun key(create: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "lexi.provider"
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw java.security.KeyStoreException("Missing encryption key")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        require(data.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return cipher.doFinal(data, 12, data.size - 12).toString(Charsets.UTF_8)
    }
}
