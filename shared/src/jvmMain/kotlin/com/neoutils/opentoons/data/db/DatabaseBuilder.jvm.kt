package com.neoutils.opentoons.data.db

import androidx.room.Room
import java.io.File

/** Desktop: DB no diretório de usuário (`~/.opentoons`). */
fun desktopDatabase(): OpenToonsDatabase {
    val dir = File(System.getProperty("user.home"), ".opentoons").apply { mkdirs() }
    val dbFile = File(dir, "opentoons.db")
    return buildDatabase(Room.databaseBuilder<OpenToonsDatabase>(name = dbFile.absolutePath))
}
