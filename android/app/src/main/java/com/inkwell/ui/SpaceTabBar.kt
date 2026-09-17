package com.inkwell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inkwell.data.SpaceEntity
import com.inkwell.render.AnnotationRenderer

/**
 * Stage 14 (SPEC §9.3, ADR-0010): a horizontally scrollable bar of the server's spaces,
 * shown above the Library breadcrumb. One tab per space ordered by `position`, labelled by
 * `name`, with a selected indicator painted in the space `color` (parsed via
 * [AnnotationRenderer.accentFrom]). A trailing refresh control re-mirrors the server's
 * spaces; a subtle "spaces not synced" hint shows only when no server space is cached.
 */
@Composable
fun SpaceTabBar(
    spaces: List<SpaceEntity>,
    activeSpaceId: String?,
    notSynced: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    Column(
                        modifier = Modifier
                            .clickable { onSelect(space.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag(SpaceTabTags.tab(space.id)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = space.name,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) accent else Color.Unspecified,
                            maxLines = 1,
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .height(3.dp)
                                .width(28.dp)
                                .background(if (selected) accent else Color.Transparent),
                        )
                    }
                }
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

    fun tab(id: String) = "space_tab_$id"
}
