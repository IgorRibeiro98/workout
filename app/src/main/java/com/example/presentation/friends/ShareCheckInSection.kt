package com.example.presentation.friends

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.social.WorkoutCheckInContract
import com.example.ui.theme.Red400
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import androidx.lifecycle.compose.collectAsStateWithLifecycle

const val SHARE_CHECKIN_CTA_LABEL = "Compartilhar check-in"
const val SHARE_CHECKIN_SECTION_DESCRIPTION = "Compartilhar check-in do treino com amigos"
const val SHARE_CHECKIN_SHARED_LABEL = "Check-in compartilhado ✓"

// --- T17.9 ---------------------------------------------------------------------------------

const val SHARE_CHECKIN_COMPOSER_DESCRIPTION = "Compositor de check-in"
const val SHARE_CHECKIN_ADD_PHOTO_LABEL = "Adicionar foto"
const val SHARE_CHECKIN_REMOVE_PHOTO_LABEL = "Remover foto"
const val SHARE_CHECKIN_PHOTO_PREVIEW_DESCRIPTION = "Prévia da foto escolhida"
const val SHARE_CHECKIN_CAPTION_LABEL = "Legenda opcional"
const val SHARE_CHECKIN_CAPTION_DESCRIPTION = "Legenda opcional do check-in"
const val SHARE_CHECKIN_PHOTO_FAILURE_DESCRIPTION = "Falha ao enviar a foto"
const val SHARE_CHECKIN_RETRY_PHOTO_LABEL = "Tentar novamente"
const val SHARE_CHECKIN_PUBLISH_WITHOUT_PHOTO_LABEL = "Publicar sem foto"

/** O que **não** é compartilhado. A lista é literal na tela, e não uma promessa genérica (§101). */
internal val SHARE_CHECKIN_EXCLUDED_ITEMS = listOf(
    "exercícios",
    "cargas",
    "séries e repetições",
    "duração",
    "horário do treino",
    "notas privadas",
    "histórico"
)

/**
 * O CTA social do Resumo e do Histórico (T17.8 §98–§106).
 *
 * ## Ele nunca bloqueia a conclusão
 *
 * Esta seção só é composta depois de a sessão já estar `COMPLETED` e salva — ela recebe um
 * `sessionId` que só existe porque o treino terminou. Não há caminho em que compartilhar venha
 * antes de concluir, e fechar o Resumo sem tocar aqui é um desfecho completo (§99).
 *
 * ## O primeiro toque não publica
 *
 * Ele abre o preview, que diz o que será e o que **não** será compartilhado. Só a confirmação
 * publica (§102).
 */
@Composable
fun ShareCheckInSection(
    viewModel: WorkoutCheckInViewModel,
    sessionId: Long,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.prepare(sessionId) }

    // Nada é desenhado quando não há o que oferecer: sem conta, sem backend, com o Social
    // desativado ou com o treino fora da janela, o Resumo continua exatamente como era.
    if (!uiState.canShare && !uiState.isShared && !uiState.isPublishing) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = SHARE_CHECKIN_SECTION_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            uiState.isPublishing -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Lime400, modifier = Modifier.size(20.dp))
            }

            uiState.isShared -> Surface(
                color = SurfaceDark,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Lime400),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = SHARE_CHECKIN_SHARED_LABEL,
                    color = Lime400,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            else -> OutlinedButton(
                onClick = viewModel::requestShare,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Lime400)
            ) {
                Text(
                    text = SHARE_CHECKIN_CTA_LABEL,
                    color = Lime400,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }

    ShareCheckInDialogs(viewModel)
}

/**
 * Só os diálogos: o preview e o resultado.
 *
 * Existe separado para o Histórico, onde o CTA já é o item do menu de opções da sessão e um botão
 * a mais na tela seria um terceiro toque para a mesma intenção (§105).
 */
@Composable
fun ShareCheckInDialogs(viewModel: WorkoutCheckInViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // O Photo Picker **oficial** do Android (§44).
    //
    // `PickVisualMedia` roda no processo do sistema e devolve um `Uri` de leitura para **um**
    // arquivo escolhido pela pessoa. Ele não exige `READ_MEDIA_IMAGES` nem
    // `READ_EXTERNAL_STORAGE`, e é por isso que o `AndroidManifest` do Spark continua sem elas: o
    // app nunca teve, e não passa a ter, acesso à galeria inteira. `CAMERA` também continua fora
    // (§45) — captura direta é outra tarefa.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onPhotoPicked(uri) }

    if (uiState.isConfirming) {
        ShareCheckInComposerDialog(
            uiState = uiState,
            onCaptionChanged = viewModel::onCaptionChanged,
            onPickPhoto = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRemovePhoto = viewModel::removePhoto,
            onConfirm = viewModel::confirmShare,
            onDismiss = viewModel::cancelShare
        )
    }

    // §43 — a foto falhou. A publicação **não** aconteceu, e as duas saídas ficam na tela.
    (uiState.photo as? CheckInPhotoState.Failed)?.let { failed ->
        PhotoFailureDialog(
            message = failed.message,
            onRetry = viewModel::retryPhotoUpload,
            onPublishWithoutPhoto = viewModel::publishWithoutPhoto,
            onDismiss = viewModel::removePhoto
        )
    }

    uiState.feedback?.let { feedback ->
        ShareCheckInFeedbackDialog(feedback = feedback, onDismiss = viewModel::dismissFeedback)
    }
}

/**
 * O compositor de check-in (T17.8 §101; T17.9 §123–§125).
 *
 * ```text
 * Compartilhar check-in
 *
 * [ Adicionar foto ]            ← Photo Picker oficial, sem permissão ampla (§44)
 *
 * Legenda opcional
 * ┌──────────────────────────────┐
 * │ Hoje rendeu demais...        │
 * └──────────────────────────────┘
 *
 * Seus amigos não verão:
 * • exercícios  • cargas  • séries/repetições  • horário do treino  • notas privadas
 *
 * [ Cancelar ]  [ Compartilhar ]
 * ```
 *
 * ## As duas listas continuam sendo o ponto (§101)
 *
 * Ele diz o que os amigos verão **e**, item a item, o que não será compartilhado. Consentimento
 * informado sobre um app de treino significa saber que carga, série e horário não vão junto — e
 * agora que existe uma legenda, saber que ela é a **única** coisa em texto livre que sai daqui.
 *
 * ## Nada acontece sozinho
 *
 * Abrir o compositor não faz requisição (§102/§144). Escolher a foto lê e reduz **no aparelho**
 * (§46). Só a confirmação envia alguma coisa — e, quando há foto, o envio dela é o primeiro passo,
 * com a falha parando ali (§43) em vez de publicar sem ela.
 */
@Composable
private fun ShareCheckInComposerDialog(
    uiState: WorkoutCheckInUiState,
    onCaptionChanged: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        modifier = Modifier.semantics {
            contentDescription = SHARE_CHECKIN_COMPOSER_DESCRIPTION
        },
        title = {
            Text("Compartilhar check-in", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Seus amigos poderão ver que você concluiu um treino.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )

                CheckInPhotoField(
                    photo = uiState.photo,
                    onPick = onPickPhoto,
                    onRemove = onRemovePhoto
                )

                CheckInCaptionField(
                    caption = uiState.caption,
                    remaining = uiState.captionRemaining,
                    onCaptionChanged = onCaptionChanged
                )

                Surface(
                    color = SurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Não serão compartilhados:",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        SHARE_CHECKIN_EXCLUDED_ITEMS.forEach { item ->
                            Text(text = "• $item", color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = uiState.canConfirm) {
                Text(
                    text = "Compartilhar",
                    color = if (uiState.canConfirm) Lime400 else TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        }
    )
}

/**
 * O campo de foto (§124/§125).
 *
 * O preview mostra **os bytes que serão enviados** — já reduzidos e com a rotação aplicada —, e
 * não o arquivo original: mostrar uma coisa e publicar outra seria uma promessa quebrada. Remover
 * antes de publicar é permitido, e o botão está sempre ali.
 */
@Composable
private fun CheckInPhotoField(
    photo: CheckInPhotoState,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    when (photo) {
        CheckInPhotoState.None -> OutlinedButton(
            onClick = onPick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = SHARE_CHECKIN_ADD_PHOTO_LABEL },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderLight)
        ) {
            Text(SHARE_CHECKIN_ADD_PHOTO_LABEL, color = TextPrimary, fontSize = 14.sp)
        }

        CheckInPhotoState.Preparing -> PhotoPlaceholder("Preparando a foto…")

        is CheckInPhotoState.Ready -> PhotoPreview(
            bytes = photo.bytes,
            width = photo.width,
            height = photo.height,
            onRemove = onRemove
        )

        is CheckInPhotoState.Uploading -> PhotoPlaceholder("Enviando a foto…")

        is CheckInPhotoState.Uploaded -> PhotoPreview(
            bytes = photo.bytes,
            width = photo.width,
            height = photo.height,
            onRemove = onRemove
        )

        is CheckInPhotoState.Failed -> PhotoPreview(
            bytes = photo.bytes,
            width = photo.width,
            height = photo.height,
            onRemove = onRemove
        )
    }
}

@Composable
private fun PhotoPlaceholder(label: String) {
    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(color = Lime400, modifier = Modifier.size(18.dp))
            Text(label, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PhotoPreview(
    bytes: ByteArray,
    width: Int,
    height: Int,
    onRemove: () -> Unit
) {
    // Decodificado uma vez por conteúdo: sem `remember`, cada recomposição do diálogo alocaria um
    // bitmap novo de vários megabytes.
    val image: ImageBitmap? = remember(bytes) { decodeToImageBitmap(bytes) }
    val ratio = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio.coerceIn(0.5f, 2f)),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = SHARE_CHECKIN_PHOTO_PREVIEW_DESCRIPTION,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text("Prévia indisponível", color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
        TextButton(onClick = onRemove) {
            Text(SHARE_CHECKIN_REMOVE_PHOTO_LABEL, color = Red400, fontSize = 13.sp)
        }
    }
}

/**
 * O campo de legenda (§7/§123).
 *
 * O contador é o feedback imediato do limite. Ele **não** é a autorização: o servidor revalida,
 * normaliza e recusa o que não for texto (§9) — e o botão desabilitado aqui é conveniência, pelo
 * mesmo motivo de sempre.
 */
@Composable
private fun CheckInCaptionField(
    caption: String,
    remaining: Int,
    onCaptionChanged: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = caption,
            onValueChange = onCaptionChanged,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = SHARE_CHECKIN_CAPTION_DESCRIPTION },
            label = { Text(SHARE_CHECKIN_CAPTION_LABEL, color = TextSecondary) },
            placeholder = { Text("Hoje rendeu demais...", color = TextSecondary) },
            singleLine = false,
            maxLines = 4
        )
        Text(
            text = "$remaining",
            color = if (remaining < 0) Red400 else TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * O diálogo de §43: a foto falhou, e a decisão é do usuário.
 *
 * Duas ações, e nenhuma delas é o padrão. "Publicar sem foto" está ali porque a pessoa pode
 * legitimamente querer isso; ele **não** acontece sozinho, porque publicar sem a foto que ela
 * escolheu, em silêncio, é decidir por ela.
 */
@Composable
private fun PhotoFailureDialog(
    message: String,
    onRetry: () -> Unit,
    onPublishWithoutPhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        modifier = Modifier.semantics {
            contentDescription = SHARE_CHECKIN_PHOTO_FAILURE_DESCRIPTION
        },
        title = { Text(message, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Text(
                text = "Seu treino continua salvo. Você pode tentar de novo ou publicar sem a foto.",
                color = TextSecondary,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(SHARE_CHECKIN_RETRY_PHOTO_LABEL, color = Lime400, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onPublishWithoutPhoto) {
                Text(SHARE_CHECKIN_PUBLISH_WITHOUT_PHOTO_LABEL, color = TextSecondary)
            }
        }
    )
}

/** Decodifica bytes JPEG em `ImageBitmap`. `null` quando não é imagem — a tela lida com isso. */
private fun decodeToImageBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?.asImageBitmap()
}.getOrNull()

@Composable
private fun ShareCheckInFeedbackDialog(
    feedback: CheckInShareFeedback,
    onDismiss: () -> Unit
) {
    val message = when (feedback) {
        CheckInShareFeedback.Published -> SHARE_CHECKIN_SHARED_LABEL
        CheckInShareFeedback.AlreadyShared ->
            "Este treino já teve um check-in publicado."
        CheckInShareFeedback.CloudNotAdopted ->
            "Para compartilhar check-ins, seus treinos precisam estar sincronizados com sua " +
                "Conta Spark. Ative a sincronização no Perfil."
        is CheckInShareFeedback.NotShared -> feedback.detail
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        text = { Text(message, color = TextPrimary, fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", color = Lime400) } }
    )
}
