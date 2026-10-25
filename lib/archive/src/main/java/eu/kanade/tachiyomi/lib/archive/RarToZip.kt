package eu.kanade.tachiyomi.lib.archive

import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pasa las imágenes de un CBR (RAR 4 o RAR 5) a un ZIP sin comprimir, para leerlo después
 * como un CBZ normal.
 *
 * Usa libarchive, que ya viene dentro de Mihon (0.17 o más nuevo): la extensión no puede
 * cargar librerías nativas propias, pero sí usar las clases de la app que la carga.
 */
internal class RarToZip(private val i18n: I18n) {

    private class Api(
        val readNew: Method,
        val readSupportFilterAll: Method,
        val readSupportFormatAll: Method,
        val readOpenFileName: Method,
        val readNextHeader: Method,
        val readData: Method,
        val readFree: Method,
        val pathnameUtf8: Method,
        val pathname: Method,
        val filetype: Method,
    )

    private val api: Api? by lazy {
        runCatching {
            val archive = Class.forName("me.zhanghai.android.libarchive.Archive")
            val entry = Class.forName("me.zhanghai.android.libarchive.ArchiveEntry")
            val long = Long::class.javaPrimitiveType!!
            Api(
                readNew = archive.getMethod("readNew"),
                readSupportFilterAll = archive.getMethod("readSupportFilterAll", long),
                readSupportFormatAll = archive.getMethod("readSupportFormatAll", long),
                readOpenFileName = archive.getMethod("readOpenFileName", long, ByteArray::class.java, long),
                readNextHeader = archive.getMethod("readNextHeader", long),
                readData = archive.getMethod("readData", long, ByteBuffer::class.java),
                readFree = archive.getMethod("readFree", long),
                pathnameUtf8 = entry.getMethod("pathnameUtf8", long),
                pathname = entry.getMethod("pathname", long),
                filetype = entry.getMethod("filetype", long),
            )
        }.getOrNull()
    }

    fun convert(source: File, target: File) {
        val api = api ?: throw IOException(
            i18n.t(
                "Para leer CBR hace falta Mihon 0.17 o más nuevo",
                "Reading CBR files needs Mihon 0.17 or newer",
            ),
        )
        val partial = File(target.path + ".part")
        try {
            val archive = api.readNew.call() as Long
            try {
                api.readSupportFilterAll.call(archive)
                api.readSupportFormatAll.call(archive)
                api.readOpenFileName.call(archive, source.absolutePath.toByteArray(), BLOCK_SIZE.toLong())
                var images = 0
                ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                    // Las imágenes ya están comprimidas: no vale la pena volver a comprimirlas.
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    val buffer = ByteBuffer.allocate(BLOCK_SIZE)
                    while (true) {
                        val entry = api.readNextHeader.call(archive) as Long
                        if (entry == 0L) break
                        val name = api.pathnameUtf8.call(entry) as String?
                            ?: (api.pathname.call(entry) as ByteArray?)?.decodeToString()
                            ?: continue
                        if (api.filetype.call(entry) as Int != AE_IFREG || !isImageName(name) || name.startsWith("__MACOSX")) continue
                        zip.putNextEntry(ZipEntry(name))
                        while (true) {
                            buffer.clear()
                            api.readData.call(archive, buffer)
                            buffer.flip()
                            if (!buffer.hasRemaining()) break
                            zip.write(buffer.array(), 0, buffer.remaining())
                        }
                        zip.closeEntry()
                        images++
                    }
                }
                if (images == 0) throw IOException(i18n.t("El CBR no contiene imágenes", "The CBR has no images"))
            } finally {
                runCatching { api.readFree.call(archive) }
            }
            if (!partial.renameTo(target)) throw IOException(i18n.t("No se pudo preparar el CBR", "Could not prepare the CBR"))
        } catch (e: Exception) {
            partial.delete()
            throw if (e is IOException) e else IOException(i18n.t("No se pudo abrir el CBR: ", "Could not open the CBR: ") + e.message, e)
        }
    }

    /** Llama a un método estático y devuelve el error original de libarchive si falla. */
    private fun Method.call(vararg args: Any?): Any? = try {
        invoke(null, *args)
    } catch (e: InvocationTargetException) {
        throw IOException(e.targetException.message ?: e.targetException.toString(), e.targetException)
    }

    companion object {
        private const val BLOCK_SIZE = 64 * 1024
        private const val AE_IFREG = 0x8000
    }
}
