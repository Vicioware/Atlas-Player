package com.connorb.atlasplayer

import android.app.Application
import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.caverock.androidsvg.SVG
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class OmniTvApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val cache = Cache(
            File(cacheDir, "logo_http_cache"),
            50L * 1024L * 1024L
        )

        val headersInterceptor = Interceptor { chain ->
            val request = chain.request()
                .newBuilder()
                .header("User-Agent", "Atlas Player/1.0 (Android TV)")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*;q=0.8")
                .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.7")
                .build()

            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addNetworkInterceptor(headersInterceptor)
            .build()

        Glide.get(this).registry.apply {
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