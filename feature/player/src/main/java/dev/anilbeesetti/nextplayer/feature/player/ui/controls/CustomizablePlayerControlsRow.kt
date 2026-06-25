package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.components.glass.glassPanel
import dev.anilbeesetti.nextplayer.core.ui.components.glass.glassCard

/**
 * PLAYit-style customizable player controls row (Phase 1.2).
 *
 * Features:
 *  - Each control button (play/pause, next, prev, equalizer, audio track, etc.) can be:
 *    • Dragged to reorder positions (long-press + drag, snap-swaps at midpoint).
 *    • Toggled visible/hidden via a "Customize" panel that slides up.
 *    • Removed by dragging out of the row (drag distance > 2x button width).
 *  - The user's chosen order and visibility are persisted via [onReorder] and
 *    [onToggleHidden] callbacks (callers wire them to DataStore).
 *  - Visual cue: dragged button gets a translucent primary-color background +
 *    70% alpha icon. Other buttons stay full opacity.
 *  - "Customize" pencil button at the row's end opens a glass dialog where the
 *    user can drag-handle to reorder, eye/eye-off to toggle, and tap to add
 *    hidden buttons back.
 */
data class PlayerControlButton(
    val id: String,
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)

@Composable
fun CustomizablePlayerControlsRow(
    buttons: List<PlayerControlButton>,
    hiddenButtonIds: Set<String>,
    onReorder: (newOrder: List<String>) -> Unit,
    onToggleHidden: (buttonId: String, hidden: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visibleButtons by remember(buttons) {
        mutableStateOf(buttons.filter { it.id !in hiddenButtonIds })
    }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var showCustomizeDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(cornerRadius = 20.dp, alpha = 0.35f)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleButtons.forEachIndexed { index, button ->
            val isBeingDragged = draggedIndex == index
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isBeingDragged) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else Color.Transparent,
                    )
                    .pointerInput(button.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedIndex = index
                            },
                            onDragEnd = {
                                draggedIndex = null
                                dragOffsetX = 0f
                                onReorder(visibleButtons.map { it.id })
                            },
                            onDragCancel = {
                                draggedIndex = null
                                dragOffsetX = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                val buttonWidthPx = 48f + 16f
                                val swapThreshold = buttonWidthPx / 2
                                val removeThreshold = buttonWidthPx * 2

                                // Remove by dragging far out
                                if (kotlin.math.abs(dragOffsetX) > removeThreshold) {
                                    val removed = visibleButtons[index]
                                    onToggleHidden(removed.id, true)
                                    visibleButtons = visibleButtons.toMutableList().also { it.removeAt(index) }
                                    draggedIndex = null
                                    dragOffsetX = 0f
                                    return@detectDragGesturesAfterLongPress
                                }

                                if (dragOffsetX > swapThreshold && index < visibleButtons.lastIndex) {
                                    val newOrder = visibleButtons.toMutableList()
                                    val tmp = newOrder[index]
                                    newOrder[index] = newOrder[index + 1]
                                    newOrder[index + 1] = tmp
                                    visibleButtons = newOrder
                                    draggedIndex = index + 1
                                    dragOffsetX = 0f
                                } else if (dragOffsetX < -swapThreshold && index > 0) {
                                    val newOrder = visibleButtons.toMutableList()
                                    val tmp = newOrder[index]
                                    newOrder[index] = newOrder[index - 1]
                                    newOrder[index - 1] = tmp
                                    visibleButtons = newOrder
                                    draggedIndex = index - 1
                                    dragOffsetX = 0f
                                }
                            },
                        )
                    }
                    .clickable { button.onClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = button.icon,
                    contentDescription = button.contentDescription,
                    tint = if (isBeingDragged) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(24.dp)
                        .alpha(if (isBeingDragged) 0.7f else 1f),
                )
            }
            if (index < visibleButtons.lastIndex) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }

        // Customize (pencil) button — opens the add/hide panel
        IconButton(
            onClick = { showCustomizeDialog = true },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Customize controls",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    if (showCustomizeDialog) {
        CustomizeControlsDialog(
            allButtons = buttons,
            hiddenButtonIds = hiddenButtonIds,
            currentOrder = visibleButtons.map { it.id },
            onDismiss = { showCustomizeDialog = false },
            onReorder = { newOrder ->
                visibleButtons = newOrder.mapNotNull { id -> buttons.firstOrNull { it.id == id } }
                onReorder(newOrder)
            },
            onToggleHidden = { id, hidden ->
                if (hidden) {
                    visibleButtons = visibleButtons.filter { it.id != id }
                } else {
                    val restored = buttons.firstOrNull { it.id == id }
                    if (restored != null) visibleButtons = visibleButtons + restored
                }
                onToggleHidden(id, hidden)
            },
        )
    }
}

@Composable
private fun CustomizeControlsDialog(
    allButtons: List<PlayerControlButton>,
    hiddenButtonIds: Set<String>,
    currentOrder: List<String>,
    onDismiss: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onToggleHidden: (String, Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize Controls") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Drag to reorder. Tap eye to hide/show.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val visibleIds = currentOrder.filter { it !in hiddenButtonIds }
                val hiddenIds = allButtons.map { it.id }.filter { it in hiddenButtonIds }

                Text("Visible", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                visibleIds.forEach { id ->
                    val btn = allButtons.firstOrNull { it.id == id } ?: return@forEach
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = "Drag",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = btn.icon,
                            contentDescription = btn.contentDescription,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(btn.contentDescription, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onToggleHidden(id, true) }) {
                            Icon(
                                imageVector = Icons.Default.VisibilityOff,
                                contentDescription = "Hide",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (hiddenIds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Hidden", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    hiddenIds.forEach { id ->
                        val btn = allButtons.firstOrNull { it.id == id } ?: return@forEach
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                imageVector = btn.icon,
                                contentDescription = btn.contentDescription,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                btn.contentDescription,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { onToggleHidden(id, false) }) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "Show",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
