package com.neoutils.opentoons.util

/**
 * Gera a **thumbnail de capa** a partir dos bytes de uma imagem — página da obra ou imagem externa
 * escolhida na revisão (design D5). É o único ponto que reintroduz encode de imagem por plataforma
 * (`expect/actual`) — e **só para a capa** (uma imagem por obra/adição), nunca no caminho quente das
 * páginas: nenhuma página é transcodificada (seguem STORED cru).
 *
 * O formato de saída **não é contratual**: cada alvo usa WebP quando fecha e cai para PNG/JPEG da
 * plataforma quando não (D5). O Coil decodifica por conteúdo, não pela extensão, então o arquivo
 * pode se chamar `cover.webp` de qualquer forma.
 */
expect object CoverEncoder {
    /**
     * Reduz [source] para uma thumbnail com lado maior ≤ [maxDimension] e devolve os bytes
     * codificados, ou `null` se a imagem não decodificar.
     */
    fun encodeThumbnail(source: ByteArray, maxDimension: Int = 512): ByteArray?

    /**
     * `true` se [source] decodifica como imagem. Existe para validar bytes que serão gravados
     * **sem reencode** (capa externa já reduzida) — decodificar de novo só para descartar o
     * resultado seria desperdício, mas gravar sem checar deixaria a escolha falhar em silêncio.
     */
    fun isDecodable(source: ByteArray): Boolean
}
