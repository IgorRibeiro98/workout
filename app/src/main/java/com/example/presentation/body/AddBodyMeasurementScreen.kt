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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.body.BodyMetricsCalculator
import com.example.ui.components.body.BodyMetricCard
import com.example.ui.components.body.MeasurementField
import com.example.ui.components.body.ValidationMessage
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

    // Dynamic IMC preview in form if weight & height are valid
    val currentWeight = formState.weightKg.replace(',', '.').toFloatOrNull()
    val currentHeight = formState.heightCm.replace(',', '.').toFloatOrNull()
    val liveBmi = BodyMetricsCalculator.calculateBmi(currentWeight, currentHeight)

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (formState.isEditMode) "Editar Medição" else "Nova Medição",
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
                            viewModel.saveMeasurement(onSuccess = onNavigateBack)
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
                                color = BackgroundDark,
                                modifier = Modifier.size(22.dp),
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
                                text = if (formState.isEditMode) "SALVAR ALTERAÇÕES" else "SALVAR MEDIÇÃO",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                letterSpacing = 0.5.sp
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // General error message banner if present
            formState.errors["general"]?.let { generalError ->
                ValidationMessage(
                    message = generalError,
                    testTag = "general_error_message"
                )
            }

            // Date Picker Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Data da Medição",
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
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { datePickerDialog.show() }
                        .testTag("date_picker_field")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formattedDate,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("selected_date_text")
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

            // Section 1: Composição Corporal
            FormSection(title = "Composição Corporal") {
                MeasurementField(
                    label = "Peso",
                    value = formState.weightKg,
                    onValueChange = { viewModel.updateWeight(it) },
                    unit = "kg",
                    placeholder = "Ex: 88.4",
                    errorMessage = formState.errors["weight"],
                    testTag = "input_weight"
                )

                MeasurementField(
                    label = "Altura",
                    value = formState.heightCm,
                    onValueChange = { viewModel.updateHeight(it) },
                    unit = "cm",
                    placeholder = "Ex: 171",
                    errorMessage = formState.errors["height"],
                    testTag = "input_height"
                )

                MeasurementField(
                    label = "Percentual de Gordura",
                    value = formState.bodyFatPercentage,
                    onValueChange = { viewModel.updateBodyFat(it) },
                    unit = "%",
                    placeholder = "Ex: 15.5",
                    errorMessage = formState.errors["bodyFat"],
                    testTag = "input_body_fat"
                )

                // Live dynamic IMC card inside form when both valid
                if (liveBmi != null) {
                    BodyMetricCard(bmiResult = liveBmi)
                }
            }

            // Section 2: Tronco
            FormSection(title = "Tronco") {
                MeasurementField(
                    label = "Cintura",
                    value = formState.waistCm,
                    onValueChange = { viewModel.updateWaist(it) },
                    unit = "cm",
                    placeholder = "Ex: 91",
                    errorMessage = formState.errors["waist"],
                    testTag = "input_waist"
                )

                MeasurementField(
                    label = "Abdômen",
                    value = formState.abdomenCm,
                    onValueChange = { viewModel.updateAbdomen(it) },
                    unit = "cm",
                    placeholder = "Ex: 94",
                    errorMessage = formState.errors["abdomen"],
                    testTag = "input_abdomen"
                )

                MeasurementField(
                    label = "Peito",
                    value = formState.chestCm,
                    onValueChange = { viewModel.updateChest(it) },
                    unit = "cm",
                    placeholder = "Ex: 105",
                    errorMessage = formState.errors["chest"],
                    testTag = "input_chest"
                )

                MeasurementField(
                    label = "Quadril",
                    value = formState.hipCm,
                    onValueChange = { viewModel.updateHip(it) },
                    unit = "cm",
                    placeholder = "Ex: 101",
                    errorMessage = formState.errors["hip"],
                    testTag = "input_hip"
                )
            }

            // Section 3: Membros Superiores
            FormSection(title = "Membros Superiores") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MeasurementField(
                        label = "Braço Direito",
                        value = formState.rightArmCm,
                        onValueChange = { viewModel.updateRightArm(it) },
                        unit = "cm",
                        placeholder = "Ex: 38",
                        errorMessage = formState.errors["rightArm"],
                        modifier = Modifier.weight(1f),
                        testTag = "input_right_arm"
                    )

                    MeasurementField(
                        label = "Braço Esquerdo",
                        value = formState.leftArmCm,
                        onValueChange = { viewModel.updateLeftArm(it) },
                        unit = "cm",
                        placeholder = "Ex: 37.5",
                        errorMessage = formState.errors["leftArm"],
                        modifier = Modifier.weight(1f),
                        testTag = "input_left_arm"
                    )
                }
            }

            // Section 4: Membros Inferiores
            FormSection(title = "Membros Inferiores") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MeasurementField(
                        label = "Coxa Direita",
                        value = formState.rightThighCm,
                        onValueChange = { viewModel.updateRightThigh(it) },
                        unit = "cm",
                        placeholder = "Ex: 60",
                        errorMessage = formState.errors["rightThigh"],
                        modifier = Modifier.weight(1f),
                        testTag = "input_right_thigh"
                    )

                    MeasurementField(
                        label = "Coxa Esquerda",
                        value = formState.leftThighCm,
                        onValueChange = { viewModel.updateLeftThigh(it) },
                        unit = "cm",
                        placeholder = "Ex: 59.5",
                        errorMessage = formState.errors["leftThigh"],
                        modifier = Modifier.weight(1f),
                        testTag = "input_left_thigh"
                    )
                }

                MeasurementField(
                    label = "Panturrilha",
                    value = formState.calfCm,
                    onValueChange = { viewModel.updateCalf(it) },
                    unit = "cm",
                    placeholder = "Ex: 39",
                    errorMessage = formState.errors["calf"],
                    testTag = "input_calf"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
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
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                content()
            }
        }
    }
}
