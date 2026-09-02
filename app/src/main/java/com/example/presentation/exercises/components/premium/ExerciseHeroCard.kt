package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.domain.engine.MuscleVisualResolver
import com.example.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExerciseHeroCard(
    title: String,
    primaryMuscle: String?,
    equipment: String?,
    difficulty: String?,
    mediaUrl: String?,
    subtitle: String? = null,
    movementPattern: String? = null,
    modifier: Modifier = Modifier
) {
    val muscleGroup = MuscleVisualResolver.resolveGroup(primaryMuscle)

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 1. Nome do Exercício
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 30.sp
            )

            // Subtítulo (se houver, ex: nome em inglês ou padrão de movimento)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Mídia Grande (Hero) — Proporção consistente e ContentScale.Fit sem corte indevido
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 190.dp, max = 280.dp)
                    .aspectRatio(16f / 10f, matchHeightConstraintsFirst = false)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0D0D0E)),
                contentAlignment = Alignment.Center
            ) {
                if (!mediaUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = mediaUrl,
                        contentDescription = "Demonstração do exercício $title",
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                } else {
                    // Fallback elegante quando não houver GIF/imagem disponível
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(muscleGroup.color.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = muscleGroup.icon,
                                contentDescription = null,
                                tint = muscleGroup.color,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Sem demonstração visual disponível",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Use as instruções abaixo para executar com segurança",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Chips de Contexto
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!primaryMuscle.isNullOrEmpty()) {
                    HeroBadge(text = primaryMuscle)
                }
                if (!equipment.isNullOrEmpty()) {
                    val firstEquipment = equipment.split(",").firstOrNull()?.trim()
                    if (!firstEquipment.isNullOrEmpty()) {
                        HeroBadge(text = firstEquipment, icon = Icons.Default.FitnessCenter)
                    }
                }
                if (!difficulty.isNullOrEmpty()) {
                    HeroBadge(text = difficulty.replaceFirstChar { it.uppercase() })
                }
                if (!movementPattern.isNullOrEmpty()) {
                    HeroBadge(text = movementPattern.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
private fun HeroBadge(
    text: String,
    icon: ImageVector? = null
) {
    Surface(
        color = SurfaceHighlight,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderLight.copy(alpha = 0.5f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = text,
                color = Lime400,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

