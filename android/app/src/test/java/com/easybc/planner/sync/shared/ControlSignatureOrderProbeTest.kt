package com.easybc.planner.sync.shared

import com.keyneom.synckit.crypto.CanonicalJson
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSignatureOrderProbeTest {
    @Test
    fun findsThePreNormalizationArrayOrder() {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val encodedPublicKey = export(keys.public as ECPublicKey)
        val member = buildJsonObject {
            put("publicKey", buildJsonObject {
                put("keyId", "owner")
                put("encryptionAlgorithm", "ECDH-P256")
                put("encryptionPublicKey", encodedPublicKey)
                put("signatureAlgorithm", "ECDSA-P256-SHA256-P1363")
                put("signingPublicKey", encodedPublicKey)
            })
        }
        val genesisUnsigned = eventBase("genesis", 0, "member-upsert") + ("member" to member)
        val genesis = signed(JsonObject(genesisUnsigned), keys.private)

        val migration = buildJsonObject {
            put("migrationId", "migration")
            put("sourceDatasetIds", strings("source-z", "source-a"))
            put("targets", buildJsonArray {
                add(target("target-z", "file-z"))
                add(target("target-a", "file-a"))
            })
            put("requiredAcks", buildJsonArray {
                add(buildJsonObject {
                    put("keyId", "recipient")
                    put("targetFileIds", strings("file-z", "file-a"))
                })
            })
            put("mode", "hard-cutover")
        }
        val announcedUnsigned = JsonObject(
            eventBase("announcement", 1, "migration-announced") + ("migration" to migration),
        )
        val announcedSignature = sign(announcedUnsigned, keys.private)
        val normalizedMigration = JsonObject(
            migration + mapOf(
                "sourceDatasetIds" to strings("source-a", "source-z"),
                "requiredAcks" to buildJsonArray {
                    add(buildJsonObject {
                        put("keyId", "recipient")
                        put("targetFileIds", strings("file-a", "file-z"))
                    })
                },
            ),
        )
        val storedAnnouncement = JsonObject(
            announcedUnsigned + mapOf(
                "migration" to normalizedMigration,
                "signature" to JsonPrimitive(announcedSignature),
            ),
        )
        val state = buildJsonObject {
            put("events", JsonArray(listOf(genesis, storedAnnouncement)))
        }

        val result = probeControlSignatureOrdering(state)

        assertEquals("stored-order", result[0].match)
        assertEquals("reversed-order", result[1].match)
        assertTrue(result[1].attempts > 1)
    }

    private fun eventBase(eventId: String, sequence: Int, type: String) = mapOf(
        "schemaVersion" to JsonPrimitive(1),
        "kind" to JsonPrimitive("sync-kit-sharing-control-event"),
        "eventId" to JsonPrimitive(eventId),
        "profileId" to JsonPrimitive("profile"),
        "actorKeyId" to JsonPrimitive("owner"),
        "sequence" to JsonPrimitive(sequence),
        "createdAt" to JsonPrimitive("2026-07-13T00:00:00.000Z"),
        "type" to JsonPrimitive(type),
    )

    private fun target(datasetId: String, fileId: String) = buildJsonObject {
        put("datasetId", datasetId)
        put("fileId", fileId)
    }

    private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    private fun signed(unsigned: JsonObject, privateKey: java.security.PrivateKey) =
        JsonObject(unsigned + ("signature" to JsonPrimitive(sign(unsigned, privateKey))))

    private fun sign(unsigned: JsonObject, privateKey: java.security.PrivateKey): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(CanonicalJson.encodeAad(unsigned))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(derToP1363(signer.sign()))
    }

    private fun export(publicKey: ECPublicKey): String {
        fun component(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return when {
                bytes.size == 32 -> bytes
                bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
                else -> ByteArray(32 - bytes.size) + bytes
            }
        }
        val raw = byteArrayOf(4) + component(publicKey.w.affineX) + component(publicKey.w.affineY)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var index = 2
        fun integer(): ByteArray {
            require(der[index++] == 0x02.toByte())
            val length = der[index++].toInt() and 0xff
            return der.copyOfRange(index, index + length).also { index += length }
        }
        fun component(value: ByteArray): ByteArray {
            val unsigned = value.dropWhile { it == 0.toByte() }.toByteArray()
            require(unsigned.size <= 32)
            return ByteArray(32 - unsigned.size) + unsigned
        }
        return component(integer()) + component(integer())
    }
}
