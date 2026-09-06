package com.example.presentation.account

import com.example.data.backup.BackupSummary

/**
 * O estado do backup na tela (T16.4).
 *
 * ## Por que não um `isLoading`
 *
 * "Preparando o snapshot" e "enviando" são coisas diferentes para quem espera, e "falhou" precisa
 * dizer **o que** falhou para o usuário saber se adianta tentar de novo. Um booleano colapsaria
 * sete situações em duas, e a UI teria que adivinhar a diferença entre "backup indisponível" e
 * "esta conta não é a dona destes dados" — que são conselhos opostos.
 */
sealed interface BackupPhase {

    /** Não há endereço de Spark Backend neste build. Nada a oferecer, e dizer isso é honesto. */
    data object NotConfigured : BackupPhase

    /** Sem Conta Spark. O backup exige uma; o resto do Spark, não. */
    data object NotAuthenticated : BackupPhase

    /** Autenticado, e os dados deste aparelho ainda **não** foram associados a nenhuma conta. */
    data object Unbound : BackupPhase

    /** Vinculado e ocioso. [lastBackupAt] vem do relógio do **servidor**, quando existe. */
    data class Ready(
        val ownerUid: String,
        val lastBackupAt: Long? = null,
        val lastBackupItemCount: Int? = null,
        /** Uma tentativa criada e ainda não confirmada — de uma falha ou de um process death. */
        val hasPendingAttempt: Boolean = false
    ) : BackupPhase

    /** Lendo o banco e montando o snapshot. */
    data object PreparingSnapshot : BackupPhase

    /** O snapshot está subindo. Sem porcentagem: não há métrica real de bytes para mostrar. */
    data object Uploading : BackupPhase

    /**
     * Os dados deste aparelho pertencem a outra Conta Spark.
     *
     * Só os recursos de nuvem ficam indisponíveis. Treino, execução e histórico continuam
     * completos — e nada da conta dona é exposto além do fato de existir.
     */
    data object AccountMismatch : BackupPhase

    /** A última tentativa falhou e continua guardada. [reason] é classe de erro, nunca conteúdo. */
    data class Failed(val reason: BackupFailure) : BackupPhase
}

/**
 * Por que o backup não completou.
 *
 * Classes de falha, e não mensagens do servidor: mensagem de servidor não é texto de UI, e um
 * `code` interno não ajuda quem está olhando a tela.
 */
enum class BackupFailure {
    /** Sem internet ou sem alcançar o servidor. Tentar de novo depois resolve. */
    NETWORK,

    /** O Spark Backend respondeu indisponível. Recuperável. */
    UNAVAILABLE,

    /** É preciso entrar na Conta Spark de novo. Não é falha do backup. */
    AUTH_REQUIRED,

    /** O servidor recusou o snapshot. Isso é defeito, e o texto convida a relatar. */
    REJECTED,

    /** O snapshot não pôde ser montado a partir do banco local. */
    SNAPSHOT
}

/**
 * O que a UI do backup precisa saber.
 *
 * [summary] só existe enquanto a confirmação de adoção está aberta: ela é lida sob demanda, no
 * toque em "Ativar backup", e não em `init` nem em recomposição.
 */
data class BackupUiState(
    val phase: BackupPhase = BackupPhase.NotConfigured,
    /** `true` enquanto a folha de confirmação da adoção está visível. */
    val isConfirmingAdoption: Boolean = false,
    /** O que será associado à conta. `null` até a confirmação ser aberta. */
    val summary: BackupSummary? = null,
    /** O e-mail da conta que fará a adoção, quando o Google o informa. */
    val accountEmail: String? = null
) {

    /** Uma operação em andamento: enquanto isso, novos toques são ignorados. */
    val isBusy: Boolean
        get() = phase is BackupPhase.PreparingSnapshot || phase is BackupPhase.Uploading
}
