package org.opentoons.poc7.probe

import platform.UIKit.UIDevice

/**
 * `actual` iOS — lê o device real via UIKit. Além de nomear a plataforma, prova que o
 * cinterop Kotlin/Native ↔ SDK Apple (Foundation/UIKit) fecha no device físico: se isto
 * roda, `platform.UIKit` está linkado e chamável a partir de Kotlin.
 */
actual fun platformName(): String {
    val d = UIDevice.currentDevice
    return "iOS/${d.systemName}/${d.systemVersion}/${d.model}"
}
