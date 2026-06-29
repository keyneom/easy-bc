package com.easybc.planner.sync

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Evaluates the WebAuthn PRF extension through Android Credential Manager.
 * The request/response JSON is the same WebAuthn JSON used by the web app.
 */
open class PasskeyPrfClient {
    open suspend fun create(activity: Activity): PasskeyMaterial {
        val prfInput = SyncCrypto.randomBytes(32)
        val kdfSalt = SyncCrypto.randomBytes(32)
        val request = createRequest(prfInput)
        val response = CredentialManager.create(activity).createCredential(
            context = activity,
            request = CreatePublicKeyCredentialRequest(request.toString()),
        ) as? CreatePublicKeyCredentialResponse
            ?: error("Passkey creation did not return a public-key credential.")
        val parsed = SyncCrypto.json.parseToJsonElement(response.registrationResponseJson).jsonObject
        val credentialId = parsed.string("rawId") ?: parsed.string("id")
            ?: error("Passkey creation did not return a credential id.")
        val secret = prfResult(parsed) ?: unlock(activity, credentialId, SyncCrypto.base64Url(prfInput))
        return PasskeyMaterial(credentialId, prfInput, kdfSalt, secret)
    }

    open suspend fun unlock(activity: Activity, credentialId: String, prfInput: String): ByteArray {
        val option = GetPublicKeyCredentialOption(getRequest(credentialId, prfInput).toString())
        val response = CredentialManager.create(activity).getCredential(
            context = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build(),
        )
        val credential = response.credential as? PublicKeyCredential
            ?: error("Passkey selection did not return a public-key credential.")
        val parsed = SyncCrypto.json.parseToJsonElement(credential.authenticationResponseJson).jsonObject
        return prfResult(parsed)
            ?: error("This passkey provider did not return a PRF secret. Try current Google Password Manager and Chrome.")
    }

    private fun createRequest(prfInput: ByteArray): JsonObject = buildJsonObject {
        put("rp", buildJsonObject {
            put("id", SYNC_RP_ID)
            put("name", "EasyBC")
        })
        put("user", buildJsonObject {
            put("id", SyncCrypto.base64Url(SyncCrypto.randomBytes(32)))
            put("name", "encrypted-sync")
            put("displayName", "EasyBC encrypted sync")
        })
        put("challenge", SyncCrypto.base64Url(SyncCrypto.randomBytes(32)))
        put("pubKeyCredParams", buildJsonArray {
            add(buildJsonObject {
                put("type", "public-key")
                put("alg", -7)
            })
        })
        put("authenticatorSelection", buildJsonObject {
            put("residentKey", "required")
            put("requireResidentKey", true)
            put("userVerification", "required")
        })
        put("timeout", 60_000)
        put("attestation", "none")
        put("extensions", prfExtensions(SyncCrypto.base64Url(prfInput)))
    }

    private fun getRequest(credentialId: String, prfInput: String): JsonObject = buildJsonObject {
        put("challenge", SyncCrypto.base64Url(SyncCrypto.randomBytes(32)))
        put("rpId", SYNC_RP_ID)
        put("allowCredentials", buildJsonArray {
            add(buildJsonObject {
                put("type", "public-key")
                put("id", credentialId)
            })
        })
        put("userVerification", "required")
        put("timeout", 60_000)
        put("extensions", prfExtensions(prfInput))
    }

    private fun prfExtensions(prfInput: String): JsonObject = buildJsonObject {
        put("prf", buildJsonObject {
            put("eval", buildJsonObject { put("first", prfInput) })
        })
    }

    private fun prfResult(response: JsonObject): ByteArray? {
        val value = runCatching {
            val prf = response["clientExtensionResults"]!!.jsonObject["prf"]!!.jsonObject
            prf["results"]!!.jsonObject["first"]!!.jsonPrimitive.content
        }.getOrNull() ?: return null
        return SyncCrypto.decodeBase64Url(value)
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
}
