package com.connorb.atlasplayer

import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.InputStream

class SvgDecoder : ResourceDecoder<InputStream, SVG> {

    override fun handles(
        source: InputStream,
        options: Options
    ): Boolean = true

    override fun decode(
        source: InputStream,
        width: Int,
        height: Int,
        options: Options
    ): Resource<SVG>? {
        return try {
            val svg = SVG.getFromInputStream(source)

            if (width > 0) {
                svg.documentWidth = width.toFloat()
            }

            if (height > 0) {
                svg.documentHeight = height.toFloat()
            }

            SimpleResource(svg)
        } catch (_: SVGParseException) {
            null
        }
    }
}

class SvgDrawableTranscoder :
    ResourceTranscoder<SVG, PictureDrawable> {

    override fun transcode(
        toTranscode: Resource<SVG>,
        options: Options
    ): Resource<PictureDrawable> {
        val picture = toTranscode.get().renderToPicture()
        return SimpleResource(PictureDrawable(picture))
    }
}
