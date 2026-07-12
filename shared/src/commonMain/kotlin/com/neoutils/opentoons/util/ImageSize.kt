package com.neoutils.opentoons.util

/** Dimensões de uma imagem em pixels. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Lê as dimensões de uma imagem a partir dos bytes, sem decodificar o bitmap, via
 * [ImageHeaderReader] (task 1.4). Antes eram três `actual` por plataforma que **divergiam**
 * entre si (o iOS aplicava a orientação EXIF; JVM/Android não), gerando geometria e detecção
 * de layout inconsistentes para o mesmo arquivo. Agora é uma leitura de header em `commonMain`,
 * idêntica nas três plataformas. Retorna `null` para formato desconhecido/header truncado.
 */
fun readImageSize(bytes: ByteArray): ImageSize? = ImageHeaderReader.readSize(bytes)
