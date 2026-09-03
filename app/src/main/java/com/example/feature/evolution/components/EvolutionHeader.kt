package com.example.feature.evolution.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvolutionHeader(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BackgroundDark)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.my_evolution_title),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.testTag("evolution_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Voltar",
                        tint = TextPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BackgroundDark
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = LimeTransparent,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.TrendingUp,
                    contentDescription = null,
                    tint = Lime400,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                // Evolução owns one question, and Hoje owns the other ("qual treino eu faço
                // agora?"). Stating it here is what keeps the two screens from overlapping.
                Text(
                    text = stringResource(R.string.my_evolution_question),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = stringResource(R.string.my_evolution_subtitle),
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
