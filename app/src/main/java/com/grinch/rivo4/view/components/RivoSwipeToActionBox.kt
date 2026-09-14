package com.grinch.rivo4.view.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.grinch.rivo4.modal.data.SwipeActionType
import kotlin.math.abs
import kotlin.math.roundToInt

/** Restores the component referenced but absent in the selected upstream commit. */
@Composable
fun RivoSwipeToActionBox(
    enabled: Boolean,
    swipeRightAction: SwipeActionType,
    swipeLeftAction: SwipeActionType,
    onTriggerAction: (SwipeActionType) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var offset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val trigger by rememberUpdatedState(onTriggerAction)
    val selected = if (offset > 0) swipeRightAction else swipeLeftAction
    Box(modifier) {
        if (offset != 0f && selected != SwipeActionType.NONE) Text(stringResource(selected.titleRes),
            modifier = Modifier.align(if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd))
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.pointerInput(enabled, swipeRightAction, swipeLeftAction) {
            if (enabled) detectHorizontalDragGestures(
                onHorizontalDrag = { change, delta -> change.consume(); offset = (offset + delta).coerceIn(-threshold * 1.5f, threshold * 1.5f) },
                onDragCancel = { offset = 0f },
                onDragEnd = {
                    val action = if (offset > 0) swipeRightAction else swipeLeftAction
                    val fire = abs(offset) >= threshold && action != SwipeActionType.NONE
                    offset = 0f
                    if (fire) trigger(action)
                }
            )
        }) { content() }
    }
}
