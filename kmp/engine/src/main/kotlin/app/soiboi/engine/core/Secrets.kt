package app.soiboi.engine.core

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/** Where passwords, tokens and API keys live. Android supplies a Keystore-backed store; the default is a file-backed one. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Secrets in a single AES-GCM encrypted file beside the settings, with the key in a separate owner-only file.
 * It keeps credentials out of the plain-text config and out of backups of it, which is what the original's
 * "secure storage with a plain-text fallback" did on a bare Linux session with no keyring.
 */
class FileSecretStore(private val dir: File) : SecretStore {
    private val keyFile = File(dir, "secret.key")
    private val dataFile = File(dir, "secrets.bin")
    private val random = SecureRandom()

    private fun key(): SecretKeySpec {
        if (!keyFile.exists()) {
            dir.mkdirs()
            keyFile.writeBytes(ByteArray(32).also { random.nextBytes(it) })
            keyFile.setReadable(false, false); keyFile.setReadable(true, true); keyFile.setWritable(false, false); keyFile.setWritable(true, true)
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    private fun load(): MutableMap<String, String> {
        if (!dataFile.exists()) return HashMap()
        return try {
            val raw = dataFile.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
            val text = String(c.doFinal(raw, 12, raw.size - 12))
            text.lines().filter { '\t' in it }.associate { it.substringBefore('\t') to String(Base64.getDecoder().decode(it.substringAfter('\t'))) }.toMutableMap()
        } catch (e: Exception) {
            EngineLog.output("secrets: unreadable, starting empty: $e"); HashMap()
        }
    }

    private fun save(map: Map<String, String>) {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val text = map.entries.joinToString("\n") { "${it.key}\t${Base64.getEncoder().encodeToString(it.value.toByteArray())}" }
        dataFile.writeBytes(iv + c.doFinal(text.toByteArray()))
        dataFile.setReadable(false, false); dataFile.setReadable(true, true)
    }

    @Synchronized override fun get(key: String) = load()[key]
    @Synchronized override fun put(key: String, value: String?) { val m = load(); if (value == null) m.remove(key) else m[key] = value; save(m) }
}
