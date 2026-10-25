package eu.kanade.tachiyomi.extension.all.nyaa

import android.app.Application
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Prepara la librería nativa de libtorrent.
 *
 * Mihon carga las extensiones con un ClassLoader sin ruta de librerías nativas, así que
 * System.loadLibrary no encuentra libtorrent4j.so. En su lugar se busca el .so (ya extraído
 * por Android, o dentro del propio APK) y se le pasa la ruta a libtorrent4j.
 */
object NativeLoader {
    private const val LIB_NAME = "libtorrent4j.so"
    private const val PROPERTY = "libtorrent4j.jni.path"

    @Volatile
    private var ready = false

    @Synchronized
    fun ensureLoaded(app: Application, packageName: String) {
        if (ready) return
        val lib = findExtracted(app, packageName) ?: extractFromApk(app, packageName)
        System.setProperty(PROPERTY, lib.absolutePath)
        ready = true
    }

    private fun findExtracted(app: Application, packageName: String): File? = runCatching {
        val info = app.packageManager.getApplicationInfo(packageName, 0)
        File(info.nativeLibraryDir, LIB_NAME).takeIf { it.isFile }
    }.getOrNull()

    private fun extractFromApk(app: Application, packageName: String): File {
        val apk = apkCandidates(app, packageName).firstOrNull { it.isFile }
            ?: throw IOException("No se encontró el APK de la extensión para cargar libtorrent")
        ZipFile(apk).use { zip ->
            val entry = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> zip.getEntry("lib/$abi/$LIB_NAME") }
                ?: throw IOException("Esta extensión no incluye libtorrent para ${Build.SUPPORTED_ABIS.joinToString()}")
            val target = File(app.codeCacheDir, "nyaa-native/${apk.lastModified()}-${entry.crc}/$LIB_NAME")
            if (target.isFile && target.length() == entry.size) return target
            target.parentFile?.parentFile?.deleteRecursively()
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            zip.getInputStream(entry).use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(target)) throw IOException("No se pudo preparar libtorrent")
            return target
        }
    }

    private fun apkCandidates(app: Application, packageName: String): List<File> = buildList {
        runCatching { add(File(app.packageManager.getApplicationInfo(packageName, 0).sourceDir)) }
        // Instalación "privada" de Mihon: el APK se guarda dentro de la app.
        add(File(app.filesDir, "exts/$packageName.ext"))
    }
}
