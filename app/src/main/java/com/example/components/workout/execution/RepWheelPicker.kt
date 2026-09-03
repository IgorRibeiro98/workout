package com.example.components.workout.execution

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.components.NumberWheelPicker
import com.example.ui.components.WheelPickerDefaults

@Composable
fun RepWheelPicker(
    value: Int,
    step: Int = 1,
    minReps: Int = 1,
    maxReps: Int = 100,
    label: String = "Repetições",
    unit: String = "reps",
    hapticEnabled: Boolean = true,
    onValueSettled: (Int) -> Unit,
    onDirectInputRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    NumberWheelPicker(
        value = value.toFloat(),
        range = minReps.toFloat()..maxReps.toFloat(),
        step = step.toFloat(),
        label = label,
        unit = unit,
        formatter = { if (unit == "s" || unit == "seg") "${it.toInt()}s" else WheelPickerDefaults.formatReps(it.toInt()) },
        onValueSettled = { onValueSettled(it.toInt()) },
        onDirectInputRequest = onDirectInputRequest,
        hapticEnabled = hapticEnabled,
        modifier = modifier
    )
}
