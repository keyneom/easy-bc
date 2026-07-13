package com.easybc.planner.sync.shared

import com.easybc.planner.sync.SyncDayLog
import com.easybc.planner.sync.SyncPayloadStore
import com.easybc.planner.sync.SyncPayloadV1
import com.easybc.planner.sync.hasDataForDatasetPart
import com.keyneom.synckit.sharing.SharingDatasetGrantV1
import com.keyneom.synckit.sharing.SharingRole

/*
 * Multi-file dataset split — Kotlin mirror of web/src/sync/datasets.ts
 * (docs/sync-kit-multi-file-datasets.md). Keep the two in sync: both
 * platforms must project the same fields into the same files.
 *
 *   plan       <base>            planner options & plan outputs
 *   cycle      <base>.cycle      period records + body-signal day-log fields
 *   intimacy   <base>.intimacy   logged acts, incidents, abstinence credits
 *   sensitive  <base>.sensitive  EC journal + EC events
 *
 * Each part file carries a full SyncPayloadV1 shape with only its own
 * sections populated, so the existing codec/merge/fingerprint machinery
 * works per file. Legacy profiles keep everything in the single <base> file
 * (ProfileRecord.datasetGrants == null).
 */

const val PART_PLAN = "plan"
const val PART_CYCLE = "cycle"
const val PART_INTIMACY = "intimacy"
const val PART_SENSITIVE = "sensitive"
const val CONTROL_DATASET_SUFFIX = ".control"

val DATASET_PARTS: List<String> = listOf(PART_PLAN, PART_CYCLE, PART_INTIMACY, PART_SENSITIVE)

val OWNER_DATASET_GRANTS: Map<String, String> =
    DATASET_PARTS.associateWith { SharingRole.OWNER.name.lowercase() }

fun datasetPartLabel(part: String): String = when (part) {
    PART_PLAN -> "Plan & settings"
    PART_CYCLE -> "Cycle & periods"
    PART_INTIMACY -> "Intimacy log"
    PART_SENSITIVE -> "Sensitive events"
    else -> part
}

fun datasetPartSummary(part: String): String = when (part) {
    PART_PLAN -> "Planner options, plan outputs, and profile photo"
    PART_CYCLE -> "Period dates, cycle stats, body signals"
    PART_INTIMACY -> "Logged acts, incidents, abstinence credits"
    PART_SENSITIVE -> "Emergency contraception events"
    else -> part
}

fun datasetIdForPart(baseDatasetId: String, part: String): String =
    if (part == PART_PLAN) baseDatasetId else "$baseDatasetId.$part"

fun controlDatasetIdFor(baseDatasetId: String): String = "$baseDatasetId$CONTROL_DATASET_SUFFIX"

fun partForDatasetId(baseDatasetId: String, datasetId: String): String? {
    if (datasetId == baseDatasetId) return PART_PLAN
    return DATASET_PARTS.firstOrNull { part ->
        part != PART_PLAN && datasetId == datasetIdForPart(baseDatasetId, part)
    }
}

/** "primary.cycle" -> "primary"; plain ids map to themselves. */
fun baseDatasetIdOf(datasetId: String): String {
    for (part in DATASET_PARTS) {
        if (part == PART_PLAN) continue
        val suffix = ".$part"
        if (datasetId.endsWith(suffix)) return datasetId.removeSuffix(suffix)
    }
    return datasetId
}

/*
 * Base dataset ids are generational: "primary" → "primary.g2" →
 * "primary.g3" (KEEP IN SYNC with web datasets.ts). A hard-cutover
 * migration cannot reuse the source's id — sync-kit refuses duplicate
 * dataset ids in one folder and the source must stay readable until the
 * migration closes — so each cutover targets the next generation. The
 * ".g" marker lives in the dot namespace; display-name slugs are
 * [a-z0-9-], so "emma-2" is always a second profile named Emma, never
 * generation 2 of "emma".
 */
private val GENERATION_SUFFIX = Regex("""\.g\d+$""")
private val GENERATION_ID = Regex("""^(.*)\.g(\d+)$""")

fun splitBaseRoot(baseDatasetId: String): String =
    baseDatasetIdOf(baseDatasetId).replace(GENERATION_SUFFIX, "")

private fun splitBaseGeneration(root: String, baseDatasetId: String): Int? {
    if (baseDatasetId == root) return 1
    val match = GENERATION_ID.matchEntire(baseDatasetId) ?: return null
    if (match.groupValues[1] != root) return null
    return match.groupValues[2].toInt()
}

/** The next unused generation for a cutover from `sourceBaseId`. */
fun nextSplitBaseId(sourceBaseId: String, existingDatasetIds: List<String>): String {
    val root = splitBaseRoot(sourceBaseId)
    var max = 1
    for (id in existingDatasetIds) {
        splitBaseGeneration(root, baseDatasetIdOf(id))?.let { max = maxOf(max, it) }
    }
    return "$root.g${max + 1}"
}

/**
 * The highest existing generation strictly newer than `currentBaseId`, or
 * null. Non-null means another device already created (or completed) a
 * cutover this device hasn't adopted yet — or that an interrupted cutover
 * on this device should resume into that generation.
 */
fun newerSplitBaseId(currentBaseId: String, existingDatasetIds: List<String>): String? {
    val root = splitBaseRoot(currentBaseId)
    val current = splitBaseGeneration(root, baseDatasetIdOf(currentBaseId)) ?: 1
    var best: Int? = null
    for (id in existingDatasetIds) {
        val generation = splitBaseGeneration(root, baseDatasetIdOf(id)) ?: continue
        if (generation > current) best = maxOf(best ?: 0, generation)
    }
    return best?.let { "$root.g$it" }
}

/**
 * Two dataset ids belong to the same profile when their bases share a
 * generation root. Registry scoping uses this so a migration's target
 * datasets are recorded inside the migrating profile record instead of
 * surfacing as foreign profiles.
 */
fun sameSplitFamily(a: String, b: String): Boolean = splitBaseRoot(a) == splitBaseRoot(b)

data class ParsedDatasetGrants(
    val baseDatasetId: String,
    /** part -> lowercase role name */
    val grants: Map<String, String>,
    val split: Boolean,
)

fun grantsFromRequestedGrants(requestedGrants: List<SharingDatasetGrantV1>): ParsedDatasetGrants {
    val baseDatasetId = baseDatasetIdOf(requestedGrants.firstOrNull()?.datasetId ?: PRIMARY_DATASET_ID)
    val grants = mutableMapOf<String, String>()
    var sawCompanion = false
    for (grant in requestedGrants) {
        val part = partForDatasetId(baseDatasetId, grant.datasetId) ?: continue
        if (grant.datasetId != baseDatasetId) sawCompanion = true
        grants[part] = grant.role.name.lowercase()
    }
    return ParsedDatasetGrants(
        baseDatasetId = baseDatasetId,
        grants = grants,
        split = sawCompanion || requestedGrants.size > 1,
    )
}

fun requestedGrantsFromDatasetGrants(
    baseDatasetId: String,
    grants: Map<String, String>,
): List<SharingDatasetGrantV1> =
    DATASET_PARTS.mapNotNull { part ->
        val role = grants[part] ?: return@mapNotNull null
        val sharingRole = sharingRoleFromString(role)
        if (sharingRole == SharingRole.OWNER) return@mapNotNull null
        SharingDatasetGrantV1(datasetIdForPart(baseDatasetId, part), sharingRole)
    }

private val ROLE_RANK = mapOf("owner" to 3, "admin" to 2, "writer" to 1, "viewer" to 0)

fun highestGrantedRole(grants: Map<String, String>): String =
    grants.values.maxByOrNull { ROLE_RANK[it.lowercase()] ?: 0 }?.lowercase() ?: "viewer"

/** Invite presets — same ids/grants as web SHARING_PRESETS. */
data class SharingPreset(
    val id: String,
    val label: String,
    val description: String,
    val grants: Map<String, String>,
)

val SHARING_PRESETS: List<SharingPreset> = listOf(
    SharingPreset(
        "cycle-only",
        "Cycle only",
        "They see period dates, cycle stats, and body signals — read-only.",
        mapOf(PART_CYCLE to "viewer"),
    ),
    SharingPreset(
        "cycle-partner",
        "Cycle partner",
        "They can log periods and body signals, and see the plan.",
        mapOf(PART_CYCLE to "writer", PART_PLAN to "viewer"),
    ),
    SharingPreset(
        "full-partner",
        "Full partner",
        "They can edit everything except sensitive events.",
        mapOf(PART_PLAN to "writer", PART_CYCLE to "writer", PART_INTIMACY to "writer"),
    ),
    SharingPreset(
        "everything",
        "Everything",
        "Full edit access, including sensitive events.",
        mapOf(
            PART_PLAN to "writer",
            PART_CYCLE to "writer",
            PART_INTIMACY to "writer",
            PART_SENSITIVE to "writer",
        ),
    ),
)

/* ---------- Split-profile helpers ---------- */

fun isSplitProfile(profile: ProfileRecord): Boolean = profile.datasetGrants != null

/** Parts this device can read. Legacy profiles grant everything at profile.role. */
fun grantedParts(profile: ProfileRecord): List<String> {
    val grants = profile.datasetGrants ?: return DATASET_PARTS
    return DATASET_PARTS.filter { grants.containsKey(it) }
}

/** Parts this device cannot read — the UI shows these as restricted. */
fun restrictedParts(profile: ProfileRecord): List<String> {
    val grants = profile.datasetGrants ?: return emptyList()
    return DATASET_PARTS.filter { !grants.containsKey(it) }
}

fun partRole(profile: ProfileRecord, part: String): String? {
    val grants = profile.datasetGrants ?: return profile.role
    return grants[part]
}

fun partIsWritable(profile: ProfileRecord, part: String): Boolean {
    val role = partRole(profile, part) ?: return false
    return canPublishRole(role)
}

/** Every dataset file id this device knows for the profile (base first). */
fun profileDatasetIds(profile: ProfileRecord): List<String> {
    if (!isSplitProfile(profile)) return listOf(profile.datasetId)
    return grantedParts(profile).map { datasetIdForPart(profile.datasetId, it) }
}

fun profileDatasetIdsIncludingControl(profile: ProfileRecord): List<String> =
    profileDatasetIds(profile) + listOfNotNull(
        profile.controlDatasetId?.takeIf { profile.datasetRecords?.get(it)?.fileId != null },
    )

fun requestedGrantsWithControl(
    profile: ProfileRecord,
    dataGrants: List<SharingDatasetGrantV1>,
): List<SharingDatasetGrantV1> {
    val controlDatasetId = profile.controlDatasetId
    val controlReady = controlDatasetId != null &&
        profile.datasetRecords?.get(controlDatasetId)?.fileId != null
    return dataGrants + if (controlReady) {
        listOf(SharingDatasetGrantV1(requireNotNull(controlDatasetId), SharingRole.WRITER))
    } else {
        emptyList()
    }
}

/* ---------- Payload projection & combination ---------- */

private fun isSensitiveEvent(kind: String): Boolean = kind == "plan_b_taken"

private fun projectDayLog(log: SyncDayLog, part: String): SyncDayLog? {
    val projected = when (part) {
        PART_CYCLE -> SyncDayLog(
            mucus = log.mucus,
            bbtCelsius = log.bbtCelsius,
            opk = log.opk,
            mittelschmerz = log.mittelschmerz,
            breastTender = log.breastTender,
            updatedAt = log.updatedAt,
        )
        PART_INTIMACY -> SyncDayLog(
            actualAction = log.actualAction,
            notes = log.notes,
            reconciled = log.reconciled,
            events = log.events.filter { !isSensitiveEvent(it.kind) },
            updatedAt = log.updatedAt,
        )
        PART_SENSITIVE -> SyncDayLog(
            events = log.events.filter { isSensitiveEvent(it.kind) },
            updatedAt = log.updatedAt,
        )
        else -> return null
    }
    val deletedAt = log.deletedDatasetParts[part]
    val projectedAt = projected.updatedAt
    return when {
        projected.hasDataForDatasetPart(part) &&
            (deletedAt == null || SyncPayloadStore.timestamp(projectedAt) > SyncPayloadStore.timestamp(deletedAt)) ->
            projected
        deletedAt != null -> SyncDayLog(
            updatedAt = listOfNotNull(projectedAt, deletedAt)
                .maxByOrNull { SyncPayloadStore.timestamp(it) },
        )
        !log.hasUserData() && log.deletedDatasetParts.isEmpty() && projectedAt != null ->
            SyncDayLog(updatedAt = projectedAt)
        else -> null
    }
}

private fun mergeDayLogSections(a: SyncDayLog?, b: SyncDayLog, part: String): SyncDayLog {
    val deletedParts = a?.deletedDatasetParts.orEmpty().toMutableMap().apply {
        putAll(b.deletedDatasetParts)
        if (b.hasDataForDatasetPart(part)) {
            remove(part)
        } else if (b.updatedAt != null) {
            this[part] = b.updatedAt
        }
    }
    if (a == null) return b.copy(deletedDatasetParts = deletedParts)
    val events = (a.events + b.events).distinctBy { it.id }
    return SyncDayLog(
        actualAction = b.actualAction ?: a.actualAction,
        notes = b.notes ?: a.notes,
        mucus = b.mucus ?: a.mucus,
        bbtCelsius = b.bbtCelsius ?: a.bbtCelsius,
        opk = b.opk ?: a.opk,
        mittelschmerz = b.mittelschmerz ?: a.mittelschmerz,
        breastTender = b.breastTender ?: a.breastTender,
        reconciled = b.reconciled ?: a.reconciled,
        events = events,
        deletedDatasetParts = deletedParts,
        updatedAt = listOfNotNull(a.updatedAt, b.updatedAt).maxOrNull(),
    )
}

/**
 * Extract the slice of a full payload that belongs to one dataset part. The
 * result is a full-shape payload with every other section left empty, so the
 * existing per-file codec, merge, and fingerprint logic applies unchanged.
 * Never includes androidPreferences (device-scoped, never shared).
 */
fun projectDatasetPart(payload: SyncPayloadV1, part: String): SyncPayloadV1 {
    val empty = emptySharedPayload().copy(exportedAt = payload.exportedAt)
    val logs = payload.calendarDayLogs.mapNotNull { (date, log) ->
        projectDayLog(log, part)?.let { date to it }
    }.toMap()
    return when (part) {
        PART_PLAN -> empty.copy(
            planner = payload.planner,
            profileMeta = payload.profileMeta,
        )
        PART_CYCLE -> empty.copy(
            periodRecords = payload.periodRecords,
            deletedPeriodStarts = payload.deletedPeriodStarts,
            calendarDayLogs = logs,
        )
        PART_INTIMACY -> empty.copy(
            voluntaryAbstinenceDates = payload.voluntaryAbstinenceDates,
            voluntaryAbstinenceUpdatedAt = payload.voluntaryAbstinenceUpdatedAt,
            deletedVoluntaryAbstinenceDates = payload.deletedVoluntaryAbstinenceDates,
            calendarDayLogs = logs,
        )
        PART_SENSITIVE -> empty.copy(
            ecJournal = payload.ecJournal,
            calendarDayLogs = logs,
        )
        else -> empty
    }
}

/**
 * Reassemble a full payload from the parts this device can decrypt. Missing
 * parts stay at their empty defaults — the UI surfaces that via
 * ProfileRecord.datasetGrants (see restrictedParts).
 */
fun combineDatasetParts(parts: Map<String, SyncPayloadV1>): SyncPayloadV1 {
    var out = emptySharedPayload()
    val logs = mutableMapOf<String, SyncDayLog>()
    var exportedAt: String? = null
    for (part in DATASET_PARTS) {
        val payload = parts[part] ?: continue
        exportedAt = listOfNotNull(exportedAt, payload.exportedAt).maxOrNull()
        out = when (part) {
            PART_PLAN -> out.copy(
                planner = payload.planner,
                profileMeta = payload.profileMeta,
            )
            PART_CYCLE -> out.copy(
                periodRecords = payload.periodRecords,
                deletedPeriodStarts = payload.deletedPeriodStarts,
            )
            PART_INTIMACY -> out.copy(
                voluntaryAbstinenceDates = payload.voluntaryAbstinenceDates,
                voluntaryAbstinenceUpdatedAt = payload.voluntaryAbstinenceUpdatedAt,
                deletedVoluntaryAbstinenceDates = payload.deletedVoluntaryAbstinenceDates,
            )
            PART_SENSITIVE -> out.copy(ecJournal = payload.ecJournal)
            else -> out
        }
        for ((date, log) in payload.calendarDayLogs) {
            logs[date] = mergeDayLogSections(logs[date], log, part)
        }
    }
    return out.copy(
        calendarDayLogs = logs,
        exportedAt = exportedAt ?: out.exportedAt,
    )
}
