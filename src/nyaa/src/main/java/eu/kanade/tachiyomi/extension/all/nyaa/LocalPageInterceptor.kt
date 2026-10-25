package eu.kanade.tachiyomi.extension.all.nyaa

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Responde a las URL de páginas sin salir a la red:
 * - http://nyaa.local/a/<torrent>/<archivo>/<página>: página de un CBZ/PDF del torrent
 * - http://nyaa.local/i/<torrent>/<archivo>: imagen suelta del torrent
 */
class LocalPageInterceptor(
    private val serve: (torrentId: String, fileIndex: Int, page: Int?) -> Pair<ByteArray, String>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)

        val segments = request.url.pathSegments
        val page = if (segments[0] == "a") segments[3].toInt() else null
        val (bytes, mime) = serve(segments[1], segments[2].toInt(), page)

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

        fun archivePageUrl(torrentId: String, fileIndex: Int, page: Int) = "http://$HOST/a/$torrentId/$fileIndex/$page"

        fun imageUrl(torrentId: String, fileIndex: Int) = "http://$HOST/i/$torrentId/$fileIndex"
    }
}
