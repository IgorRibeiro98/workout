package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * O vínculo entre uma sessão **deste aparelho** e uma sala de treino em dupla à distância (T19.5).
 *
 * É o mínimo que a recuperação precisa: qual sala, de qual conta. Tudo o mais — quem está na sala,
 * o que o outro já fez, em que ordem — é lido do servidor a cada reconexão (`after = 0`) e vive só
 * em memória. Não existe cópia local do estado remoto: ela seria uma segunda fonte de verdade sobre
 * algo que este aparelho não controla.
 *
 * ## Escopo por conta
 *
 * `accountUid` é a conta com que a sala foi aberta ou aceita. O coordenador só conecta quando a
 * conta atual é **esta**: depois de um logout e login com outra conta, o vínculo fica inerte — o
 * treino local continua (é do aparelho, como toda sessão), mas nenhuma sala, cursor ou evento da
 * conta anterior chega à conta nova. O uid aqui é armazenamento privado do aparelho, como o de
 * `CloudDataBinding`; ele nunca sai em DTO.
 *
 * ## `finishedNotifiedAt`
 *
 * Quando a sessão termina (ou é cancelada), o aparelho avisa a sala (`MEMBER_FINISHED` / leave).
 * Se o processo morrer antes disso, o aviso é reenviado na próxima abertura — é para isso que a
 * linha sobrevive à sessão concluída.
 */
@Entity(
    tableName = "workout_session_multiplayer_links",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["roomId", "accountUid"], unique = true)
    ]
)
data class WorkoutSessionMultiplayerLinkEntity(
    @PrimaryKey val sessionId: Long,
    val roomId: String,
    val accountUid: String,
    /** `HOST` ou `GUEST` — o papel deste aparelho na sala. Rótulo para a tela; a autoridade é o servidor. */
    val role: String,
    val peerDisplayName: String? = null,
    val createdAt: Long,
    val finishedNotifiedAt: Long? = null
)
