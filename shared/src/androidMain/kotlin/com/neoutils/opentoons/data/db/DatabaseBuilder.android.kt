package com.neoutils.opentoons.data.db

import android.content.Context
import androidx.room.Room

/** Android: DB no diretório de databases do app. */
fun androidDatabase(context: Context): OpenToonsDatabase {
    val dbFile = context.getDatabasePath("opentoons.db")
    return buildDatabase(
        Room.databaseBuilder<OpenToonsDatabase>(
            context = context.applicationContext,
            name = dbFile.absolutePath,
        ),
    )
}
