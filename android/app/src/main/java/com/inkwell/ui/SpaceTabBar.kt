package com.inkwell.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkwell.BuildConfig
import com.inkwell.data.SpaceEntity
import com.inkwell.render.AnnotationRenderer

/**
 * Stage 14 (SPEC §9.3, ADR-0010): a horizontally scrollable bar of the server's spaces,
 * shown above the Library breadcrumb. One tab per space ordered by `position`, labelled by
 * `name`, with a selected indicator painted in the space `color` (parsed via
 * [AnnotationRenderer.accentFrom]). A trailing refresh control re-mirrors the server's
 * spaces; a subtle "spaces not synced" hint shows only when no server space is cached.
 *
 * Stage 15 (additive, gated by [settingsEnabled] = [BuildConfig.SPACE_SETTINGS]): when ON,
 * a long-press on a tab opens a menu with **Space settings** ([onEditSpace]) and a trailing
 * **"+"** tab creates a new space ([onAddSpace]). When OFF the bar is exactly the Stage-14
 * read-only bar (no long-press menu, no "+"), so existing call sites/tests are unaffected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpaceTabBar(
    spaces: List<SpaceEntity>,
    activeSpaceId: String?,
    notSynced: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onEditSpace: (String) -> Unit = {},
    onAddSpace: () -> Unit = {},
    settingsEnabled: Boolean = BuildConfig.SPACE_SETTINGS,
    // Stage 22: unread pushed-canvas count per space id → a badge on the tab (SPEC §9.3).
    unseenBySpace: Map<String, Int> = emptyMap(),
    // Stage 27: the tab's ⋯ menu → Brain (per space); gated by [brainEnabled].
    onOpenBrain: (String) -> Unit = {},
    brainEnabled: Boolean = BuildConfig.BRAIN,
) {
    // The long-press menu appears when it has at least one item to show (Space settings and/or
    // Brain). Stage 27: Brain can be reachable even when space-editing (Stage 15) is off.
    val menuEnabled = settingsEnabled || brainEnabled
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = modifier.fillMaxWidth().testTag(SpaceTabTags.BAR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                spaces.forEach { space ->
                    val selected = space.id == activeSpaceId
                    val accent = Color(AnnotationRenderer.accentFrom(space.color))
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Column(
                            modifier = Modifier
                                .then(
                                    if (menuEnabled) {
                                        Modifier.combinedClickable(
                                            onClick = { onSelect(space.id) },
                                            onLongClick = { menu = true },
                                        )
                                    } else {
                                        Modifier.clickable { onSelect(space.id) }
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag(SpaceTabTags.tab(space.id)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = space.name,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) accent else Color.Unspecified,
                                    maxLines = 1,
                                )
                                // Stage 22: unread pushed-canvas badge (count of `seen_at == null`).
                                val unseen = unseenBySpace[space.id] ?: 0
                                if (unseen > 0) {
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(16.dp)
                                            .background(accent, CircleShape)
                                            .testTag(SpaceTabTags.badge(space.id)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = if (unseen > 9) "9+" else unseen.toString(),
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .height(3.dp)
                                    .width(28.dp)
                                    .background(if (selected) accent else Color.Transparent),
                            )
                        }
                        if (menuEnabled) {
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (settingsEnabled) {
                                    DropdownMenuItem(
                                        text = { Text("Space settings") },
                                        onClick = { menu = false; onEditSpace(space.id) },
                                        modifier = Modifier.testTag(SpaceTabTags.settings(space.id)),
                                    )
                                }
                                // Stage 27: open the per-space Brain (gated by BuildConfig.BRAIN).
                                if (brainEnabled) {
                                    DropdownMenuItem(
                                        text = { Text("Brain") },
                                        onClick = { menu = false; onOpenBrain(space.id) },
                                        modifier = Modifier.testTag(SpaceTabTags.brain(space.id)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Stage 16: the "+" is a FIXED trailing action OUTSIDE the horizontally-scrolling
            // tab row, so it is always visible and hittable regardless of how many space tabs
            // there are. Previously it sat at the end of the weighted `horizontalScroll` row and
            // overflowed off-screen once the tabs filled the width (phone width), so a tap on it
            // never registered and the New space dialog never opened (bug #37 / stage 16).
            if (settingsEnabled) {
                Text(
                    text = "+",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onAddSpace() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(SpaceTabTags.ADD),
                )
            }
            if (notSynced) {
                Text(
                    text = "spaces not synced",
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .testTag(SpaceTabTags.NOT_SYNCED),
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(SpaceTabTags.REFRESH),
            ) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh spaces") }
        }
    }
}

/** Stable tags for Compose/instrumented tests. */
object SpaceTabTags {
    const val BAR = "space_tab_bar"
    const val REFRESH = "space_tab_refresh"
    const val NOT_SYNCED = "space_tab_not_synced"
    const val ADD = "space_tab_add"

    fun tab(id: String) = "space_tab_$id"

    /** Stage 15: the "Space settings" item in a tab's long-press menu. */
    fun settings(id: String) = "space_tab_settings_$id"

    /** Stage 27: the "Brain" item in a tab's long-press menu. */
    fun brain(id: String) = "space_tab_brain_$id"

    /** Stage 22: the unread pushed-canvas badge on a tab. */
    fun badge(id: String) = "space_tab_badge_$id"
}
