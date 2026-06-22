package com.easybc.planner.sync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SyncCrypto {
    private val aad = "easy-bc-sync-envelope-v1".toByteArray(Charsets.UTF_8)
    private val hkdfInfo = "easy-bc-cloud-content-key-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    fun randomBytes(length: Int): ByteArray = ByteArray(length).also(random::nextBytes)

    fun encrypt(
        payload: SyncPayloadV1,
        secret: ByteArray,
        credentialId: String,
        rpId: String,
        prfInput: ByteArray,
        kdfSalt: ByteArray,
    ): SyncEnvelopeV1 = encryptWithNonce(
        payload, secret, credentialId, rpId, prfInput, kdfSalt, randomBytes(12)
    )

    internal fun encryptWithNonce(
        payload: SyncPayloadV1,
        secret: ByteArray,
        credentialId: String,
        rpId: String,
        prfInput: ByteArray,
        kdfSalt: ByteArray,
        nonce: ByteArray,
    ): SyncEnvelopeV1 {
        require(nonce.size == 12) { "AES-GCM nonce must be 12 bytes." }
        val key = deriveContentKey(secret, kdfSalt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val plaintext = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        key.fill(0)
        return SyncEnvelopeV1(
            credentialId = credentialId,
            rpId = rpId,
            prfInput = base64Url(prfInput),
            kdfSalt = base64Url(kdfSalt),
            nonce = base64Url(nonce),
            ciphertext = base64Url(ciphertext),
            updatedAt = payload.exportedAt,
        )
    }

    fun decrypt(envelope: SyncEnvelopeV1, secret: ByteArray): SyncPayloadV1 {
        validateEnvelope(envelope)
        val key = deriveContentKey(secret, decodeBase64Url(envelope.kdfSalt))
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, decodeBase64Url(envelope.nonce)),
            )
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(decodeBase64Url(envelope.ciphertext))
            json.decodeFromString(plaintext.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalArgumentException("This passkey could not decrypt the EasyBC snapshot.", error)
        } finally {
            key.fill(0)
        }
    }

    fun parseEnvelope(value: String): SyncEnvelopeV1 =
        try {
            json.decodeFromString<SyncEnvelopeV1>(value).also(::validateEnvelope)
        } catch (error: Exception) {
            throw IllegalArgumentException("The Drive file is not a supported EasyBC encrypted snapshot.", error)
        }

    private fun validateEnvelope(envelope: SyncEnvelopeV1) {
        require(envelope.schemaVersion == 1)
        require(envelope.algorithm == "AES-256-GCM+HKDF-SHA-256")
        require(envelope.credentialId.isNotBlank())
        require(envelope.rpId.isNotBlank())
        require(envelope.prfInput.isNotBlank())
        require(envelope.kdfSalt.isNotBlank())
        require(envelope.nonce.isNotBlank())
        require(envelope.ciphertext.isNotBlank())
    }

    /** RFC 5869 HKDF-SHA-256, matching WebCrypto's HKDF deriveKey. */
    private fun deriveContentKey(inputKeyMaterial: ByteArray, salt: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudoRandomKey = extract.doFinal(inputKeyMaterial)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
        expand.update(hkdfInfo)
        expand.update(1)
        val output = expand.doFinal().copyOf(32)
        pseudoRandomKey.fill(0)
        return output
    }
}
