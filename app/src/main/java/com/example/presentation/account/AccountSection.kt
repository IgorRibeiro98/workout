package com.example.presentation.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.data.firebase.SparkAppCheck
import com.example.domain.auth.AuthError
import com.example.domain.auth.SparkAccount
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.LimeTransparent
import com.example.ui.theme.Red400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * A área de Conta Spark dentro do Perfil.
 *
 * A conta é **opcional**, e o texto diz isso: nada aqui sugere que o usuário precise entrar para
 * continuar usando o Spark. Também não anuncia o que ainda não existe — desde a T16.4 o backup é
 * real e mora na seção logo abaixo, mas sincronização entre dispositivos continua sendo etapa
 * futura, e a tela nunca diz "sincronizado".
 */
@Composable
fun AccountSection(
    uiState: AccountUiState,
    onSignIn: (Context) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    canVerifyWithBackend: Boolean = false,
    onVerifyWithBackend: () -> Unit = {}
) {
    val context = LocalContext.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Conta Spark",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val account = uiState.account
                if (account != null) {
                    SignedInContent(
                        account = account,
                        isBusy = uiState.isBusy,
                        onSignOut = onSignOut
                    )
                } else {
                    SignedOutContent(
                        uiState = uiState,
                        onSignIn = { onSignIn(context.findActivityOrSelf()) }
                    )
                }

                // Diagnóstico de desenvolvimento, não feature: prova a cadeia
                // Firebase Auth -> ID Token -> Spark Backend -> Firebase Admin -> uid.
                if (BuildConfig.DEBUG && canVerifyWithBackend && uiState.isSignedIn) {
                    BackendCheckRow(
                        check = uiState.backendCheck,
                        onVerify = onVerifyWithBackend
                    )
                }

                // App Check atesta o **aplicativo** perante o Firebase, e desde a T16.2 quem o
                // instala é a fronteira de autenticação — por isso a ferramenta de depuração dele
                // mora aqui, e não na tela do Coach, que não fala mais com o Firebase.
                if (BuildConfig.DEBUG && SparkAppCheck.SUPPORTS_DEBUG_TOKEN) {
                    AppCheckDebugTokenRow()
                }
            }
        }
    }
}

@Composable
private fun SignedOutContent(
    uiState: AccountUiState,
    onSignIn: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Entrar é opcional",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Text(
            text = "Uma conta habilita o backup dos seus dados neste servidor. " +
                "Sincronização entre dispositivos chega nas próximas etapas.",
            color = TextSecondary,
            fontSize = 13.sp
        )
    }

    val error = uiState.error
    if (error != null) {
        AccountErrorMessage(error)
    }

    if (uiState.isSignInAvailable) {
        Button(
            onClick = onSignIn,
            enabled = !uiState.isBusy,
            colors = ButtonDefaults.buttonColors(
                containerColor = Lime400,
                contentColor = SurfaceDark,
                disabledContainerColor = SurfaceHighlight,
                disabledContentColor = TextSecondary
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (uiState.isBusy) {
                CircularProgressIndicator(
                    color = TextSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(text = "Entrando...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            } else {
                Text(
                    text = if (error != null) "Tentar novamente" else "Continuar com Google",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    } else {
        // Sem configuração de Firebase/Google neste build não há botão para oferecer — e dizer
        // isso é mais honesto do que apresentar uma ação que falharia.
        Text(
            text = "Entrar com o Google não está configurado neste aplicativo.",
            color = TextSecondary,
            fontSize = 13.sp
        )
    }

    Text(
        text = "Você pode continuar usando o Spark sem conta.",
        color = TextSecondary,
        fontSize = 12.sp
    )
}

@Composable
private fun SignedInContent(
    account: SparkAccount,
    isBusy: Boolean,
    onSignOut: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AccountAvatar(photoUrl = account.photoUrl)

        Column(modifier = Modifier.weight(1f)) {
            // Nenhum destes campos é garantido pelo Google: o único obrigatório é o uid.
            Text(
                text = account.displayName ?: "Conta conectada",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            val email = account.email
            if (email != null) {
                Text(text = email, color = TextSecondary, fontSize = 13.sp)
            }
            Text(
                text = "Conectado",
                color = Lime400,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }

    // O backup vive na seção logo abaixo, e é onde ele é ativado. Aqui a conta só diz o que é.
    Text(
        text = "Sincronização entre dispositivos chega nas próximas etapas.",
        color = TextSecondary,
        fontSize = 12.sp
    )

    OutlinedButton(
        onClick = onSignOut,
        enabled = !isBusy,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextPrimary,
            disabledContentColor = TextSecondary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                color = TextSecondary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(text = "Saindo...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        } else {
            Text(text = "Sair da conta", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/**
 * O token de depuração do App Check deste aparelho.
 *
 * Ferramenta de desenvolvimento, compilada só na variante de depuração: em release
 * `SUPPORTS_DEBUG_TOKEN` é `false` e o provedor de depuração nem existe no APK. Nenhum token vive
 * no código — sem um colado aqui, o provedor gera o dele e o registra no Logcat.
 */
@Composable
private fun AppCheckDebugTokenRow() {
    val context = LocalContext.current
    var currentToken by remember { mutableStateOf(SparkAppCheck.customDebugToken(context)) }
    var inputToken by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "App Check (debug)",
            color = Lime400,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        Text(
            text = currentToken ?: GENERATED_DEBUG_TOKEN_HINT,
            color = TextSecondary,
            fontSize = 11.sp
        )
        OutlinedTextField(
            value = inputToken,
            onValueChange = { inputToken = it },
            placeholder = {
                Text(text = "Cole o token do Firebase...", fontSize = 11.sp, color = TextSecondary)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = {
                if (inputToken.isNotBlank()) {
                    SparkAppCheck.setCustomDebugToken(context, inputToken)
                    currentToken = inputToken.trim()
                    inputToken = ""
                }
            },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, BorderLight),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Salvar token de depuração", fontSize = 12.sp)
        }
    }
}

/**
 * O que mostrar quando nenhum token de depuração foi configurado neste aparelho.
 *
 * Nenhum token vive no código: sem um colado aqui, o provedor de depuração do Firebase gera o
 * dele e o registra no Logcat para ser cadastrado no console.
 */
private const val GENERATED_DEBUG_TOKEN_HINT: String =
    "gerado pelo provedor — procure \"DebugAppCheckProvider\" no Logcat"

/**
 * A foto do Google quando existe.
 *
 * O ícone fica atrás da imagem: sem `photoUrl`, ou com o download falhando, ele continua visível
 * e o Perfil não quebra. A imagem não é baixada como mídia do Spark nem sincronizada.
 */
@Composable
private fun AccountAvatar(photoUrl: String?) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(LimeTransparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = Lime400,
            modifier = Modifier.size(28.dp)
        )
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Foto da conta",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
            )
        }
    }
}

@Composable
private fun AccountErrorMessage(error: AuthError) {
    val message = when (error) {
        AuthError.NETWORK -> "Sem conexão para entrar agora. Seus treinos continuam aqui."
        AuthError.NO_CREDENTIAL -> "Nenhuma conta Google disponível neste aparelho."
        AuthError.NOT_CONFIGURED -> "Entrar com o Google não está configurado neste aplicativo."
        AuthError.PROVIDER, AuthError.UNKNOWN -> "Não foi possível entrar agora. Tente de novo."
    }

    Surface(
        color = SurfaceHighlight,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = ACCOUNT_ERROR_DESCRIPTION }
    ) {
        Text(
            text = message,
            color = Red400,
            fontSize = 13.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun BackendCheckRow(
    check: BackendIdentityCheck,
    onVerify: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = onVerify,
            enabled = check !is BackendIdentityCheck.Running,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, BorderLight),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TextSecondary,
                disabledContentColor = TextSecondary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = "Verificar identidade no servidor", fontSize = 13.sp)
        }

        val status = when (check) {
            is BackendIdentityCheck.Idle -> null
            is BackendIdentityCheck.Running -> "Verificando..."
            is BackendIdentityCheck.Verified -> "Servidor confirmou o mesmo usuário."
            is BackendIdentityCheck.Diverged -> "O servidor concluiu outro usuário."
            is BackendIdentityCheck.Unauthenticated -> "O servidor recusou o token."
            is BackendIdentityCheck.Unavailable -> "Servidor indisponível. Sua conta segue conectada."
            is BackendIdentityCheck.NotConfigured -> "Nenhum servidor configurado neste build."
            is BackendIdentityCheck.Failed -> "Resposta inesperada do servidor."
        }
        if (status != null) {
            Text(
                text = status,
                color = if (check is BackendIdentityCheck.Verified) Lime400 else TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

/** Descrição de acessibilidade do bloco de erro — também o gancho dos testes de UI. */
const val ACCOUNT_ERROR_DESCRIPTION = "Erro ao entrar na conta"

/**
 * O Credential Manager precisa de um contexto de Activity para exibir o seletor de contas.
 * Em Compose, `LocalContext` costuma ser um `ContextWrapper` em volta dela.
 */
internal fun Context.findActivityOrSelf(): Context {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return this
}
