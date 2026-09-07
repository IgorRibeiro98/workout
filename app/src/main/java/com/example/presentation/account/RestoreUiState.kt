package com.example.presentation.account

import com.example.data.restore.RestoreCounts
import com.example.data.restore.RestoreError
import com.example.data.restore.RestoreWarning

/**
 * O estado do restore na tela (T16.5).
 *
 * ## Por que tantas fases
 *
 * Restore é uma operação destrutiva de várias etapas, e cada etapa muda o que o usuário pode fazer
 * e o que ele precisa saber. "Baixando", "conferindo", "isto vai substituir seus dados" e
 * "restaurado" são situações diferentes; um `isLoading` as colapsaria e a tela teria que adivinhar
 * quando pode mostrar o botão que apaga o histórico do aparelho.
 *
 * A fase também é o que impede a tela de mentir: enquanto a aplicação não concluiu **todas** as
 * fases, nenhuma delas se chama [RestorePhaseUi.Completed].
 */
sealed interface RestorePhaseUi {

    /** Não há endereço de Spark Backend neste build. A seção não aparece. */
    data object NotConfigured : RestorePhaseUi

    /** Sem Conta Spark. Restaurar exige uma; o resto do Spark, não. */
    data object NotAuthenticated : RestorePhaseUi

    /** Autenticado e ocioso. [lastRestoredAt] existe quando este aparelho já foi restaurado. */
    data class Idle(val lastRestoredAt: Long? = null) : RestorePhaseUi

    /** Buscando a lista de backups. Só metadata — nenhum snapshot é baixado aqui. */
    data object LoadingBackups : RestorePhaseUi

    /** A lista chegou. Escolher um backup ainda não baixa nem altera nada. */
    data class ChoosingBackup(val backups: List<RestoreBackupItem>) : RestorePhaseUi

    /** Baixando, conferindo o hash e validando o snapshot escolhido. Nada local foi tocado. */
    data object Preparing : RestorePhaseUi

    /**
     * O preview, montado a partir do snapshot **validado**.
     *
     * É a última tela antes de qualquer escrita, e é onde o usuário vê o que vai ganhar e o que vai
     * perder.
     */
    data class Preview(val preview: RestorePreviewUi) : RestorePhaseUi

    /** A substituição está acontecendo. Curta, e sem porcentagem inventada. */
    data object Applying : RestorePhaseUi

    /** Todas as fases concluíram. */
    data class Completed(val counts: RestoreCounts) : RestorePhaseUi

    /**
     * O dataset foi substituído e uma fase posterior não concluiu.
     *
     * **Não** é sucesso, e a tela não diz que é: ela diz que falta terminar e que reabrir o app
     * conclui.
     */
    data object RecoveryPending : RestorePhaseUi

    /** Os dados deste aparelho pertencem a outra Conta Spark. */
    data object AccountMismatch : RestorePhaseUi

    /** Falhou. [error] é classe de erro; a tela traduz, nunca exibe o nome cru. */
    data class Failed(val error: RestoreError) : RestorePhaseUi
}

/** Um backup na lista de escolha. Só o que o usuário precisa para decidir. */
data class RestoreBackupItem(
    val backupId: String,
    val createdAt: Long,
    val itemCount: Int
)

/**
 * O preview de um restore, já validado.
 *
 * As contagens vêm do [RestoreCounts] do plano — do snapshot **lido**, não do número declarado na
 * metadata do servidor. Mostrar metadata seria mostrar o que o servidor diz; mostrar o plano é
 * mostrar o que será escrito.
 */
data class RestorePreviewUi(
    val backupCreatedAt: Long,
    val counts: RestoreCounts,
    val warnings: List<RestoreWarning>,
    /** `true` quando o aparelho tem dado pessoal que a substituição vai descartar. */
    val replacesLocalData: Boolean
)

/**
 * O que a UI do restore precisa saber.
 *
 * [isConfirmingReplacement] é a segunda etapa da confirmação, e ela só existe quando há dado local
 * a perder: substituir um aparelho vazio não precisa de duas perguntas, e substituir três anos de
 * histórico não pode caber em um botão de aparência inofensiva.
 */
data class RestoreUiState(
    val phase: RestorePhaseUi = RestorePhaseUi.NotConfigured,
    val isConfirmingReplacement: Boolean = false
) {

    /** Uma operação em andamento: enquanto isso, novos toques são ignorados. */
    val isBusy: Boolean
        get() = phase is RestorePhaseUi.LoadingBackups ||
            phase is RestorePhaseUi.Preparing ||
            phase is RestorePhaseUi.Applying
}
