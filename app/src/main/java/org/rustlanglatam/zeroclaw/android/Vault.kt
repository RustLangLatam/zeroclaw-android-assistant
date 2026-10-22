package org.rustlanglatam.zeroclaw.android

import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the app's own files to this device (ADR-0023) with AES-256-GCM under a non-exportable key in the
 * Android Keystore (hardware-backed when available). Copies on another device, or restored after a reset, cannot
 * be decrypted. Sealed text is `ks1:` + Base64(IV ‖ ciphertext ‖ tag); text without the prefix is legacy plaintext,
 * returned as is and sealed on its next write. ZeroClaw's own files are not sealed because the daemon reads them
 * directly.
 */
object Vault {
    private const val PREFIX = "ks1:"
    private const val ALIAS = "ksandra-vault"

    /** The encryption key; while `null` (JVM tests, or before [open]) text passes through unchanged. */
    @Volatile var key: SecretKey? = null

    /** Loads the Keystore key, generating it on first use. */
    fun open() {
        if (key != null) return
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        key = (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build(),
            )
            generateKey()
        }
    }

    fun seal(text: String): String {
        val k = key ?: return text
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()))
    }

    /** Decrypts [sealed]; throws when it was sealed with a different key (another device). */
    fun unseal(sealed: String): String {
        if (!sealed.startsWith(PREFIX)) return sealed
        val k = key ?: error("sealed, and no key")
        val bytes = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, bytes, 0, 12)) }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}

/** Writes [text] to the file, sealed with [Vault]. */
fun File.writeSealed(text: String) = replaceWith(Vault.seal(text))

fun File.readSealed(): String = Vault.unseal(readText())

/** Appends [line] to a line-oriented file, sealing each line independently. */
fun File.appendSealed(line: String) = appendText(Vault.seal(line) + "\n")

/** Returns the decrypted lines; if any line is legacy plaintext, rewrites the file sealed. */
fun File.readSealedLines(): List<String> {
    val raw = readLines().filter(String::isNotEmpty)
    val lines = raw.map(Vault::unseal)
    if (Vault.key != null && raw.any { !it.startsWith("ks1:") }) writeSealedLines(lines)
    return lines
}

fun File.writeSealedLines(lines: List<String>) = replaceWith(lines.joinToString("") { Vault.seal(it) + "\n" })

/** Opens the [Vault] before any component (service, activities, receivers) reads a file. */
class KsandraApplication : android.app.Application() {
    private var phone = java.util.Locale.getDefault().language

    override fun onCreate() {
        Strings.init(this)
        Lexicon.init(this)
        Keys.init(this)
        super.onCreate()
        Vault.open()
    }

    /** On a system locale change, when the app follows the system language, rewrites the agent identity and invokes [Language.onChange]. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val now = newConfig.locales[0].language
        if (now == phone) return
        phone = now
        if (!Language.followsPhone) return
        Thread { AssistantService.writeIdentity(this) }.start()
        Language.onChange()
    }
}
