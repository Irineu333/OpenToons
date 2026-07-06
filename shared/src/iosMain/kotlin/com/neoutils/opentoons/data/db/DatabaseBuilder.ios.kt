package com.neoutils.opentoons.data.db

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** iOS: DB no diretório Documents do app. */
@OptIn(ExperimentalForeignApi::class)
fun iosDatabase(): OpenToonsDatabase {
    val documents = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(documents?.URLByAppendingPathComponent("opentoons.db")?.path) {
        "Não foi possível resolver o diretório Documents do iOS"
    }
    return buildDatabase(Room.databaseBuilder<OpenToonsDatabase>(name = path))
}
