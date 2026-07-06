package org.opentoons.poc7.probe

/** `actual` host (JVM) — aferição da régua: a MESMA computação determinística no DEV. */
actual fun platformName(): String =
    "jvm/${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
