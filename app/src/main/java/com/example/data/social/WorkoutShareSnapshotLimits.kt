package com.example.data.social

/**
 * As regras de forma de um snapshot de compartilhamento, do lado do aparelho (T19.H2).
 *
 * ## Por que elas existem aqui
 *
 * Quem decide se uma oferta é aceita é o servidor (`workout-share.service.ts`). Mas uma oferta
 * recusada depois da requisição vira, na tela, "o servidor recusou o conteúdo" — uma frase que não
 * diz o que corrigir. Repetir as faixas aqui é o que permite bloquear **antes** da oferta existir,
 * nomeando o treino, o exercício e o campo.
 *
 * ## Por que elas não podem divergir
 *
 * Dois lugares com o mesmo número é exatamente onde a H1.2 e a H2.6 nasceram: o app não conhecia
 * uma regra do servidor e o usuário só descobria pela recusa. A amarra é a fixture compartilhada
 * `contracts/social/v1/workout-share-snapshot.json`, lida pelo teste daqui
 * (`WorkoutShareContractTest`) **e** pelo do backend (`workout-share-contract.spec.ts`). Mudar um
 * número de um lado só deixa o teste do outro vermelho.
 *
 * ## As duas versões
 *
 * ```text
 * V1   1..30 exercícios por treino, todos do catálogo
 * V2   0..30 exercícios por treino  +  exercício CUSTOM portátil
 * ```
 *
 * O app **escreve V1 sempre que ela basta**, e V2 só quando a oferta precisa de treino vazio ou de
 * CUSTOM. Não é conservadorismo gratuito: uma oferta V1 é aceita por qualquer servidor Spark já
 * publicado, e o caminho que já funcionava não passa a depender de um servidor novo.
 */
object WorkoutShareSnapshotLimits {

    /** A forma original. Continua sendo escrita, aceita e importável. */
    const val VERSION_V1 = 1

    /** Acrescenta treino vazio e exercício CUSTOM portátil. */
    const val VERSION_V2 = 2

    const val NAME_MAX_LENGTH = 100
    const val SHORT_IDENTIFIER_MAX_LENGTH = 10
    const val DESCRIPTION_MAX_LENGTH = 500

    const val MIN_EXERCISES_V1 = 1
    const val MIN_EXERCISES_V2 = 0
    const val MAX_EXERCISES = 30

    const val MIN_TEMPLATES = 1
    const val MAX_TEMPLATES = 30
    const val MAX_ORDER_IN_PROGRAM = 30

    const val MIN_SORT_ORDER = 0
    const val MAX_SORT_ORDER = 30

    const val MIN_TARGET_SETS = 1
    const val MAX_TARGET_SETS = 20
    const val MIN_REPS = 1
    const val MAX_REPS = 100
    const val MIN_REST_SECONDS = 0
    const val MAX_REST_SECONDS = 600

    const val MAX_CUSTOM_EXERCISES = 30
    const val CUSTOM_NAME_MAX_LENGTH = 100
    const val CUSTOM_PRIMARY_MUSCLE_MAX_LENGTH = 60
    const val CUSTOM_EQUIPMENT_MAX_LENGTH = 60
    const val CUSTOM_DESCRIPTION_MAX_LENGTH = 500

    /** A forma de um id do catálogo, como o servidor a exige. */
    val CANONICAL_EXERCISE_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,128}$")

    /**
     * A forma de uma referência CUSTOM escopada ao snapshot.
     *
     * `custom-1`, `custom-2`: não é UUID, não é slug e não se parece com identidade global —
     * justamente para que ninguém, de nenhum lado, seja tentado a guardá-la como uma.
     */
    val CUSTOM_EXERCISE_REF_PATTERN = Regex("^custom-[0-9]{1,3}$")

    /** A chave do n-ésimo exercício CUSTOM de uma oferta, contando de 1. */
    fun customRefAt(index: Int): String = "custom-${index + 1}"
}

/**
 * O formato **do texto** que atravessa a rede no compartilhamento (T19.H2 / H2.6).
 *
 * Existe como objeto próprio porque o defeito não estava em nenhum DTO: estava na configuração do
 * serializador, e ninguém olhava para ela.
 *
 * ## `encodeDefaults`
 *
 * `kotlinx.serialization` **não escreve** um campo cujo valor é igual ao default declarado na
 * `data class`. `snapshotVersion` vale 1 e o default é 1: o corpo de
 * `POST /v1/social/workout-shares` saía **sem** `snapshotVersion`. O servidor lê `undefined`, e a
 * primeira coisa que ele valida é a versão suportada — então **toda** oferta de treino e de
 * programa era recusada com `INVALID_SNAPSHOT`, dissesse o que dissesse o resto do snapshot.
 *
 * Nenhum teste pegava porque nenhum deles olhava o texto que ia para a rede: os do Android
 * afirmavam sobre o objeto, e os do backend montavam o corpo à mão em TypeScript.
 * `WorkoutShareWireFormatTest` afirma o texto exato, e é ele que impede o retorno.
 *
 * ## `explicitNulls`
 *
 * Continua desligado, e isso é contrato: o campo ausente de um par exclusivo — `snapshot` /
 * `programSnapshot`, `canonicalExerciseId` / `customExerciseRef` — não pode virar `null` no JSON,
 * porque o servidor lê "presente" por nome, e não por valor.
 */
object WorkoutShareWireFormat {
    val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** O corpo exato de `POST /v1/social/workout-shares`. */
    fun encodeCreateRequest(request: CreateWorkoutShareRequestDto): String =
        json.encodeToString(CreateWorkoutShareRequestDto.serializer(), request)
}
