package com.example.presentation.workouts

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * O estado de um arrastar-e-soltar na lista do editor de treino (T19.6).
 *
 * É estado **de apresentação e temporário**: existe do long-press ao soltar, e a única coisa
 * que sai dele é a lista de ids na ordem final, entregue a [onDrop] uma vez. A autoridade da
 * ordem continua sendo `sortOrder` no Room; nada aqui é persistido nem sobrevive à recriação da
 * tela (recriar no meio de um arrastar simplesmente cancela o gesto — nada foi escrito).
 *
 * Coordenadas: tudo em pixels da própria `LazyColumn` (o `offset` do `layoutInfo` e o delta do
 * gesto vivem no mesmo sistema), como pede o `ARCHITECTURE.md §13` — o item arrastado é
 * deslocado por `graphicsLayer`, não por um `Popup`, então não há deriva entre dedo e fantasma.
 * O item arrastado é pintado em `initialOffset + dragDistance`, fixo em relação ao dedo mesmo
 * quando a lista rola por baixo dele; o slot dele na lista segue por [workingOrder], e os
 * vizinhos animam para a posição candidata.
 */
class TemplateExerciseDragState internal constructor(
    val listState: LazyListState,
    private val onDrop: (List<Long>) -> Unit
) {
    /** Id (`WorkoutTemplateExerciseEntity.id`) do item sendo arrastado, ou `null`. */
    var draggingId: Long? by mutableStateOf(null)
        private set

    /** A ordem candidata durante o gesto; `null` fora dele. */
    var workingOrder: List<Long>? by mutableStateOf(null)
        private set

    /**
     * A ordem entregue no último drop, enquanto a lista da ViewModel ainda não a reflete.
     *
     * O `StateFlow` da ViewModel só muda num despacho seguinte; sem isto a lista mostraria a
     * ordem antiga por um frame entre o drop e a projeção da ordem pendente, e `animateItem`
     * começaria a levar os itens de volta. Limpa em [onExercisesChanged], na primeira emissão
     * que já não é a ordem de antes do gesto.
     */
    var droppedOrder: List<Long>? by mutableStateOf(null)
        private set

    /** A ordem exibida pela lista: a candidata durante o gesto, a solta logo depois dele. */
    val displayedOrder: List<Long>? get() = workingOrder ?: droppedOrder

    private var orderAtStart: List<Long> = emptyList()
    private var initialOffset by mutableFloatStateOf(0f)
    private var dragDistance by mutableFloatStateOf(0f)

    val isDragging: Boolean get() = draggingId != null

    /** Quanto o item [id] deve ser deslocado no `graphicsLayer` para ficar sob o dedo. */
    fun translationFor(id: Long): Float {
        if (id != draggingId) return 0f
        val current = visibleItem(id) ?: return 0f
        return initialOffset + dragDistance - current.offset
    }

    /** Começa a arrastar [id]; `false` se outro gesto já está em curso ou o item não está visível. */
    fun start(id: Long, currentOrder: List<Long>): Boolean {
        if (isDragging) return false
        val item = visibleItem(id) ?: return false
        draggingId = id
        workingOrder = currentOrder
        orderAtStart = currentOrder
        droppedOrder = null
        initialOffset = item.offset.toFloat()
        dragDistance = 0f
        return true
    }

    /** Movimento do dedo que segura [id]; um segundo dedo em outro item não move o primeiro. */
    fun drag(id: Long, deltaY: Float) {
        if (id != draggingId) return
        dragDistance += deltaY
        settleCandidate()
    }

    /** Solta [id]: a ordem candidata vira a ordem pedida (uma única entrega), e o gesto termina. */
    fun end(id: Long) {
        if (id != draggingId) return
        val order = workingOrder
        cancel()
        if (order != null && order != orderAtStart) {
            droppedOrder = order
            onDrop(order)
        }
    }

    fun cancel(id: Long) {
        if (id == draggingId) cancel()
    }

    /** A lista da ViewModel emitiu [ids]; se já não é a ordem de antes do gesto, ela assume. */
    fun onExercisesChanged(ids: List<Long>) {
        if (droppedOrder != null && ids != orderAtStart) droppedOrder = null
    }

    private fun cancel() {
        draggingId = null
        workingOrder = null
        dragDistance = 0f
        initialOffset = 0f
    }

    /**
     * Quanto a lista precisa rolar para o fantasma não ficar preso na borda; `0` quando não
     * precisa. Só é lido pelo laço de auto-rolagem de [rememberTemplateExerciseDragState].
     */
    internal fun autoScrollDelta(): Float {
        val id = draggingId ?: return 0f
        val item = visibleItem(id) ?: return 0f
        val info = listState.layoutInfo
        val top = initialOffset + dragDistance
        val bottom = top + item.size
        val edge = item.size * 0.6f
        val viewportStart = info.viewportStartOffset.toFloat()
        val viewportEnd = info.viewportEndOffset.toFloat()
        return when {
            bottom > viewportEnd - edge -> (bottom - (viewportEnd - edge)).coerceAtMost(edge) * 0.35f
            top < viewportStart + edge -> (top - (viewportStart + edge)).coerceAtLeast(-edge) * 0.35f
            else -> 0f
        }
    }

    /** A rolagem mudou o que está sob o dedo; recalcular o slot candidato. */
    internal fun onScrolled() = settleCandidate()

    private fun settleCandidate() {
        val id = draggingId ?: return
        val order = workingOrder ?: return
        val dragged = visibleItem(id) ?: return
        val center = initialOffset + dragDistance + dragged.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val key = info.key
            key is Long && key != id && key in order &&
                center >= info.offset && center < info.offset + info.size
        } ?: return
        val from = order.indexOf(id)
        val to = order.indexOf(target.key as Long)
        if (from < 0 || to < 0 || from == to) return
        workingOrder = order.toMutableList().apply {
            removeAt(from)
            add(to, id)
        }
    }

    private fun visibleItem(id: Long): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
}

@Composable
fun rememberTemplateExerciseDragState(
    listState: LazyListState,
    onDrop: (List<Long>) -> Unit
): TemplateExerciseDragState {
    val currentOnDrop by rememberUpdatedState(onDrop)
    val state = remember(listState) { TemplateExerciseDragState(listState) { currentOnDrop(it) } }
    // Auto-rolagem enquanto o dedo segura o item perto de uma borda. O laço só existe durante o
    // gesto e roda por frame, então uma lista longa continua alcançável sem soltar o item.
    LaunchedEffect(state, state.isDragging) {
        while (state.isDragging) {
            withFrameNanos { }
            val delta = state.autoScrollDelta()
            if (delta != 0f) {
                listState.scrollBy(delta)
                state.onScrolled()
            }
        }
    }
    return state
}
