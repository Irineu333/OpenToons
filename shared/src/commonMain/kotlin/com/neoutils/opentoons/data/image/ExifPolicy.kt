package com.neoutils.opentoons.data.image

/**
 * Política de orientação EXIF do leitor (design, Open Question / task 1.2).
 *
 * **Decisão: ignorar a orientação EXIF** — a geometria de uma página é a do *bitstream*
 * (largura/altura como codificadas), não a "visual" após aplicar o `Orientation` do APP1.
 *
 * Porquê: o sniff comum ([com.neoutils.opentoons.util.ImageHeaderReader]) lê as dimensões do
 * SOF do JPEG, que são as do bitstream. JVM (`ImageIO`) e Android (`BitmapFactory` com
 * `inJustDecodeBounds`) já reportavam o bitstream; só o iOS (`UIImage`) aplicava a orientação
 * e, num JPEG `orientation=6`, devolvia largura/altura trocadas — a divergência que motivou
 * mover a geometria para `commonMain`. Ignorar a orientação torna as três plataformas
 * idênticas por construção.
 *
 * **Contrato com o render:** para geometria e pixel não divergirem, o decoder de tile
 * ([decodeRegion]) e o render do Coil 3 devem entregar o pixel **na orientação do bitstream**,
 * sem auto-rotacionar por EXIF. A confirmação em runtime por plataforma (Coil 3 em Android,
 * iOS e Desktop) é verificação manual — os webtoons-alvo raramente carregam EXIF de rotação,
 * mas um `.opz` de terceiro pode. Se algum decoder auto-rotacionar, a correção é desabilitar
 * a rotação por EXIF nele, não mudar esta política.
 */
object ExifPolicy {
    /** `false`: a orientação EXIF é ignorada; a geometria é a do bitstream. */
    const val APPLY_ORIENTATION: Boolean = false
}
