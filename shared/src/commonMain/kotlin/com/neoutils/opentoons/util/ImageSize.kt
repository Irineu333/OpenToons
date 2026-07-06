package com.neoutils.opentoons.util

/** Dimensões de uma imagem em pixels. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Lê as dimensões de uma imagem a partir dos bytes, sem manter o bitmap decodificado
 * (usa leitura de header/bounds em cada plataforma). Retorna `null` se não decodificável.
 * Base da heurística de layout (task 5.1).
 */
expect fun readImageSize(bytes: ByteArray): ImageSize?
