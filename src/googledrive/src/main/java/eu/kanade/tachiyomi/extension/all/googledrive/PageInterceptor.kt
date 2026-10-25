package eu.kanade.tachiyomi.extension.all.googledrive

import eu.kanade.tachiyomi.lib.archive.ArchiveKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Responde a las URL de páginas (http://gdrive.local/<cbz|pdf>/<id>/<página>?rk=<resourceKey>,
 * o http://gdrive.local/img/<id> para una imagen suelta)
 * sin salir a la red: saca la imagen del CBZ o renderiza la página del PDF.
 */
class PageInterceptor(private val files: FileCache) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)

        val segments = request.url.pathSegments
        val ref = DriveRef(segments[1], request.url.queryParameter("rk"))
        val (bytes, mime) = if (segments[0] == IMAGE) {
            files.image(ref)
        } else {
            files.page(ArchiveKind.fromCode(segments[0]), ref, segments[2].toInt())
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(mime.toMediaType()))
            .build()
    }

    companion object {
        private const val HOST = "gdrive.local"
        private const val IMAGE = "img"

        /** Una imagen suelta de Drive (página de una carpeta de imágenes). */
        fun imageUrl(ref: DriveRef): HttpUrl =
            "http://$HOST/$IMAGE/${ref.id}".toHttpUrl().newBuilder()
                .apply { ref.resourceKey?.let { addQueryParameter("rk", it) } }
                .build()

        fun pageUrl(kind: ArchiveKind, ref: DriveRef, index: Int): HttpUrl =
            "http://$HOST/${kind.code}/${ref.id}/$index".toHttpUrl().newBuilder()
                .apply { ref.resourceKey?.let { addQueryParameter("rk", it) } }
                .build()
    }
}
