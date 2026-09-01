package com.example.presentation.execution.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.workout.execution.WorkoutExerciseExecution
import com.example.ui.components.AppModalBottomSheet
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseReorderBottomSheet(
    exerciseToMove: WorkoutExerciseExecution,
    allExercises: List<WorkoutExerciseExecution>,
    onDismiss: () -> Unit,
    onSelectPosition: (newPosition: Int) -> Unit
) {
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        title = "Mover ${exerciseToMove.name}",
        subtitle = "Escolha a nova posição no treino"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "Selecione onde este exercício deve ser executado:",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                itemsIndexed(allExercises) { index, ex ->
                    val targetPosition = index + 1
                    val isCurrentPos = ex.exerciseId == exerciseToMove.exerciseId

                    Surface(
                        color = if (isCurrentPos) Lime400.copy(alpha = 0.12f) else BackgroundDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isCurrentPos) Lime400 else BorderLight
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectPosition(targetPosition)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isCurrentPos) Lime400 else SurfaceDark,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "$targetPosition",
                                        color = if (isCurrentPos) BackgroundDark else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = ex.name,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = if (isCurrentPos) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )

                            if (isCurrentPos) {
                                Text(
                                    text = "Atual",
                                    color = Lime400,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "Mover para posição $targetPosition",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
