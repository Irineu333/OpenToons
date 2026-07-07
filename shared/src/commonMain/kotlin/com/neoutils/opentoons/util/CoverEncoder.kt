package com.neoutils.opentoons.util

/**
 * Gera a **thumbnail de capa** a partir dos bytes de uma página (design D5, task 3.1). É o
 * único ponto que reintroduz encode de imagem por plataforma (`expect/actual`) — e **só para a
 * capa** (uma imagem por obra/adição), nunca no caminho quente das páginas: nenhuma página é
 * transcodificada (seguem STORED cru, task 3.4).
 *
 * A `cover.webp` é **cache derivado, regenerável** — o formato **não é contratual**: cada alvo
 * usa WebP quando fecha e cai para PNG/JPEG da plataforma quando não (D5). O Coil decodifica
 * por conteúdo, não pela extensão, então o arquivo pode se chamar `cover.webp` de qualquer forma.
 */
expect object CoverEncoder {
    /**
     * Reduz [source] para uma thumbnail com lado maior ≤ [maxDimension] e devolve os bytes
     * codificados, ou `null` se a página não decodificar.
     */
    fun encodeThumbnail(source: ByteArray, maxDimension: Int = 512): ByteArray?
}
