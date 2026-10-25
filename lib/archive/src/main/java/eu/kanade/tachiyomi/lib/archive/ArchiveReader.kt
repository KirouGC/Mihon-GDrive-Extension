package eu.kanade.tachiyomi.lib.archive

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Formatos de archivo que se pueden leer como capítulo. */
enum class ArchiveKind(val code: String) {
    CBZ("cbz"),
    PDF("pdf"),
    ;

    companion object {
        fun of(name: String, mimeType: String = ""): ArchiveKind? {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".cbz") || lower.endsWith(".zip") -> CBZ
                lower.endsWith(".pdf") || mimeType == "application/pdf" -> PDF
                else -> null
            }
        }

        fun fromCode(code: String) = entries.first { it.code == code }
    }
}

val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "jxl", "heic")

fun isImageName(name: String): Boolean {
    val base = name.substringAfterLast('/')
    return !base.startsWith(".") && IMAGE_EXTENSIONS.any { base.endsWith(".$it", ignoreCase = true) }
}

fun imageMime(name: String): String = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "avif" -> "image/avif"
    "bmp" -> "image/bmp"
    "jxl" -> "image/jxl"
    "heic" -> "image/heic"
    else -> "image/jpeg"
}

/**
 * Saca las páginas de un CBZ o PDF que ya está en el dispositivo: las imágenes del ZIP en
 * orden natural, o cada página del PDF renderizada a JPEG con [PdfRenderer].
 */
class ArchiveReader(private val pdfWidth: () -> Int) {

    private val zipEntries = ConcurrentHashMap<String, List<String>>()
    private val openPdfs = LinkedHashMap<String, OpenPdf>()

    private class OpenPdf(val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
        fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    fun pageCount(kind: ArchiveKind, file: File): Int = when (kind) {
        ArchiveKind.CBZ -> imageEntries(file).size
        ArchiveKind.PDF -> withPdf(file) { it.pageCount }
    }

    fun page(kind: ArchiveKind, file: File, index: Int): Pair<ByteArray, String> = when (kind) {
        ArchiveKind.CBZ -> {
            val name = imageEntries(file).getOrNull(index) ?: throw IOException("Página $index inexistente")
            val bytes = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }
            bytes to imageMime(name)
        }
        ArchiveKind.PDF -> withPdf(file) { renderer -> renderPdfPage(renderer, index) } to "image/jpeg"
    }

    /** Olvida lo que se sabe de [file] (por ejemplo, antes de borrarlo de la caché). */
    fun forget(file: File) {
        synchronized(openPdfs) { openPdfs.remove(file.path)?.close() }
        zipEntries.remove(file.path)
    }

    fun closeAll() {
        synchronized(openPdfs) {
            openPdfs.values.forEach { it.close() }
            openPdfs.clear()
        }
        zipEntries.clear()
    }

    private fun imageEntries(file: File): List<String> = zipEntries.getOrPut(file.path) {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && !it.name.startsWith("__MACOSX") && isImageName(it.name) }
                .map { it.name }
                .sortedWith(NaturalOrder)
                .toList()
        }.ifEmpty { throw IOException("El CBZ no contiene imágenes") }
    }

    private fun <T> withPdf(file: File, block: (PdfRenderer) -> T): T {
        val pdf = synchronized(openPdfs) {
            openPdfs.remove(file.path) ?: run {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                OpenPdf(fd, PdfRenderer(fd))
            }.also { opened ->
                openPdfs[file.path] = opened
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

    companion object {
        private const val MAX_OPEN_PDFS = 2
        private const val MAX_PDF_HEIGHT = 16384
    }
}

/** Orden "natural": "Vol 2" va antes que "Vol 10". */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val ca = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val cb = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return ca.size - cb.size
    }
}
