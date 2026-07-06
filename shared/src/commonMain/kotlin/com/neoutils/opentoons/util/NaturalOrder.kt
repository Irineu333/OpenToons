package com.neoutils.opentoons.util

/**
 * Ordenação natural de nomes de entrada (spec content-import, task 3.5): trata sequências
 * de dígitos como números, então `pag2.jpg` vem antes de `pag10.jpg`. Case-insensitive,
 * estável, sem alocar por caractere no caminho comum.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // pula zeros à esquerda
                var si = i
                var sj = j
                while (si < a.length && a[si] == '0') si++
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                var ej = sj
                while (ei < a.length && a[ei].isDigit()) ei++
                while (ej < b.length && b[ej].isDigit()) ej++
                val lenA = ei - si
                val lenB = ej - sj
                if (lenA != lenB) return lenA - lenB
                var k = 0
                while (k < lenA) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                    k++
                }
                i = ei
                j = ej
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la - lb
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
