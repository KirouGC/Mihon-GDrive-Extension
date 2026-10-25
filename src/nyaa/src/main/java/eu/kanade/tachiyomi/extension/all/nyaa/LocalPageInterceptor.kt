package eu.kanade.tachiyomi.extension.all.nyaa

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Responde a las URL de páginas sin salir a la red:
 * - http://nyaa.local/z/<torrent>/<archivo>/<página>: imagen de un CBZ, leída por rangos
 * - http://nyaa.local/a/<torrent>/<archivo>/<página>: página de un PDF
 * - http://nyaa.local/i/<torrent>/<archivo>: imagen suelta del torrent
 */
class LocalPageInterceptor(
    private val serve: (kind: String, torrentId: String, fileIndex: Int, page: Int?) -> Pair<ByteArray, String>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)

        val segments = request.url.pathSegments
        val page = segments.getOrNull(3)?.toInt()
        val (bytes, mime) = serve(segments[0], segments[1], segments[2].toInt(), page)

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(mime.toMediaType()))
            .build()
    }

    companion object {
        private const val HOST = "nyaa.local"

        const val ZIP = "z"
        const val PDF = "a"
        const val IMAGE = "i"

        fun pageUrl(kind: String, torrentId: String, fileIndex: Int, page: Int) = "http://$HOST/$kind/$torrentId/$fileIndex/$page"

        fun imageUrl(torrentId: String, fileIndex: Int) = "http://$HOST/$IMAGE/$torrentId/$fileIndex"
    }
}
