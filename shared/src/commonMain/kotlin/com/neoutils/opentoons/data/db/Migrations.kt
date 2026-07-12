package com.neoutils.opentoons.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Migração v5→v6 (task 4.1): `progress` ganha `fractionWithinPage` para carregar a posição
 * independente de layout do long strip `(pageIndex, fractionWithinPage)` (design D4).
 *
 * É uma migração **preservadora** — diferente do `fallbackToDestructiveMigration` que apagaria a
 * biblioteca inteira por uma coluna. As linhas antigas ganham `fractionWithinPage = 0`; o
 * progresso long strip legado (que só tinha `scrollFraction`) é convertido por aproximação em
 * runtime, no open (`pageIndex ≈ scrollFraction × pageCount`, task 4.2), pois a conversão exata
 * exigiria a geometria do capítulo, indisponível aqui.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE progress ADD COLUMN fractionWithinPage REAL NOT NULL DEFAULT 0",
        )
    }
}
