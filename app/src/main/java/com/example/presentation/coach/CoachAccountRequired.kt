package com.example.presentation.coach

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.presentation.account.findActivityOrSelf
import com.example.ui.theme.BorderLight
import com.example.ui.theme.Lime400
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * O convite único do Coach quando falta Conta Spark.
 *
 * A partir da T16.2 o Coach é uma capacidade **online autenticada**: o núcleo do Spark continua
 * completo sem conta, mas uma chamada nova ao modelo, não. O texto diz exatamente isso, e o botão
 * reutiliza a infraestrutura da T16.1 — não existe uma segunda implementação de login dentro do
 * Coach.
 *
 * O seletor de contas **nunca** abre sozinho: ele só aparece se o usuário tocar aqui.
 */
const val COACH_REQUIRES_ACCOUNT: String =
    "O Coach IA usa a Conta Spark para falar com o modelo. Entre para continuar — seus treinos, " +
        "histórico, execução e gamificação seguem funcionando normalmente sem conta."

@Composable
fun CoachAccountRequiredCard(
    message: String = COACH_REQUIRES_ACCOUNT,
    /** `false` quando falta configuração de Firebase/Google neste build. */
    isSignInAvailable: Boolean = true,
    isSigningIn: Boolean = false,
    onSignIn: (Context) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Surface(
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Entre na sua Conta Spark",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(text = message, color = TextSecondary, fontSize = 13.sp)

            if (isSignInAvailable) {
                Button(
                    onClick = { onSignIn(context.findActivityOrSelf()) },
                    enabled = !isSigningIn,
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
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            color = TextSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(text = "Entrando...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    } else {
                        Text(
                            text = "Continuar com Google",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                // Sem configuração de Firebase/Google neste build não há ação a oferecer, e dizer
                // isso é mais honesto do que um botão que falharia.
                Text(
                    text = "Entrar com o Google não está configurado neste aplicativo.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}
