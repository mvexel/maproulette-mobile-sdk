package org.maproulette.sdk

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A transport implementation must honor cancellation and must not forward headers across redirects. */
fun interface Transport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

// No data-class toString: headers and response bodies can contain credentials.
enum class HttpMethod { GET, PUT }
class HttpRequest(
    val url: String, val headers: Map<String, String>,
    val method: HttpMethod = HttpMethod.GET, val body: String? = null,
)

class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

/** Reuse one transport per application. Requests have a 30-second overall deadline, including
 * waiting for the server response. close() cancels outstanding calls and releases its resources. */
class OkHttpTransport : Transport, Closeable {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun execute(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val httpRequest = Request.Builder()
                .url(request.url)
                .method(request.method.name, request.body?.toRequestBody())
                .apply {
                    request.headers.forEach { (name, value) -> header(name, value) }
                }
                .build()
            val call = client.newCall(httpRequest)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(MapRouletteException(ErrorKind.NETWORK)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            HttpResponse(
                                status = it.code,
                                headers = it.headers.toMultimap().mapValues { entry ->
                                    entry.value.joinToString(",")
                                },
                                body = it.body?.string() ?: "",
                            )
                        }
                    }.recoverCatching {
                        throw MapRouletteException(ErrorKind.NETWORK)
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
