package com.example.feature.evolution.performance.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.evolution.model.performance.ExercisePerformanceEvolution
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun ExerciseSelector(
    exercises: List<ExercisePerformanceEvolution>,
    selectedExerciseId: String?,
    onExerciseSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "exercise_selector"
) {
    var expanded by remember { mutableStateOf(false) }

    val currentExercise = exercises.find { it.exerciseId == selectedExerciseId }
    val displayName = when {
        exercises.isEmpty() -> "Nenhum exercício com histórico disponível"
        currentExercise != null -> currentExercise.exerciseName
        else -> "Selecione um exercício"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Text(
            text = "Exercício:",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E2124))
                    .border(1.dp, if (expanded) Lime400 else BorderLight, RoundedCornerShape(12.dp))
                    .clickable(enabled = exercises.isNotEmpty()) { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("exercise_selector_button"),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = if (selectedExerciseId != null && exercises.isNotEmpty()) Lime400 else TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = displayName,
                            color = if (selectedExerciseId != null && exercises.isNotEmpty()) TextPrimary else TextTertiary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Expandir exercícios",
                        tint = if (exercises.isNotEmpty()) TextSecondary else TextTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (exercises.isNotEmpty()) {
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .background(SurfaceDark)
                        .border(1.dp, BorderLight, RoundedCornerShape(8.dp))
                        .testTag("exercise_selector_dropdown")
                ) {
                    exercises.forEach { item ->
                        val isSelected = item.exerciseId == selectedExerciseId
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = item.exerciseName,
                                        color = if (isSelected) Lime400 else TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (item.bestWeight != null) {
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = "${item.bestWeight}kg máx",
                                            color = TextTertiary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onExerciseSelected(item.exerciseId)
                                expanded = false
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = TextPrimary
                            ),
                            modifier = Modifier.testTag("exercise_option_${item.exerciseId}")
                        )
                    }
                }
            }
        }
    }
}
