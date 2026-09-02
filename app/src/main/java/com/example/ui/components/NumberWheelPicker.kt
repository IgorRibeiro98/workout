package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object WheelPickerDefaults {
    fun formatWeight(weight: Float): String {
        val rounded = (Math.round(weight * 10f) / 10f)
        return if (rounded % 1f == 0f) {
            "${rounded.toInt()}"
        } else {
            String.format(Locale("pt", "BR"), "%.1f", rounded)
        }
    }

    fun formatReps(reps: Int): String {
        return reps.toString()
    }

    fun indexToValue(index: Int, min: Float, step: Float): Float {
        val multiplier = 1f / step
        val raw = min + index * step
        return (Math.round(raw * multiplier) / multiplier)
    }

    fun valueToIndex(value: Float, min: Float, step: Float, totalItems: Int): Int {
        val multiplier = 1f / step
        val index = Math.round((value - min) * multiplier)
        return index.coerceIn(0, totalItems - 1)
    }

    /** Base row height at fontScale 1.0. */
    val BaseItemHeight: Dp = 44.dp

    /**
     * Row height that follows the system font scale.
     *
     * The selected value renders at 30.sp, so a fixed dp row clips the digits once
     * the user raises the font size. Scaling the row keeps the wheel legible instead.
     */
    fun itemHeightFor(fontScale: Float): Dp = BaseItemHeight * fontScale.coerceIn(1f, 2f)
}

/**
 * Reusable vertical wheel picker component with snapping and settled persistence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NumberWheelPicker(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    unit: String,
    formatter: (Float) -> String,
    onValueSettled: (Float) -> Unit,
    onDirectInputRequest: () -> Unit,
    hapticEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    visibleItemsCount: Int = 3,
    itemHeight: Dp = WheelPickerDefaults.itemHeightFor(LocalDensity.current.fontScale)
) {
    val haptic = LocalHapticFeedback.current
    val totalItems = remember(range, step) {
        ((range.endInclusive - range.start) / step).roundToInt() + 1
    }

    // Current target index based on incoming value prop
    val targetIndex = remember(value, range, step, totalItems) {
        WheelPickerDefaults.valueToIndex(value, range.start, step, totalItems)
    }

    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = targetIndex)
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = lazyListState)

    val currentCenterItemIndex by remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty()) {
                targetIndex
            } else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }?.index ?: targetIndex
            }
        }
    }

    // Direct scroll to initial / updated position when value changes externally
    LaunchedEffect(targetIndex) {
        if (!lazyListState.isScrollInProgress) {
            val layoutInfo = lazyListState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            val currentCenterIndex = layoutInfo.visibleItemsInfo.minByOrNull { item ->
                abs((item.offset + item.size / 2) - viewportCenter)
            }?.index

            if (currentCenterIndex != targetIndex) {
                lazyListState.scrollToItem(targetIndex)
            }
        }
    }

    // Haptic feedback tick on center item change during scroll
    var lastHapticIndex by remember { mutableIntStateOf(targetIndex) }
    LaunchedEffect(lazyListState, hapticEnabled) {
        snapshotFlow { currentCenterItemIndex }.collect { index ->
            if (index != lastHapticIndex) {
                if (lastHapticIndex != -1 && hapticEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                lastHapticIndex = index
            }
        }
    }

    // Settled persistence: trigger onValueSettled only when actual scroll stops
    var hasScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (isScrolling) {
                    hasScrolled = true
                } else if (hasScrolled) {
                    val settledIndex = currentCenterItemIndex
                    val settledVal = WheelPickerDefaults.indexToValue(settledIndex, range.start, step)
                    if (abs(settledVal - value) > (step / 4f)) {
                        onValueSettled(settledVal)
                    }
                }
            }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label.uppercase(),
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = "Arrastar para alterar",
                tint = TextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * visibleItemsCount)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = { /* Tap keeps wheel active */ },
                    onLongClick = onDirectInputRequest,
                    onDoubleClick = onDirectInputRequest
                )
                .semantics {
                    contentDescription = "$label, ${formatter(value)} $unit"
                    stateDescription = "${formatter(value)} $unit"
                    customActions = listOf(
                        CustomAccessibilityAction("Aumentar") {
                            val nextVal = (value + step).coerceAtMost(range.endInclusive)
                            onValueSettled(nextVal)
                            true
                        },
                        CustomAccessibilityAction("Diminuir") {
                            val prevVal = (value - step).coerceAtLeast(range.start)
                            onValueSettled(prevVal)
                            true
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Selection indicator overlay (center row highlight)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(itemHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Lime400.copy(alpha = 0.12f))
                    .border(1.dp, Lime400.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            )

            LazyColumn(
                state = lazyListState,
                flingBehavior = snapFlingBehavior,
                contentPadding = PaddingValues(vertical = itemHeight * ((visibleItemsCount - 1) / 2)),
                modifier = Modifier.fillMaxSize()
            ) {
                items(totalItems) { index ->
                    val itemVal = WheelPickerDefaults.indexToValue(index, range.start, step)
                    val isSelected = currentCenterItemIndex == index

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = formatter(itemVal),
                                color = if (isSelected) Lime400 else TextPrimary,
                                fontSize = if (isSelected) 30.sp else 20.sp,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                modifier = Modifier.alpha(if (isSelected) 1f else 0.4f)
                            )
                            if (unit.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = unit,
                                    color = if (isSelected) Lime400.copy(alpha = 0.9f) else TextSecondary,
                                    fontSize = if (isSelected) 14.sp else 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .padding(bottom = if (isSelected) 4.dp else 2.dp)
                                        .alpha(if (isSelected) 1f else 0.4f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeightWheelPicker(
    value: Float,
    step: Float = 0.5f,
    minWeight: Float = 0f,
    maxWeight: Float = 500f,
    hapticEnabled: Boolean = true,
    label: String = "Carga",
    onValueSettled: (Float) -> Unit,
    onDirectInputRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    NumberWheelPicker(
        value = value,
        range = minWeight..maxWeight,
        step = step,
        label = label,
        unit = "kg",
        formatter = { WheelPickerDefaults.formatWeight(it) },
        onValueSettled = onValueSettled,
        onDirectInputRequest = onDirectInputRequest,
        hapticEnabled = hapticEnabled,
        modifier = modifier
    )
}

@Composable
fun RepsWheelPicker(
    value: Int,
    step: Int = 1,
    minReps: Int = 1,
    maxReps: Int = 100,
    hapticEnabled: Boolean = true,
    onValueSettled: (Int) -> Unit,
    onDirectInputRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    NumberWheelPicker(
        value = value.toFloat(),
        range = minReps.toFloat()..maxReps.toFloat(),
        step = step.toFloat(),
        label = "Repetições",
        unit = "reps",
        formatter = { WheelPickerDefaults.formatReps(it.toInt()) },
        onValueSettled = { onValueSettled(it.toInt()) },
        onDirectInputRequest = onDirectInputRequest,
        hapticEnabled = hapticEnabled,
        modifier = modifier
    )
}

/**
 * Direct numeric input fallback sheet (ModalBottomSheet) for jumping to far values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectNumericInputSheet(
    title: String,
    initialValue: String,
    isDecimal: Boolean,
    unitLabel: String,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var textState by remember { mutableStateOf(initialValue) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = textState,
                onValueChange = { input ->
                    textState = input.replace(',', '.')
                    errorMessage = null
                },
                label = { Text("Digite o valor ($unitLabel)") },
                singleLine = true,
                isError = errorMessage != null,
                supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lime400,
                    unfocusedBorderColor = BorderLight,
                    focusedLabelColor = Lime400,
                    cursorColor = Lime400
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val parsed = textState.trim().toFloatOrNull()
                    if (parsed == null || parsed < 0f || (!isDecimal && parsed <= 0f)) {
                        errorMessage = "Insira um valor válido."
                    } else {
                        onConfirm(parsed)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = BackgroundDark)
            ) {
                Text("CONFIRMAR", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
