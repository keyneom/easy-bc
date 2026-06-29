package com.easybc.planner.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCryptoTest {
    @Test
    fun decryptsSharedGzipCryptoVector() {
        val envelope = SyncEnvelopeV1(
            compression = "gzip",
            credentialId = "credential",
            rpId = SYNC_RP_ID,
            prfInput = "AA",
            kdfSalt = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
            nonce = "QEFCQ0RFRkdISUpL",
            ciphertext = "LMBOEaK5ATYQxI26eKGwIdJ_ELsBJ8rf76MIXyxVsT3kN1K3qY42h07AZHOYkztpJYdhERCZmbG2j_Pd1zhqKRMIMdrj_-pBd6DljWZU0uD7d5rHWCbyAqudt_psdPSHm47MuQauJTsCjVmEkPNKCZZNOZkYnceNyC9MZQM_tY15jJLsHEdOnQS43zyNhv180UmM9POJcsaWBAG6yIfDRDpd2b1IpPuxmIAV3GLMYLiNHbJ4yDUX6PRYFhzI-Hh18rggXBENhWfq9kZ3hBpPKGkms-j4MHFIGhg_o3GY_rZTHCzq3phTdD3Mo4iSWf7D6FXezQP6H_jwyhDE0ydJyTjNfIPPyCESZtc",
            updatedAt = "2026-06-22T12:00:00Z",
        )
        val secret = SyncCrypto.decodeBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")

        val payload = SyncCrypto.decrypt(envelope, secret)

        assertEquals(38, payload.planner.value.ageYears)
        assertEquals(
            true,
            payload.calendarDayLogs.getValue("2026-06-22").notes
                ?.contains("cross-platform gzip vector"),
        )
    }

    @Test
    fun decryptsWebCryptoVector() {
        val envelope = SyncEnvelopeV1(
            credentialId = "credential",
            rpId = SYNC_RP_ID,
            prfInput = "AA",
            kdfSalt = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
            nonce = "QEFCQ0RFRkdISUpL",
            ciphertext = "SGk1csrcbFdGshJaXqUdM-SLg9O0ZjtkHuUREJkV5R89VrpDkTmr12IlZ5W6d4Qk4pQ0kVT9XO7F_r4OHtoWNGbjzrdv49y2LhT-uDLjzNu38r-xVoQXGU3HTJdvr6VFk18TbaKAN08gD3wluMP9JH_IKwpGHeAgZyoajp3agwg4bGV-VSw9URAFlHrXtgJKymwNPz_4E4UHbmjcnCK94JngZgqYXnhBueTwX4fTayIrzqXl_JEcGDm6aEcae_FoqhZ1dVUVx5KynVj_JRgPMKOtviGe-LLk7aXbCoRLxSjZDU4nlPQOV2dFkbrwpV6afwsu1IQWwZ-xvnDovfN8eHzVuvdb7JgSIShs62Yy5N-wgCm-BUWxHp5oq0LUFkGB2njyTQjkmSYBiM4eVOYvhBTIRNQZqFMXSEI32-NyCEaLvv63y43OkQ9-kpWKZ7-yDpM-Mcs5ckNk3IKTHTGhl_0DMpZxA5QONtGvDCW4SHr8dKEQ6aDQ",
            updatedAt = "2026-06-21T12:00:00Z",
        )
        val secret = SyncCrypto.decodeBase64Url("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")

        val payload = SyncCrypto.decrypt(envelope, secret)

        assertEquals(37, payload.planner.value.ageYears)
        assertEquals("2026-06-21T12:00:00Z", payload.exportedAt)
    }

    @Test
    fun roundTripsAndroidPayload() {
        val payload = payload(age = 41, updatedAt = "2026-06-21T13:00:00Z")
        val secret = ByteArray(32) { it.toByte() }
        val salt = ByteArray(32) { (it + 32).toByte() }
        val envelope = SyncCrypto.encryptWithNonce(
            payload,
            secret,
            "credential",
            SYNC_RP_ID,
            ByteArray(32) { 7 },
            salt,
            ByteArray(12) { (it + 64).toByte() },
        )

        assertEquals(payload, SyncCrypto.decrypt(envelope, secret))
    }

    @Test
    fun roundTripsWithOnlyTheDerivedSessionKey() {
        val payload = payload(age = 39, updatedAt = "2026-06-29T13:00:00Z")
        val secret = ByteArray(32) { it.toByte() }
        val salt = ByteArray(32) { (it + 32).toByte() }
        val contentKey = SyncCrypto.deriveContentKey(secret, salt)
        val envelope = SyncCrypto.encryptWithContentKey(
            payload,
            contentKey,
            "credential",
            SYNC_RP_ID,
            ByteArray(32) { 7 },
            salt,
        )

        assertEquals(payload, SyncCrypto.decryptWithContentKey(envelope, contentKey))
    }

    @Test
    fun compressesRepetitivePayloadBeforeEncryption() {
        val payload = payload(age = 41, updatedAt = "2026-06-21T13:00:00Z").copy(
            calendarDayLogs = mapOf(
                "2026-06-21" to SyncDayLog(
                    notes = "repeated private journal text ".repeat(200),
                    updatedAt = "2026-06-21T13:00:00Z",
                )
            )
        )
        val secret = ByteArray(32) { it.toByte() }
        val envelope = SyncCrypto.encryptWithNonce(
            payload,
            secret,
            "credential",
            SYNC_RP_ID,
            ByteArray(32) { 7 },
            ByteArray(32) { (it + 32).toByte() },
            ByteArray(12) { (it + 64).toByte() },
        )

        assertEquals("gzip", envelope.compression)
        assertEquals(payload, SyncCrypto.decrypt(envelope, secret))
    }

    private fun payload(age: Int, updatedAt: String) = SyncPayloadV1(
        exportedAt = updatedAt,
        planner = TimestampedPlanner(SyncPlannerOptions(ageYears = age), updatedAt),
    )
}
