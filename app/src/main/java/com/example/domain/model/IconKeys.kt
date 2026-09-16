package com.example.domain.model

/**
 * Chaves de ícone que o domínio pode carregar como texto (T19.7B).
 *
 * Conquistas, eventos da linha do tempo e destaques da Home nasceram com um emoji no campo
 * `icon: String` — um ícone funcional desenhado pela fonte do sistema, diferente em cada
 * aparelho e fora do design system. O domínio continua sem depender de Compose: ele guarda a
 * **chave** (o nome Material do ícone), e a apresentação a resolve em `semanticIcon` (ui). Uma chave
 * que este APK não conhece cai num ícone neutro, nunca em texto cru.
 */
object IconKeys {
    const val TROPHY = "emoji_events"
    const val FIRE = "local_fire_department"
    const val MEDAL = "military_tech"
    const val SCALE = "monitor_weight"
    const val WORKOUT = "fitness_center"
    const val TREND_UP = "trending_up"
    const val TREND_DOWN = "trending_down"
    const val RULER = "straighten"
    const val BOLT = "bolt"
    const val ROCKET = "rocket_launch"
    const val PLACE = "place"
    const val LOCK = "lock"
}
