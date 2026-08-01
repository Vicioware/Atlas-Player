package com.connorb.omnitv

import android.app.Application
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import android.graphics.drawable.PictureDrawable
import com.caverock.androidsvg.SVG

class OmniTvApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val cacheDirectory = File(cacheDir, "logo_http_cache")
        val cache = Cache(
            directory = cacheDirectory,
            maxSize = 50L * 1024L * 1024L
        )

        val headersInterceptor = Interceptor { chain ->
            val original = chain.request()

            val request = original.newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 11; Android TV) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Safari/537.36"
                )
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .header("Connection", "close")
                .build()

            chain.proceed(request)
        }

        val retryInterceptor = Interceptor { chain ->
            var request = chain.request()
            var response: Response? = null
            var lastException: java.io.IOException? = null

            repeat(3) { attempt ->
                try {
                    response?.close()
                    response = chain.proceed(request)

                    val currentResponse = response!!

                    if (
                        currentResponse.isSuccessful ||
                        currentResponse.code !in listOf(408, 429, 500, 502, 503, 504)
                    ) {
                        return@Interceptor currentResponse
                    }

                    if (attempt < 2) {
                        currentResponse.close()
                        Thread.sleep(350L * (attempt + 1))
                        request = request.newBuilder()
                            .header("Connection", "close")
                            .build()
                    }
                } catch (e: java.io.IOException) {
                    lastException = e

                    if (attempt < 2) {
                        Thread.sleep(350L * (attempt + 1))
                        request = request.newBuilder()
                            .header("Connection", "close")
                            .build()
                    }
                }
            }

            response ?: throw (
                lastException
                    ?: java.io.IOException("No se pudo descargar el logo")
            )
        }

        val client = OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(headersInterceptor)
            .addInterceptor(retryInterceptor)
            .build()

        Glide.get(this)
            .registry
            .apply {
                replace(
                    GlideUrl::class.java,
                    InputStream::class.java,
                    OkHttpUrlLoader.Factory(client)
                )

                append(
                    InputStream::class.java,
                    SVG::class.java,
                    SvgDecoder()
                )

                register(
                    SVG::class.java,
                    PictureDrawable::class.java,
                    SvgDrawableTranscoder()
                )
            }
    }
}
