package eu.kanade.tachiyomi.extension.all.googledrive

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Responde a las URL de páginas (http://gdrive.local/<cbz|pdf>/<id>/<página>?rk=<resourceKey>)
 * sin salir a la red: saca la imagen del CBZ o renderiza la página del PDF.
 */
class PageInterceptor(private val files: FileCache) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)

        val segments = request.url.pathSegments
        val kind = FileKind.fromCode(segments[0])
        val ref = DriveRef(segments[1], request.url.queryParameter("rk"))
        val (bytes, mime) = files.page(kind, ref, segments[2].toInt())

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

        fun pageUrl(kind: FileKind, ref: DriveRef, index: Int): HttpUrl =
            "http://$HOST/${kind.code}/${ref.id}/$index".toHttpUrl().newBuilder()
                .apply { ref.resourceKey?.let { addQueryParameter("rk", it) } }
                .build()
    }
}
