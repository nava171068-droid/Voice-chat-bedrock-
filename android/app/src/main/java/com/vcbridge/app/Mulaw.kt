package com.vcbridge.app

/** G.711 mu-law: 16-bit PCM <-> 8-bit. Halves the bandwidth vs raw PCM. */
object Mulaw {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encode(pcm: Short): Byte {
        var sample = pcm.toInt()
        val sign = (sample shr 8) and 0x80
        if (sign != 0) sample = -sample
        if (sample > CLIP) sample = CLIP
        sample += BIAS
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (sample and mask) == 0) {
            exp--
            mask = mask shr 1
        }
        val mant = (sample shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    fun decode(b: Byte): Short {
        val u = b.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exp = (u shr 4) and 0x07
        val mant = u and 0x0F
        var s = ((mant shl 3) + BIAS) shl exp
        s -= BIAS
        return (if (sign != 0) -s else s).toShort()
    }
}
