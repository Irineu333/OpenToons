package com.neoutils.opentoons

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform