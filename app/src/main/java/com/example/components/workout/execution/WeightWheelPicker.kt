package com.example.components.workout.execution

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.components.NumberWheelPicker
import com.example.ui.components.WheelPickerDefaults

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
