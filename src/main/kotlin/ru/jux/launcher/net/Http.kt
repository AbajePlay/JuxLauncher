package ru.jux.launcher.net

import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.jux.launcher.core.Log
import ru.jux.launcher.core.Paths
import ru.jux.launcher.launch.ArgumentBuilder
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

object Http {

    const val USER_AGENT = "AbajePlay/JuxLauncher/${ArgumentBuilder.LAUNCHER_VERSION} (juxmc.ru)"

    val client: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        val metadataCache = runCatching {
            val dir = Paths.cache.resolve("http").also { it.createDirectories() }
            Cache(dir.toFile(), 32L * 1024 * 1024)
        }.getOrNull()

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .apply { metadataCache?.let { cache(it) } }
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }
            .build()
    }

    private val metadataClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun request(url: String): Request = Request.Builder().url(url).build()

    fun getString(url: String): String = execute(request(url))

    fun postJson(url: String, json: String): String =
        execute(Request.Builder().url(url).post(json.toRequestBody(JSON)).build())

    fun postForm(url: String, fields: Map<String, String>): String {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return execute(Request.Builder().url(url).post(body).build(), uploadClient)
    }

    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()
    }

    private val JSON = "application/json".toMediaType()

    private fun execute(request: Request, via: OkHttpClient = metadataClient): String {
        via.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${request.url}")
            return response.body?.string() ?: throw IOException("empty body for ${request.url}")
        }
    }

    fun shutdown() {
        runCatching {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        }.onFailure { Log.debug("http shutdown: ${it.message}") }
    }
}
