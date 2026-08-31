package com.example.presentation.exercises.components.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.*

@Composable
fun ExerciseHeroCard(
    title: String,
    primaryMuscle: String?,
    equipment: String?,
    difficulty: String?,
    mediaUrl: String?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (!mediaUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = mediaUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(SurfaceHighlight),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Sem mídia", color = TextSecondary)
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!primaryMuscle.isNullOrEmpty()) {
                        HeroBadge(primaryMuscle)
                    }
                    if (!equipment.isNullOrEmpty()) {
                        val firstEquipment = equipment.split(",").firstOrNull()?.trim()
                        if (!firstEquipment.isNullOrEmpty()) {
                            HeroBadge(firstEquipment)
                        }
                    }
                    if (!difficulty.isNullOrEmpty()) {
                        HeroBadge(difficulty.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBadge(text: String) {
    Box(
        modifier = Modifier
            .background(SurfaceHighlight, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = Lime400,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
