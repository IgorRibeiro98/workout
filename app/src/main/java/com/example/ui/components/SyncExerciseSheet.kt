package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.SetLogEntity
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncExerciseSheet(
    exerciseName: String,
    activeSet: SetLogEntity?,
    allCurrentSets: List<SetLogEntity>,
    previousExecutionSets: List<SetLogEntity>,
    onDismissRequest: () -> Unit,
    onReplicateCurrentSet: () -> Unit,
    onRestoreLastWorkout: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(id = R.string.sync_exercise_title),
        subtitle = exerciseName,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Option 1: Replicar Série Atual
            val currentSet = activeSet ?: allCurrentSets.firstOrNull { !it.completed } ?: allCurrentSets.lastOrNull()
            val subsequentTargetSets = if (currentSet != null) {
                allCurrentSets.filter { set ->
                    set.setNumber > currentSet.setNumber && !set.completed && set.type == currentSet.type
                }
            } else emptyList()

            val canReplicate = currentSet != null && subsequentTargetSets.isNotEmpty()

            Surface(
                color = BackgroundDark,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (canReplicate) BorderLight else BorderLight.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.sync_replicate_current_title),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = stringResource(id = R.string.sync_replicate_current_desc),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    // Preview Box
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (currentSet != null) {
                                val weightStr = if (currentSet.weight % 1f == 0f) currentSet.weight.toInt().toString() else currentSet.weight.toString()
                                Text(
                                    text = "Série ${currentSet.setNumber} (${currentSet.type}): $weightStr kg × ${currentSet.repetitions} reps",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (subsequentTargetSets.isNotEmpty()) {
                                    val setNumbersStr = subsequentTargetSets.joinToString { "Série ${it.setNumber}" }
                                    Text(
                                        text = "Aplicar em: $setNumbersStr",
                                        color = Lime400,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    Text(
                                        text = "Nenhuma série pendente posterior do mesmo tipo.",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                Text(
                                    text = "Nenhuma série ativa.",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onReplicateCurrentSet,
                        enabled = canReplicate,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime400,
                            contentColor = BackgroundDark,
                            disabledContainerColor = SurfaceDark,
                            disabledContentColor = TextSecondary.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = stringResource(id = R.string.sync_button_apply),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Option 2: Restaurar Último Treino
            val pendingCurrentSets = allCurrentSets.filter { !it.completed }
            val hasPreviousWorkout = previousExecutionSets.isNotEmpty()
            val canRestore = hasPreviousWorkout && pendingCurrentSets.isNotEmpty()

            Surface(
                color = BackgroundDark,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (canRestore) BorderLight else BorderLight.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.sync_restore_last_title),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = stringResource(id = R.string.sync_restore_last_desc),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    // Preview Box
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (hasPreviousWorkout) {
                                val summaryText = previousExecutionSets.joinToString(" · ") { setLog ->
                                    val weightStr = if (setLog.weight % 1f == 0f) setLog.weight.toInt().toString() else setLog.weight.toString()
                                    "${weightStr}kg×${setLog.repetitions}"
                                }
                                Text(
                                    text = "Última execução: $summaryText",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (pendingCurrentSets.isNotEmpty()) {
                                    Text(
                                        text = "Restaurar em ${pendingCurrentSets.size} série(s) pendente(s)",
                                        color = Lime400,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    Text(
                                        text = "Todas as séries do treino atual já estão concluídas.",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                Text(
                                    text = stringResource(id = R.string.sync_no_previous_workout),
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onRestoreLastWorkout,
                        enabled = canRestore,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime400,
                            contentColor = BackgroundDark,
                            disabledContainerColor = SurfaceDark,
                            disabledContentColor = TextSecondary.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            text = stringResource(id = R.string.sync_button_restore),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
