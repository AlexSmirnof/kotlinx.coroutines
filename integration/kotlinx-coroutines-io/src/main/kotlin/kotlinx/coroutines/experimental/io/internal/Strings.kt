package kotlinx.coroutines.experimental.io.internal

import java.nio.*
import java.nio.charset.*

/**
 * Decodes all the bytes to ASCII characters until end of buffer applying every character to [consumer]
 * It stops processing if a non-ascii character encountered and returns `false`
 * @return `false` if a non-ascii character encountered or `true` if all bytes were processed
 */
internal inline fun ByteBuffer.decodeASCII(consumer: (Char) -> Unit): Boolean {
    while (hasRemaining()) {
        val v = get().toInt() and 0xff
        when {
            v and 0x80 == 0 -> consumer(v.toChar())
            else -> {
                // unicode
                position(position() - 1)
                return false
            }
        }
    }

    return true
}

/**
 * Decodes all the bytes to utf8 applying every character on [consumer] until or consumer return `false`.
 * If a consumer returned false then a character will be pushed back (including all surrogates will be pushed back as well)
 * and [decodeUTF8] returns 0
 * @return number of bytes required to decode incomplete utf8 character or 0 if all bytes were processed
 */
internal inline fun ByteBuffer.decodeUTF8(consumer: (Char) -> Boolean): Int {
    var byteCount = 0
    var value = 0

    while (hasRemaining()) {
        val v = get().toInt() and 0xff
        when {
            v and 0x80 == 0 -> {
                if (byteCount != 0) throw MalformedInputException(0)
                if (!consumer(v.toChar())) {
                    position(position() - 1)
                    return 0
                }
            }
            byteCount == 0 -> {
                // first unicode byte

                var mask = 0x80
                value = v

                for (i in 1..6) { // TODO do we support 6 bytes unicode?
                    if (value and mask != 0) {
                        value = value and mask.inv()
                        mask = mask shr 1
                        byteCount++
                    } else {
                        break
                    }
                }

                byteCount--

                if (byteCount > remaining()) {
                    position(position() - 1) // return one byte back
                    return byteCount + 1
                }
            }
            else -> {
                // trailing unicode byte
                value = (value shl 6) or (v and 0x7f)
                byteCount--

                if (byteCount == 0) {
                    if (isBmpCodePoint(value)) {
                        consumer(value.toChar())
                    } else if (!isValidCodePoint(value)) {
                        throw IllegalArgumentException("Malformed code-point ${Integer.toHexString(value)} found")
                    } else {
                        consumer(highSurrogate(value).toChar())
                        consumer(lowSurrogate(value).toChar())
                    }

                    value = 0
                }
            }
        }
    }

    return 0
}

private const val MaxCodePoint = 0X10ffff
private const val MinLowSurrogate = 0xdc00
private const val MinHighSurrogate = 0xd800
private const val MinSupplementary = 0x10000
private const val HighSurrogateMagic = MinHighSurrogate - (MinSupplementary ushr 10)

private fun isBmpCodePoint(cp: Int) = cp ushr 16 == 0
private fun isValidCodePoint(codePoint: Int) = codePoint <= MaxCodePoint
private fun lowSurrogate(cp: Int) = cp and 0x3ff + MinLowSurrogate
private fun highSurrogate(cp: Int) = (cp ushr 10) + HighSurrogateMagic
