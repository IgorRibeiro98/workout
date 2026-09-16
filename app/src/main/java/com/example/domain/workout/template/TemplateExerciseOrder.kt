package com.example.domain.workout.template

import com.example.data.local.WorkoutTemplateExerciseEntity

/**
 * A ordem dos exercícios de um treino (T19.6).
 *
 * `sortOrder` é a autoridade da ordem e pertence ao template; a lista do editor é só uma projeção
 * dela. Este objeto é o único lugar que transforma "a ordem que o usuário quer" em `sortOrder`, e
 * o faz sem tocar em nada além da posição: os `id` das linhas, o `exerciseId` e a configuração
 * (séries, repetições, descanso, carga, aparelho, observações) saem exatamente como entraram.
 */
object TemplateExerciseOrder {

    /**
     * Reordena [current] segundo [orderedIds] e devolve a lista com `sortOrder` normalizado
     * (0..n-1, sem buracos nem repetição), sem criar nem apagar nenhum item.
     *
     * A ordem pedida pode estar **desatualizada** em relação ao banco — um sync pode ter
     * substituído as linhas do treino enquanto o usuário arrastava (T16.3 recria os filhos do
     * agregado). Por isso ids desconhecidos são ignorados e itens que a ordem não menciona vão
     * para o fim, na ordem relativa em que já estavam. Nenhum dos dois casos duplica ou remove
     * um exercício.
     */
    fun reorder(
        current: List<WorkoutTemplateExerciseEntity>,
        orderedIds: List<Long>
    ): List<WorkoutTemplateExerciseEntity> {
        val byId = current.associateBy { it.id }
        val placed = LinkedHashSet<Long>()
        val result = ArrayList<WorkoutTemplateExerciseEntity>(current.size)
        for (id in orderedIds) {
            val item = byId[id] ?: continue
            if (placed.add(id)) result += item
        }
        for (item in current) {
            if (placed.add(item.id)) result += item
        }
        return normalize(result)
    }

    /** Move o item de [fromIndex] para [toIndex] e normaliza; índices inválidos não mudam nada. */
    fun move(
        current: List<WorkoutTemplateExerciseEntity>,
        fromIndex: Int,
        toIndex: Int
    ): List<WorkoutTemplateExerciseEntity> {
        if (fromIndex !in current.indices || toIndex !in current.indices) return current
        val ids = current.map { it.id }.toMutableList()
        val id = ids.removeAt(fromIndex)
        ids.add(toIndex, id)
        return reorder(current, ids)
    }

    /**
     * `sortOrder` = posição na lista. Idempotente: aplicar duas vezes dá o mesmo resultado, e uma
     * lista já normalizada volta igual (`==` de `data class`), o que permite ao chamador pular a
     * escrita quando nada mudou.
     */
    fun normalize(items: List<WorkoutTemplateExerciseEntity>): List<WorkoutTemplateExerciseEntity> =
        items.mapIndexed { index, item ->
            if (item.sortOrder == index) item else item.copy(sortOrder = index)
        }
}
