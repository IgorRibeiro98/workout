package com.example.presentation.body

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.body.MeasurementField
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBodyMeasurementScreen(
    viewModel: BodyEvolutionViewModel,
    onNavigateBack: () -> Unit
) {
    val formState by viewModel.formState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.resetForm()
    }

    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val formattedDate = dateFormatter.format(Date(formState.selectedDateMillis))

    val calendar = Calendar.getInstance().apply {
        timeInMillis = formState.selectedDateMillis
    }

    val datePickerDialog = remember {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                viewModel.updateDate(selectedCal.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Nova Medição",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        },
        bottomBar = {
            Surface(
                color = BackgroundDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            val saved = viewModel.saveMeasurement(onSuccess = onNavigateBack)
                        },
                        enabled = !formState.isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime400,
                            contentColor = BackgroundDark,
                            disabledContainerColor = Lime400.copy(alpha = 0.5f),
                            disabledContentColor = BackgroundDark
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("save_measurement_button")
                    ) {
                        if (formState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = BackgroundDark,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SALVAR MEDIÇÃO",
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // General error banner if present
            formState.errors["general"]?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("general_error_banner")
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Section: Data (Obrigatório)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Data da medição",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    color = SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("date_picker_button")
                        .clickable { datePickerDialog.show() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formattedDate,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = "Selecionar data",
                            tint = Lime400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Section: Peso e Composição
            FormSection(title = "Composição Geral") {
                MeasurementField(
                    label = "Peso corporal",
                    value = formState.weightKg,
                    onValueChange = { viewModel.updateWeight(it) },
                    unit = "kg",
                    placeholder = "Ex: 88.4",
                    errorMessage = formState.errors["weight"],
                    testTag = "weight_input"
                )
                MeasurementField(
                    label = "Gordura corporal",
                    value = formState.bodyFatPercentage,
                    onValueChange = { viewModel.updateBodyFat(it) },
                    unit = "%",
                    placeholder = "Ex: 15.2",
                    errorMessage = formState.errors["bodyFat"],
                    testTag = "body_fat_input"
                )
                MeasurementField(
                    label = "Altura (opcional)",
                    value = formState.heightCm,
                    onValueChange = { viewModel.updateHeight(it) },
                    unit = "cm",
                    placeholder = "Ex: 178",
                    errorMessage = formState.errors["height"],
                    testTag = "height_input"
                )
            }

            // Section: Tronco & Membros Superiores
            FormSection(title = "Tronco & Membros Superiores") {
                MeasurementField(
                    label = "Cintura",
                    value = formState.waistCm,
                    onValueChange = { viewModel.updateWaist(it) },
                    unit = "cm",
                    placeholder = "Ex: 91.0",
                    errorMessage = formState.errors["waist"],
                    testTag = "waist_input"
                )
                MeasurementField(
                    label = "Abdômen",
                    value = formState.abdomenCm,
                    onValueChange = { viewModel.updateAbdomen(it) },
                    unit = "cm",
                    placeholder = "Ex: 94.5",
                    errorMessage = formState.errors["abdomen"],
                    testTag = "abdomen_input"
                )
                MeasurementField(
                    label = "Peito / Tórax",
                    value = formState.chestCm,
                    onValueChange = { viewModel.updateChest(it) },
                    unit = "cm",
                    placeholder = "Ex: 104.0",
                    errorMessage = formState.errors["chest"],
                    testTag = "chest_input"
                )
                MeasurementField(
                    label = "Braço direito",
                    value = formState.rightArmCm,
                    onValueChange = { viewModel.updateRightArm(it) },
                    unit = "cm",
                    placeholder = "Ex: 38.0",
                    errorMessage = formState.errors["rightArm"],
                    testTag = "right_arm_input"
                )
                MeasurementField(
                    label = "Braço esquerdo",
                    value = formState.leftArmCm,
                    onValueChange = { viewModel.updateLeftArm(it) },
                    unit = "cm",
                    placeholder = "Ex: 37.5",
                    errorMessage = formState.errors["leftArm"],
                    testTag = "left_arm_input"
                )
            }

            // Section: Membros Inferiores
            FormSection(title = "Membros Inferiores") {
                MeasurementField(
                    label = "Quadril",
                    value = formState.hipCm,
                    onValueChange = { viewModel.updateHip(it) },
                    unit = "cm",
                    placeholder = "Ex: 102.0",
                    errorMessage = formState.errors["hip"],
                    testTag = "hip_input"
                )
                MeasurementField(
                    label = "Coxa direita",
                    value = formState.rightThighCm,
                    onValueChange = { viewModel.updateRightThigh(it) },
                    unit = "cm",
                    placeholder = "Ex: 60.0",
                    errorMessage = formState.errors["rightThigh"],
                    testTag = "right_thigh_input"
                )
                MeasurementField(
                    label = "Coxa esquerda",
                    value = formState.leftThighCm,
                    onValueChange = { viewModel.updateLeftThigh(it) },
                    unit = "cm",
                    placeholder = "Ex: 59.5",
                    errorMessage = formState.errors["leftThigh"],
                    testTag = "left_thigh_input"
                )
                MeasurementField(
                    label = "Panturrilha",
                    value = formState.calfCm,
                    onValueChange = { viewModel.updateCalf(it) },
                    unit = "cm",
                    placeholder = "Ex: 39.0",
                    errorMessage = formState.errors["calf"],
                    testTag = "calf_input"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            color = Lime400,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}
