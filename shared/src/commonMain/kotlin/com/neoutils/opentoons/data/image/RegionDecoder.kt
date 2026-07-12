package com.neoutils.opentoons.data.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Limite conservador de textura da GPU (menor entre as plataformas-alvo, ~4096 px por
 * dimensão). Nenhum bitmap de tile deve exceder isso em qualquer dimensão (invariante I4,
 * task 5.9) — os `actual` reduzem a largura-alvo até caber.
 */
const val MAX_TEXTURE_PX: Int = 4096

/**
 * Decodifica **por região** (design D5, task 5.1): devolve a faixa vertical
 * `[srcTop, srcTop+srcHeight)` da imagem codificada em [bytes], num bitmap de largura
 * [targetWidth]. Regras:
 *
 * - **Nunca acima da largura nativa** (task 5.6): o upscale é do render, não do decode.
 * - `srcHeight <= 0` decodifica a **página inteira** com sub-sampling — é o caso de geometria
 *   desconhecida e o fallback quando a região falha num formato não suportado (task 5.5).
 * - Nenhuma dimensão do bitmap excede [MAX_TEXTURE_PX] (task 5.9).
 *
 * Retorna `null` se a imagem não for decodificável. Implementações: `BitmapRegionDecoder`
 * (Android), `ImageReader.setSourceRegion` (JVM), Skia subset (iOS — fallback de decode
 * inteiro; região nativa via CoreGraphics é follow-up).
 */
expect fun decodeRegion(
    bytes: ByteArray,
    srcTop: Int,
    srcHeight: Int,
    targetWidth: Int,
): ImageBitmap?
