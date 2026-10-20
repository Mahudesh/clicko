package com.mahudesh.tvremote.tv

import java.io.Closeable

/** One pairing attempt with a TV, whatever protocol it speaks. */
interface Pairer : Closeable {
    /** Characters in the code the TV will show (6 on v2 TVs, usually 4 on v1). */
    val codeLength: Int

    /** Connects and asks the TV to show its code. Returns the TV's own name if it reports one. */
    fun start(): String?

    /** Sends the code the user typed. Throws [PairingException] if it's wrong. */
    fun finish(code: String)
}
