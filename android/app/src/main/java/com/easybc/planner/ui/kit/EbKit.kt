package com.easybc.planner.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easybc.planner.ui.theme.LocalEbDarkTheme

/*
 * EasyBC UI kit — presentational composables matching the profiles/settings
 * redesign (docs/settings-profiles-redesign.md, docs/ui-kit.md). No sync or
 * repository logic here: screens compose these and wire their own handlers.
 * The web kit (web/src/ui/Kit.tsx) mirrors the same components and colors.
 */

/* ---------- Kit palette (semantic, never the accent) ---------- */

object EbColors {
    val modeLocal: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFFA9A99F) else Color(0xFF6E6E66)
    val modeLocalContainer: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF34342E) else Color(0xFFECECE4)
    val modePrivate: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF6FBFAF) else Color(0xFF256E62)
    val modePrivateContainer: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF1E3A34) else Color(0xFFDDEFEB)
    val modeShared: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFFA79BD1) else Color(0xFF5B4B8A)
    val modeSharedContainer: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF322A48) else Color(0xFFECE7F6)

    val dsCycle: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFFE792B9) else Color(0xFF7B2D5F)
    val dsPlan: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF94B2DC) else Color(0xFF44618C)
    val dsIntimacy: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFFDBA96A) else Color(0xFFA8702D)
    val dsSensitive: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFFABA3B3) else Color(0xFF5B5561)

    val ok: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF7CC79B) else Color(0xFF2E7D4F)
    val okContainer: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF1E3A2B) else Color(0xFFE0F2E7)
    val warn: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFFE3B34E) else Color(0xFF7A5410)
    val warnContainer: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF3D321A) else Color(0xFFFDF3E3)
    val info: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF94B2DC) else Color(0xFF44618C)
    val infoContainer: Color @Composable get() =
        if (LocalEbDarkTheme.current) Color(0xFF263349) else Color(0xFFE7EDF7)
}

/* ---------- Avatar & profile identity ---------- */

enum class EbProfileBadge { LOCAL, PRIVATE, SHARED, READ_ONLY, WAITING }

/**
 * Deterministic avatar hue (FNV-1a over UTF-16 code units → hue).
 * Mirrors web/src/ui/avatarColor.ts — keep both in sync so a profile
 * renders the same color on every platform.
 */
fun avatarHue(key: String): Int {
    var hash = 0x811C9DC5.toInt()
    for (c in key) {
        hash = hash xor c.code
        hash *= 0x01000193
    }
    return (hash.toUInt() % 360u).toInt()
}

fun avatarColor(key: String): Color = Color.hsl(avatarHue(key).toFloat(), 0.48f, 0.44f)

fun avatarInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val first = words.first().first()
    val second = if (words.size > 1) words.last().first() else null
    return listOfNotNull(first, second).joinToString("").uppercase()
}

@Composable
private fun badgeVisuals(badge: EbProfileBadge): Pair<ImageVector, Color> = when (badge) {
    EbProfileBadge.LOCAL -> Icons.Filled.PhoneAndroid to EbColors.modeLocal
    EbProfileBadge.PRIVATE -> Icons.Filled.Cloud to EbColors.modePrivate
    EbProfileBadge.SHARED -> Icons.Filled.Group to EbColors.modeShared
    EbProfileBadge.READ_ONLY -> Icons.Filled.Visibility to EbColors.info
    EbProfileBadge.WAITING -> Icons.Filled.HourglassEmpty to EbColors.warn
}

@Composable
fun EbAvatar(
    name: String,
    modifier: Modifier = Modifier,
    colorKey: String = name,
    size: Dp = 36.dp,
    badge: EbProfileBadge? = null,
) {
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(avatarColor(colorKey)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                avatarInitials(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
        badge?.let {
            val (icon, tint) = badgeVisuals(it)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.42f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.26f))
            }
        }
    }
}

/** Profile chip for top app bars: avatar + name + mode badge. */
@Composable
fun EbProfileChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colorKey: String = name,
    badge: EbProfileBadge? = null,
    showName: Boolean = true,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 5.dp, end = if (showName) 12.dp else 5.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EbAvatar(name, colorKey = colorKey, size = 28.dp, badge = badge)
            if (showName) {
                Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/* ---------- Layout primitives ---------- */

@Composable
fun EbGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
    )
}

enum class EbRowTone { DEFAULT, SHARED, DANGER }

/** Settings hub row: icon, title, current-value summary, chevron. */
@Composable
fun EbNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    tone: EbRowTone = EbRowTone.DEFAULT,
) {
    val borderColor = when (tone) {
        EbRowTone.SHARED -> EbColors.modeShared
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val titleColor = when (tone) {
        EbRowTone.DANGER -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, borderColor),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon?.let {
                val iconBg = if (tone == EbRowTone.SHARED) EbColors.modeSharedContainer
                else MaterialTheme.colorScheme.primaryContainer
                val iconTint = if (tone == EbRowTone.SHARED) EbColors.modeShared
                else MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(it, null, tint = iconTint, modifier = Modifier.size(17.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
                value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Active-profile header card for the Settings root and Profile detail. */
@Composable
fun EbProfileHeaderCard(
    name: String,
    meta: String,
    modifier: Modifier = Modifier,
    colorKey: String = name,
    badge: EbProfileBadge? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EbAvatar(name, colorKey = colorKey, size = 52.dp, badge = badge)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/* ---------- Storage mode ---------- */

enum class EbStorageMode { LOCAL, PRIVATE, SHARED }

/**
 * One card of the three-way storage selector (radio semantics). The caller
 * runs the storage transition and must only flip `selected` after the
 * operation confirms; `pending` shows the in-flight state.
 */
@Composable
fun EbModeCard(
    mode: EbStorageMode,
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    pending: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = when (mode) {
        EbStorageMode.LOCAL -> EbColors.modeLocal
        EbStorageMode.PRIVATE -> EbColors.modePrivate
        EbStorageMode.SHARED -> EbColors.modeShared
    }
    val container = when (mode) {
        EbStorageMode.LOCAL -> EbColors.modeLocalContainer
        EbStorageMode.PRIVATE -> EbColors.modePrivateContainer
        EbStorageMode.SHARED -> EbColors.modeSharedContainer
    }
    val icon = when (mode) {
        EbStorageMode.LOCAL -> Icons.Filled.PhoneAndroid
        EbStorageMode.PRIVATE -> Icons.Filled.Cloud
        EbStorageMode.SHARED -> Icons.Filled.Group
    }
    Surface(
        onClick = onSelect,
        enabled = enabled && !pending,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, if (selected) tint else MaterialTheme.colorScheme.outlineVariant),
        color = if (selected) container else MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled && !pending,
                colors = RadioButtonDefaults.colors(selectedColor = tint),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint),
                contentAlignment = Alignment.Center,
            ) {
                if (pending) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ---------- Datasets & access ---------- */

enum class EbDataset { CYCLE, PLAN, INTIMACY, SENSITIVE }

@Composable
fun ebDatasetColor(dataset: EbDataset): Color = when (dataset) {
    EbDataset.CYCLE -> EbColors.dsCycle
    EbDataset.PLAN -> EbColors.dsPlan
    EbDataset.INTIMACY -> EbColors.dsIntimacy
    EbDataset.SENSITIVE -> EbColors.dsSensitive
}

fun ebDatasetIcon(dataset: EbDataset): ImageVector = when (dataset) {
    EbDataset.CYCLE -> Icons.Filled.Opacity
    EbDataset.PLAN -> Icons.Filled.Explore
    EbDataset.INTIMACY -> Icons.Filled.Favorite
    EbDataset.SENSITIVE -> Icons.Filled.Security
}

@Composable
fun EbDatasetRow(
    dataset: EbDataset,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val dark = LocalEbDarkTheme.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(ebDatasetColor(dataset)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ebDatasetIcon(dataset),
                    null,
                    // Dark-mode dataset hues are light; keep glyphs legible.
                    tint = if (dark) Color(0xB8000000) else Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing?.invoke(this)
        }
    }
}

enum class EbAccessLevel { NONE, VIEW, EDIT }

/** None / View / Edit segmented control for the per-dataset access grid. */
@Composable
fun EbAccessSegmented(
    value: EbAccessLevel,
    onChange: (EbAccessLevel) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row {
            EbAccessLevel.entries.forEach { level ->
                val on = level == value
                Surface(
                    onClick = { onChange(level) },
                    enabled = enabled,
                    shape = RoundedCornerShape(7.dp),
                    color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                ) {
                    Text(
                        when (level) {
                            EbAccessLevel.NONE -> "None"
                            EbAccessLevel.VIEW -> "View"
                            EbAccessLevel.EDIT -> "Edit"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/* ---------- People & trust ---------- */

/**
 * Trust levels follow sync-kit rc.11: VERIFIED = key bound to the Google
 * account (ID-token/passkey binding recorded in the control dataset);
 * INVITE = key only asserted by the invite-link exchange.
 */
enum class EbTrust { VERIFIED, INVITE }

@Composable
fun EbTrustBadge(trust: EbTrust, modifier: Modifier = Modifier) {
    val (icon, tint, label) = when (trust) {
        EbTrust.VERIFIED -> Triple(Icons.Filled.CheckCircle, EbColors.ok, "Account-verified")
        EbTrust.INVITE -> Triple(Icons.Filled.Link, EbColors.warn, "Key from invite link")
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

@Composable
fun EbPersonCard(
    name: String,
    modifier: Modifier = Modifier,
    email: String? = null,
    trust: EbTrust? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                EbAvatar(name, colorKey = email ?: name, size = 36.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    email?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                trust?.let { EbTrustBadge(it) }
            }
            content?.invoke(this)
        }
    }
}

/* ---------- Feedback ---------- */

enum class EbBannerTone { INFO, WARN, ERROR, SUCCESS }

@Composable
fun EbBanner(
    tone: EbBannerTone,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (fg, bg) = when (tone) {
        EbBannerTone.INFO -> EbColors.info to EbColors.infoContainer
        EbBannerTone.WARN -> EbColors.warn to EbColors.warnContainer
        EbBannerTone.ERROR -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
        EbBannerTone.SUCCESS -> EbColors.ok to EbColors.okContainer
    }
    Surface(shape = RoundedCornerShape(14.dp), color = bg, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                title?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg)
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = fg)
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    border = BorderStroke(1.5.dp, fg),
                ) {
                    Text(actionLabel, color = fg, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

enum class EbStatusTone { OK, BUSY, WARN, ERROR }

@Composable
fun EbStatusRow(tone: EbStatusTone, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (tone) {
            EbStatusTone.BUSY -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(13.dp),
            )
            else -> Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (tone) {
                            EbStatusTone.OK -> EbColors.ok
                            EbStatusTone.WARN -> EbColors.warn
                            else -> MaterialTheme.colorScheme.error
                        },
                    ),
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------- Controls ---------- */

/** Invite sharing-preset chip (Cycle only / Cycle partner / …). */
@Composable
fun EbPresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        modifier = modifier,
    )
}

enum class EbExpanderTone { DEFAULT, DANGER }

@Composable
fun EbExpanderRow(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tone: EbExpanderTone = EbExpanderTone.DEFAULT,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val fg = when (tone) {
        EbExpanderTone.DANGER -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = onToggle,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                1.dp,
                if (tone == EbExpanderTone.DANGER) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.outlineVariant,
            ),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    if (expanded) "Collapse" else "Expand",
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (expanded && content != null) {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

/** Onboarding progress dots. */
@Composable
fun EbStepDots(count: Int, active: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(width = if (index == active) 20.dp else 7.dp, height = 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Composable
fun EbDangerTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label, color = MaterialTheme.colorScheme.error)
    }
}
