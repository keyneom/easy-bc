package com.easybc.planner.sync.shared

import com.keyneom.synckit.crypto.CanonicalJson
import com.keyneom.synckit.sharing.SharedBackupControllerCodec
import com.keyneom.synckit.sharing.SharingControlStateV1
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.createSharingControlCodec
import com.keyneom.synckit.sharing.verifySharingControlStateV1
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal data class ControlSignatureOrderProbeResult(
    val eventId: String,
    val type: String,
    val sequence: Long,
    val match: String?,
    val attempts: Int,
)

internal data class LegacyControlSignatureRepair(
    val state: SharingControlStateV1,
    val eventId: String,
)

/**
 * Re-attests the one confirmed rc.15 migration-announcement defect using the
 * original owner key. No event semantics or ids change; only the signature is
 * replaced with one over rc.16's normalized representation.
 */
internal fun repairLegacyControlSignature(
    state: JsonObject,
    identity: SharingIdentity,
): LegacyControlSignatureRepair? {
    val results = probeControlSignatureOrdering(state)
    if (results.any { it.match == null }) return null
    val repair = results.singleOrNull {
        it.type == "migration-announced" && it.match == "target-order"
    } ?: return null
    if (results.any { it.eventId != repair.eventId && it.match != "stored-order" }) return null

    val events = state.getValue("events").jsonArray.map { it.jsonObject }
    val genesis = events.minWithOrNull(
        compareBy<JsonObject> { it.getValue("sequence").jsonPrimitive.long }
            .thenBy { it.getValue("eventId").jsonPrimitive.content },
    ) ?: return null
    val ownerKeyId = genesis["member"]?.jsonObject
        ?.get("publicKey")?.jsonObject
        ?.get("keyId")?.jsonPrimitive?.content ?: return null
    if (identity.publicKey.keyId != ownerKeyId) return null

    val repairedEvents = events.map { event ->
        if (event.getValue("eventId").jsonPrimitive.content != repair.eventId) return@map event
        if (event.getValue("actorKeyId").jsonPrimitive.content != ownerKeyId) return null
        val unsigned = JsonObject(event - "signature")
        JsonObject(event + ("signature" to JsonPrimitive(sign(identity, unsigned))))
    }
    val repairedJson = JsonObject(state + ("events" to JsonArray(repairedEvents)))
    val repairedState = createSharingControlCodec().parse(repairedJson)
    verifySharingControlStateV1(repairedState, ownerKeyId)
    return LegacyControlSignatureRepair(repairedState, repair.eventId)
}

/** Codec used only for the owner's one-time publication of a repaired event. */
internal object LegacyControlRepairCodec : SharedBackupControllerCodec<SharingControlStateV1> {
    private val standard = createSharingControlCodec()

    override fun serialize(value: SharingControlStateV1): JsonElement = standard.serialize(value)

    override fun parse(value: JsonElement): SharingControlStateV1 = standard.parse(value)

    override fun fingerprint(value: SharingControlStateV1): String = standard.fingerprint(value)

    override fun merge(
        local: SharingControlStateV1,
        remote: SharingControlStateV1,
    ): SharingControlStateV1 = runCatching { standard.merge(local, remote) }.getOrElse { conflict ->
        val localJson = standard.serialize(local).jsonObject
        val remoteJson = standard.serialize(remote).jsonObject
        if (withoutEventSignatures(localJson) != withoutEventSignatures(remoteJson)) throw conflict
        val ownerKeyId = local.events.minWithOrNull(
            compareBy<com.keyneom.synckit.sharing.SharingControlEventV1> { it.sequence }
                .thenBy { it.eventId },
        )?.actorKeyId ?: throw conflict
        val localValid = runCatching { verifySharingControlStateV1(local, ownerKeyId) }.isSuccess
        val remoteLegacyValid = probeControlSignatureOrdering(remoteJson).all { it.match != null }
        if (!localValid || !remoteLegacyValid) throw conflict
        local
    }
}

/**
 * Diagnostic-only verifier for control ledgers written by sync-kit rc.15.
 *
 * rc.15 signed set-like string arrays before parsing sorted them. The original
 * ordering is not retained, so this checks a strictly bounded set of candidate
 * orderings without logging keys, file ids, signatures, or decrypted payloads.
 */
internal fun probeControlSignatureOrdering(
    state: JsonObject,
    maxAttemptsPerEvent: Int = 5_000,
): List<ControlSignatureOrderProbeResult> {
    val members = mutableMapOf<String, PublicKey>()
    val events = state.getValue("events").jsonArray
        .map { it.jsonObject }
        .sortedWith(compareBy<JsonObject> { it.getValue("sequence").jsonPrimitive.long }
            .thenBy { it.getValue("eventId").jsonPrimitive.content })

    return events.map { event ->
        val actorKeyId = event.getValue("actorKeyId").jsonPrimitive.content
        val type = event.getValue("type").jsonPrimitive.content
        val member = event["member"]?.jsonObject
        val actorKey = members[actorKeyId] ?: member?.let(::memberSigningKey)
        val candidates = signatureCandidates(event, maxAttemptsPerEvent)
        var attempts = 0
        val match = actorKey?.let { publicKey ->
            candidates.firstNotNullOfOrNull { (label, unsigned) ->
                attempts += 1
                label.takeIf { verify(publicKey, unsigned, event.getValue("signature").jsonPrimitive.content) }
            }
        }
        if (type == "member-upsert" && member != null) {
            val keyId = member.getValue("publicKey").jsonObject
                .getValue("keyId").jsonPrimitive.content
            members[keyId] = memberSigningKey(member)
        }
        ControlSignatureOrderProbeResult(
            eventId = event.getValue("eventId").jsonPrimitive.content,
            type = type,
            sequence = event.getValue("sequence").jsonPrimitive.long,
            match = match,
            attempts = attempts,
        )
    }
}

private fun signatureCandidates(
    event: JsonObject,
    maxAttempts: Int,
): Sequence<Pair<String, JsonObject>> = sequence {
    val unsigned = JsonObject(event - "signature")
    yield("stored-order" to unsigned)

    val paths = setLikeArrayPaths(unsigned)
    if (paths.isEmpty()) return@sequence

    targetOrderCandidate(unsigned)?.takeIf { it != unsigned }?.let {
        yield("target-order" to it)
    }
    reversedCandidate(unsigned, paths).takeIf { it != unsigned }?.let {
        yield("reversed-order" to it)
    }

    var emitted = 3
    val seen = mutableSetOf(CanonicalJson.encode(unsigned))
    targetOrderCandidate(unsigned)?.let { seen += CanonicalJson.encode(it) }
    seen += CanonicalJson.encode(reversedCandidate(unsigned, paths))
    for (candidate in permutedCandidates(unsigned, paths, maxAttempts)) {
        if (emitted >= maxAttempts) break
        if (seen.add(CanonicalJson.encode(candidate))) {
            emitted += 1
            yield("permutation-$emitted" to candidate)
        }
    }
}

private data class ArrayPath(val requirementIndex: Int? = null, val field: String)

private fun setLikeArrayPaths(event: JsonObject): List<ArrayPath> = when (
    event.getValue("type").jsonPrimitive.content
) {
    "migration-announced" -> buildList {
        add(ArrayPath(field = "sourceDatasetIds"))
        event.getValue("migration").jsonObject.getValue("requiredAcks").jsonArray
            .indices.forEach { add(ArrayPath(requirementIndex = it, field = "targetFileIds")) }
    }
    "migration-acknowledged" -> listOf(ArrayPath(field = "openedFileIds"))
    else -> emptyList()
}

private fun targetOrderCandidate(event: JsonObject): JsonObject? {
    if (event.getValue("type").jsonPrimitive.content != "migration-announced") return null
    val migration = event.getValue("migration").jsonObject
    val order = migration.getValue("targets").jsonArray.mapIndexed { index, target ->
        target.jsonObject.getValue("fileId").jsonPrimitive.content to index
    }.toMap()
    val requirements = migration.getValue("requiredAcks").jsonArray.map { raw ->
        val requirement = raw.jsonObject
        val ids = requirement.getValue("targetFileIds").jsonArray
            .sortedBy { order[it.jsonPrimitive.content] ?: Int.MAX_VALUE }
        JsonObject(requirement + ("targetFileIds" to JsonArray(ids)))
    }
    return replaceMigration(event, JsonObject(migration + ("requiredAcks" to JsonArray(requirements))))
}

private fun reversedCandidate(event: JsonObject, paths: List<ArrayPath>): JsonObject =
    paths.fold(event) { candidate, path ->
        replaceArray(candidate, path, JsonArray(arrayAt(candidate, path).reversed()))
    }

private fun permutedCandidates(
    event: JsonObject,
    paths: List<ArrayPath>,
    limit: Int,
): Sequence<JsonObject> {
    var candidates: Sequence<JsonObject> = sequenceOf(event)
    for (path in paths) {
        candidates = candidates.flatMap { candidate ->
            val values = arrayAt(candidate, path)
            permutations(values, limit).map { replaceArray(candidate, path, JsonArray(it)) }
        }.take(limit)
    }
    return candidates.take(limit)
}

private fun permutations(values: List<JsonElement>, limit: Int): Sequence<List<JsonElement>> = sequence {
    if (values.size > 7) {
        yield(values)
        yield(values.reversed())
        return@sequence
    }
    var emitted = 0
    suspend fun SequenceScope<List<JsonElement>>.visit(
        remaining: List<JsonElement>,
        prefix: List<JsonElement>,
    ) {
        if (emitted >= limit) return
        if (remaining.isEmpty()) {
            emitted += 1
            yield(prefix)
            return
        }
        for (index in remaining.indices) {
            visit(remaining.filterIndexed { candidate, _ -> candidate != index }, prefix + remaining[index])
            if (emitted >= limit) return
        }
    }
    visit(values, emptyList())
}

private fun arrayAt(event: JsonObject, path: ArrayPath): List<JsonElement> {
    if (event.getValue("type").jsonPrimitive.content == "migration-acknowledged") {
        return event.getValue(path.field).jsonArray
    }
    val migration = event.getValue("migration").jsonObject
    if (path.requirementIndex == null) return migration.getValue(path.field).jsonArray
    return migration.getValue("requiredAcks").jsonArray[path.requirementIndex]
        .jsonObject.getValue(path.field).jsonArray
}

private fun replaceArray(event: JsonObject, path: ArrayPath, replacement: JsonArray): JsonObject {
    if (event.getValue("type").jsonPrimitive.content == "migration-acknowledged") {
        return JsonObject(event + (path.field to replacement))
    }
    val migration = event.getValue("migration").jsonObject
    if (path.requirementIndex == null) {
        return replaceMigration(event, JsonObject(migration + (path.field to replacement)))
    }
    val requirements = migration.getValue("requiredAcks").jsonArray.toMutableList()
    val requirement = requirements[path.requirementIndex].jsonObject
    requirements[path.requirementIndex] = JsonObject(requirement + (path.field to replacement))
    return replaceMigration(
        event,
        JsonObject(migration + ("requiredAcks" to JsonArray(requirements))),
    )
}

private fun replaceMigration(event: JsonObject, migration: JsonObject): JsonObject =
    JsonObject(event + ("migration" to migration))

private fun memberSigningKey(member: JsonObject): PublicKey {
    val encoded = member.getValue("publicKey").jsonObject
        .getValue("signingPublicKey").jsonPrimitive.content
    val raw = Base64.getUrlDecoder().decode(encoded)
    require(raw.size == 65 && raw[0] == 4.toByte())
    val parameters = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)
    return KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))),
            parameters,
        ),
    )
}

private fun withoutEventSignatures(state: JsonObject): JsonObject {
    val events = state.getValue("events").jsonArray.map { raw ->
        JsonObject(raw.jsonObject - "signature")
    }
    return JsonObject(state + ("events" to JsonArray(events)))
}

private fun sign(identity: SharingIdentity, unsigned: JsonObject): String {
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(identity.signingPrivateKey)
    signer.update(CanonicalJson.encodeAad(unsigned))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(derToP1363(signer.sign()))
}

private fun verify(publicKey: PublicKey, unsigned: JsonObject, encodedSignature: String): Boolean =
    runCatching {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(CanonicalJson.encodeAad(unsigned))
        verifier.verify(p1363ToDer(Base64.getUrlDecoder().decode(encodedSignature)))
    }.getOrDefault(false)

private fun p1363ToDer(raw: ByteArray): ByteArray {
    require(raw.size == 64)
    fun integer(component: ByteArray): ByteArray {
        val trimmed = component.dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (trimmed.isEmpty()) byteArrayOf(0) else trimmed
        val positive = if (stripped[0].toInt() and 0x80 != 0) byteArrayOf(0) + stripped else stripped
        return byteArrayOf(0x02, positive.size.toByte()) + positive
    }
    val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
    return byteArrayOf(0x30, body.size.toByte()) + body
}

private fun derToP1363(der: ByteArray): ByteArray {
    require(der.size > 2 && der[0] == 0x30.toByte())
    var index = 2
    fun integer(): ByteArray {
        require(index + 2 <= der.size && der[index++] == 0x02.toByte())
        val length = der[index++].toInt() and 0xff
        require(index + length <= der.size)
        return der.copyOfRange(index, index + length).also { index += length }
    }
    fun component(value: ByteArray): ByteArray {
        val unsigned = value.dropWhile { it == 0.toByte() }.toByteArray()
        require(unsigned.size <= 32)
        return ByteArray(32 - unsigned.size) + unsigned
    }
    return component(integer()) + component(integer())
}
