package eu.kanade.tachiyomi.extension.all.googledrive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Descarga los CBZ/PDF a la caché del dispositivo y extrae de ellos las páginas.
 *
 * Mihon pide cada página como una imagen por HTTP, así que [PageInterceptor] responde a
 * las URL de páginas con los bytes que devuelve esta clase.
 */
class FileCache(
    private val api: DriveApi,
    private val dir: File,
    private val maxBytes: () -> Long,
    private val pdfWidth: () -> Int,
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val zipEntries = ConcurrentHashMap<String, List<String>>()
    private val openPdfs = LinkedHashMap<String, OpenPdf>()

    private class OpenPdf(val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    /** Número de páginas del archivo; lo descarga si hace falta. */
    fun pageCount(kind: FileKind, ref: DriveRef): Int = when (kind) {
        FileKind.CBZ -> imageEntries(ref).size
        FileKind.PDF -> withPdf(ref) { it.pageCount }
    }

    fun page(kind: FileKind, ref: DriveRef, index: Int): Pair<ByteArray, String> = when (kind) {
        FileKind.CBZ -> {
            val name = imageEntries(ref).getOrNull(index) ?: throw IOException("Página $index inexistente")
            val bytes = ZipFile(file(ref)).use { zip -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }
            bytes to mimeFor(name)
        }
        FileKind.PDF -> withPdf(ref) { renderer -> renderPdfPage(renderer, index) } to "image/jpeg"
    }

    private fun imageEntries(ref: DriveRef): List<String> = zipEntries.getOrPut(ref.id) {
        ZipFile(file(ref)).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .filter { name ->
                    val base = name.substringAfterLast('/')
                    !base.startsWith(".") && !name.startsWith("__MACOSX") &&
                        IMAGE_EXTENSIONS.any { base.endsWith(".$it", ignoreCase = true) }
                }
                .sortedWith(NaturalOrder)
                .toList()
        }.ifEmpty { throw IOException("El CBZ no contiene imágenes") }
    }

    private fun <T> withPdf(ref: DriveRef, block: (PdfRenderer) -> T): T {
        val pdf = synchronized(openPdfs) {
            openPdfs.remove(ref.id) ?: run {
                val fd = ParcelFileDescriptor.open(file(ref), ParcelFileDescriptor.MODE_READ_ONLY)
                OpenPdf(fd, PdfRenderer(fd))
            }.also { opened ->
                openPdfs[ref.id] = opened
                while (openPdfs.size > MAX_OPEN_PDFS) {
                    val eldest = openPdfs.keys.first()
                    openPdfs.remove(eldest)?.close()
                }
            }
        }
        // PdfRenderer solo admite una página abierta a la vez.
        return synchronized(pdf.renderer) { block(pdf.renderer) }
    }

    private fun renderPdfPage(renderer: PdfRenderer, index: Int): ByteArray {
        if (index !in 0 until renderer.pageCount) throw IOException("Página $index inexistente")
        renderer.openPage(index).use { page ->
            val width = pdfWidth()
            val scale = width.toFloat() / page.width
            val height = (page.height * scale).toInt().coerceIn(1, MAX_PDF_HEIGHT)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Devuelve el archivo local, descargándolo una sola vez aunque lo pidan varias páginas a la vez. */
    private fun file(ref: DriveRef): File {
        val target = File(dir, ref.id)
        synchronized(locks.getOrPut(ref.id) { Any() }) {
            if (target.exists()) {
                target.setLastModified(System.currentTimeMillis())
                return target
            }
            dir.mkdirs()
            val partial = File(dir, "${ref.id}.part")
            api.download(ref).use { response ->
                partial.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (!partial.renameTo(target)) throw IOException("No se pudo guardar ${ref.id} en la caché")
            trim(keep = target)
            return target
        }
    }

    private fun trim(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        val limit = maxBytes()
        for (old in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            if (old == keep) continue
            synchronized(openPdfs) { openPdfs.remove(old.name)?.close() }
            zipEntries.remove(old.name)
            total -= old.length()
            old.delete()
        }
    }

    fun clear() {
        synchronized(openPdfs) {
            openPdfs.values.forEach { it.close() }
            openPdfs.clear()
        }
        zipEntries.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "jxl" -> "image/jxl"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    companion object {
        private const val MAX_OPEN_PDFS = 2
        private const val MAX_PDF_HEIGHT = 16384
    }
}
