package com.example.domain.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.outlined.Category
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.data.local.ExerciseEntity
import com.example.domain.model.ResolvedExercise
import java.text.Normalizer

/**
 * A família de equipamento de um exercício — a dimensão que o **ícone** representa (T19.7A).
 *
 * Deriva de `ExerciseEntity.equipment`, que o catálogo canônico preenche com 24 valores
 * (`Barra`, `Halteres`, `Máquina`, `Cabo`, `Smith`, `Peso corporal`, `Elástico`, …) e que um
 * `CUSTOM` recebe como texto livre. Não é uma segunda classificação persistida: é uma leitura
 * determinística do campo que já existe.
 */
enum class EquipmentFamily(val displayName: String, val icon: ImageVector) {
    /** Barra, halteres, kettlebell, anilhas, barra EZ, trap bar — carga que o próprio usuário estabiliza. */
    FREE_WEIGHT("Peso livre", Icons.Filled.FitnessCenter),

    /** Máquina, Smith, leg press, hack, cadeiras extensora/flexora — trajetória guiada pelo aparelho. */
    MACHINE("Máquina", Icons.Filled.AirlineSeatReclineNormal),

    /** Cabo, polia, crossover e elásticos — tensão por um cabo ou faixa. */
    CABLE("Cabo ou elástico", Icons.Filled.Cable),

    /** Peso corporal, com ou sem apoio (barra fixa, paralelas, banco, bola suíça, roda). */
    BODYWEIGHT("Peso corporal", Icons.Filled.Accessibility),

    /**
     * Fallback neutro: sem equipamento informado ou texto que nenhuma regra reconhece. É um
     * ícone genérico de propósito — nunca um ícone "aleatório" de outra família.
     */
    UNKNOWN("Equipamento não informado", Icons.Outlined.Category)
}

/**
 * O que a UI mostra de um exercício, resolvido de uma vez só.
 *
 * `icon` responde "com que equipamento" ([EquipmentFamily]); `color` responde "que músculo"
 * ([MuscleGroup], via [MuscleVisualResolver]). As duas dimensões são independentes e cada uma
 * tem uma única origem — ver `docs/architecture/exercise-catalog.md`.
 */
data class ExerciseVisual(
    val equipmentFamily: EquipmentFamily,
    val muscleGroup: MuscleGroup
) {
    val icon: ImageVector get() = equipmentFamily.icon
    val color: Color get() = muscleGroup.color
}

/**
 * Única regra de ícone e cor de exercício (T19.7A).
 *
 * Todas as representações de exercício — catálogo, editor de treino, seletor, detalhes,
 * alternativas na execução, pré-visualização — passam por aqui. Um `when` local numa tela seria
 * uma segunda taxonomia.
 */
object ExerciseVisualResolver {

    fun resolve(exercise: ResolvedExercise): ExerciseVisual = resolve(
        primaryMuscle = exercise.primaryMuscle,
        equipment = exercise.equipment,
        isBodyweightFlag = exercise.rawExercise.isBodyweight
    )

    fun resolve(exercise: ExerciseEntity): ExerciseVisual = resolve(
        primaryMuscle = exercise.primaryMuscle,
        equipment = exercise.equipment,
        isBodyweightFlag = exercise.isBodyweight
    )

    /**
     * @param isBodyweightFlag o campo `ExerciseEntity.isBodyweight`. Só desempata quando o texto
     * de equipamento não diz nada: um `CUSTOM` marcado como peso corporal e sem equipamento
     * informado ganha o ícone de peso corporal em vez do neutro.
     */
    fun resolve(
        primaryMuscle: String?,
        equipment: String?,
        isBodyweightFlag: Boolean = false
    ): ExerciseVisual {
        val family = resolveEquipmentFamily(equipment).let { resolved ->
            if (resolved == EquipmentFamily.UNKNOWN && isBodyweightFlag) EquipmentFamily.BODYWEIGHT else resolved
        }
        return ExerciseVisual(
            equipmentFamily = family,
            muscleGroup = MuscleVisualResolver.resolveGroup(primaryMuscle)
        )
    }

    /**
     * Classifica o texto de equipamento.
     *
     * O catálogo combina valores com `/` (`Barra/Máquina`, `Peso corporal/Peso`,
     * `Banco/Halteres`). A regra lê os termos na ordem escrita e o primeiro que nomeia uma
     * **carga** decide; apoios (banco, bola, roda, cadeira romana, barra fixa) só decidem quando
     * nenhuma carga aparece — e aí a resposta é peso corporal. Determinística: o mesmo texto dá
     * sempre a mesma família.
     */
    fun resolveEquipmentFamily(rawEquipment: String?): EquipmentFamily {
        if (rawEquipment.isNullOrBlank()) return EquipmentFamily.UNKNOWN
        val terms = normalize(rawEquipment)
            .split('/', ',', ';', '+', '&')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        var sawSupport = false
        for (term in terms) {
            when (val classified = classifyTerm(term)) {
                Term.SUPPORT -> sawSupport = true
                Term.NONE -> Unit
                else -> return classified.family
            }
        }
        return if (sawSupport) EquipmentFamily.BODYWEIGHT else EquipmentFamily.UNKNOWN
    }

    private enum class Term(val family: EquipmentFamily) {
        FREE_WEIGHT(EquipmentFamily.FREE_WEIGHT),
        MACHINE(EquipmentFamily.MACHINE),
        CABLE(EquipmentFamily.CABLE),
        BODYWEIGHT(EquipmentFamily.BODYWEIGHT),
        SUPPORT(EquipmentFamily.BODYWEIGHT),
        NONE(EquipmentFamily.UNKNOWN)
    }

    // A ordem importa. Frases específicas ("peso livre", "barra fixa", "cadeira romana") vêm
    // antes das palavras que elas contêm ("livre", "barra", "cadeira"); e, dentro de um mesmo
    // termo, a carga decide antes do apoio — "supino máquina no banco" é máquina, não banco.
    private fun classifyTerm(term: String): Term = when {
        term.contains("peso livre") -> Term.FREE_WEIGHT
        term.contains("barra fixa") || term.contains("paralela") || term.contains("argola") ||
            term.contains("trx") || term.contains("suspens") || term.contains("peso corporal") ||
            term.contains("corporal") || term.contains("bodyweight") || term.contains("body weight") ||
            term.contains("sem equipamento") || term.contains("nenhum") || term.contains("colchonete") -> Term.BODYWEIGHT
        term.contains("cadeira romana") -> Term.SUPPORT
        term.contains("maquina") || term.contains("machine") || term.contains("smith") ||
            term.contains("leg press") || term.contains("hack") || term.contains("cadeira") ||
            term.contains("mesa flexora") || term.contains("graviton") || term.contains("aparelho") ||
            term.contains("lever") || term.contains("sled") -> Term.MACHINE
        term.contains("cabo") || term.contains("cable") || term.contains("polia") || term.contains("pulley") ||
            term.contains("crossover") || term.contains("elastic") || term.contains("band") ||
            term.contains("faixa") || term.contains("corda") -> Term.CABLE
        term.contains("halter") || term.contains("dumbbell") || term.contains("barra") ||
            term.contains("barbell") || term.contains("kettlebell") || term.contains("anilha") ||
            term.contains("plate") || term.contains("trap bar") || term.contains("landmine") ||
            term.contains("sandbag") || term == "ez" -> Term.FREE_WEIGHT
        term == "livre" || term == "solo" -> Term.BODYWEIGHT
        term.contains("banco") || term.contains("bola") || term.contains("roda") ||
            term.contains("step") || term.contains("caixa") -> Term.SUPPORT
        else -> Term.NONE
    }

    private fun normalize(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").lowercase().trim()
    }
}
