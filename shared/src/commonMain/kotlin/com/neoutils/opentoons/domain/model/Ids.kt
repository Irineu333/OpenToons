package com.neoutils.opentoons.domain.model

/**
 * Identidade de obra alinhada ao ADR-0003: `obra_id = (chave_publicador?, uuid)`.
 *
 * No Marco 1 (offline) `publisherKey` fica **nulo** — não há publicador atribuível e o
 * evento de leitura foi deferido ao Marco 2 (ADR-0009). O `uuid` é estável e gerado no
 * import; quando a rede entrar, a mesma obra pode ganhar `publisherKey` sem trocar o uuid.
 */
data class WorkId(
    val uuid: String,
    val publisherKey: String? = null,
)

/** Física de leitura (ver design D6). */
enum class Layout { PAGED, LONG_STRIP }

/** Direção de avanço no modo paginado (eixo ortogonal ao layout — design D6). */
enum class ReadingDirection { LTR, RTL }

/**
 * Resolve o layout efetivo pela precedência do design D6 / spec reading-experience:
 * override do capítulo, senão override da obra, senão o layout detectado no import.
 * A detecção é preservada separada do override (limpar override restaura a detecção).
 */
fun effectiveLayout(
    chapterOverride: Layout?,
    workOverride: Layout?,
    detected: Layout,
): Layout = chapterOverride ?: workOverride ?: detected
