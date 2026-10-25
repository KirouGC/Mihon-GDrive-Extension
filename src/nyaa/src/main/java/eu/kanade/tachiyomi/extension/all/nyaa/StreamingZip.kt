package eu.kanade.tachiyomi.extension.all.nyaa

import eu.kanade.tachiyomi.lib.archive.NaturalOrder
import eu.kanade.tachiyomi.lib.archive.isImageName
import java.io.IOException
import java.util.zip.Inflater

/**
 * Lector de ZIP/CBZ que solo pide los bytes que necesita: primero el directorio central
 * (al final del archivo) y luego cada imagen por separado. Así se puede mostrar la primera
 * página de un CBZ sin esperar a que se baje entero del torrent.
 */
class StreamingZip private constructor(val entries: List<Entry>) {

    data class Entry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localOffset: Long)

    /** Rango aproximado de bytes de una entrada (para pedir sus piezas por adelantado). */
    fun approximateRange(entry: Entry): LongRange =
        entry.localOffset until entry.localOffset + LOCAL_HEADER + MAX_NAME_EXTRA + entry.compressedSize

    fun read(entry: Entry, read: (offset: Long, length: Int) -> ByteArray): ByteArray {
        val header = read(entry.localOffset, LOCAL_HEADER)
        if (header.u32(0) != LOCAL_SIG) throw IOException("CBZ dañado: cabecera local inválida")
        val dataStart = entry.localOffset + LOCAL_HEADER + header.u16(26) + header.u16(28)
        val data = read(dataStart, entry.compressedSize.toIntExact())
        return when (entry.method) {
            0 -> data
            8 -> Inflater(true).run {
                try {
                    setInput(data)
                    val out = ByteArray(entry.size.toIntExact())
                    var done = 0
                    while (done < out.size && !finished()) {
                        val n = inflate(out, done, out.size - done)
                        if (n == 0 && (needsInput() || needsDictionary())) break
                        done += n
                    }
                    out
                } finally {
                    end()
                }
            }
            else -> throw IOException("Compresión ZIP no soportada (método ${entry.method})")
        }
    }

    companion object {
        private const val LOCAL_SIG = 0x04034b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val LOCAL_HEADER = 30
        private const val MAX_NAME_EXTRA = 1024
        private const val EOCD_MAX = 22 + 65535

        /** Lee el directorio central y devuelve las imágenes en el mismo orden que ArchiveReader. */
        fun open(size: Long, read: (offset: Long, length: Int) -> ByteArray): StreamingZip {
            val tailLength = minOf(size, EOCD_MAX.toLong()).toInt()
            val tailStart = size - tailLength
            val tail = read(tailStart, tailLength)
            val eocd = (tail.size - 22 downTo 0).firstOrNull { tail.u32(it) == EOCD_SIG }
                ?: throw IOException("CBZ dañado: no se encontró el directorio central")

            var count = tail.u16(eocd + 10).toLong()
            var cdSize = tail.u32(eocd + 12)
            var cdOffset = tail.u32(eocd + 16)
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || count == 0xFFFFL) {
                val locator = eocd - 20
                if (locator < 0 || tail.u32(locator) != ZIP64_LOCATOR_SIG) throw IOException("ZIP64 sin localizador")
                val zip64 = read(tail.u64(locator + 8), 56)
                if (zip64.u32(0) != ZIP64_EOCD_SIG) throw IOException("ZIP64 dañado")
                count = zip64.u64(32)
                cdSize = zip64.u64(40)
                cdOffset = zip64.u64(48)
            }

            val cd = read(cdOffset, cdSize.toIntExact())
            val entries = ArrayList<Entry>(count.toInt())
            var p = 0
            while (p + 46 <= cd.size && cd.u32(p) == CENTRAL_SIG) {
                val method = cd.u16(p + 10)
                var compressed = cd.u32(p + 20)
                var uncompressed = cd.u32(p + 24)
                val nameLength = cd.u16(p + 28)
                val extraLength = cd.u16(p + 30)
                val commentLength = cd.u16(p + 32)
                var offset = cd.u32(p + 42)
                val name = String(cd, p + 46, nameLength, Charsets.UTF_8)

                // Campo extra ZIP64: solo trae los valores que en la cabecera valen 0xFFFFFFFF.
                var e = p + 46 + nameLength
                val extraEnd = e + extraLength
                while (e + 4 <= extraEnd) {
                    val id = cd.u16(e)
                    val len = cd.u16(e + 2)
                    if (id == 0x0001) {
                        var q = e + 4
                        if (uncompressed == 0xFFFFFFFFL) { uncompressed = cd.u64(q); q += 8 }
                        if (compressed == 0xFFFFFFFFL) { compressed = cd.u64(q); q += 8 }
                        if (offset == 0xFFFFFFFFL) offset = cd.u64(q)
                    }
                    e += 4 + len
                }

                if (!name.endsWith("/") && !name.startsWith("__MACOSX") && isImageName(name)) {
                    entries += Entry(name, method, compressed, uncompressed, offset)
                }
                p += 46 + nameLength + extraLength + commentLength
            }
            if (entries.isEmpty()) throw IOException("El CBZ no contiene imágenes")
            return StreamingZip(entries.sortedWith(compareBy(NaturalOrder) { it.name }))
        }

        private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.u32(i: Int) = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)

        private fun ByteArray.u64(i: Int) = u32(i) or (u32(i + 4) shl 32)

        private fun Long.toIntExact(): Int {
            if (this > Int.MAX_VALUE) throw IOException("Entrada ZIP demasiado grande")
            return toInt()
        }
    }
}
