package com.example.unicitywallet.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.io.File

/**
 * Manages icon caching for token/coin icons from URLs
 * Uses Glide for efficient image loading and caching
 */
class IconCacheManager(private val context: Context) {

    companion object {
        private const val ICON_CACHE_DIR = "token_icons"

        @Volatile
        private var INSTANCE: IconCacheManager? = null

        fun getInstance(context: Context): IconCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IconCacheManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val cacheDir: File = File(context.cacheDir, ICON_CACHE_DIR).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    /**
     * Load icon from URL into ImageView with caching
     */
    fun loadIcon(
        url: String,
        imageView: ImageView,
        placeholder: Int? = null,
        error: Int? = null
    ) {
        var request = Glide.with(context)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL)

        placeholder?.let { request = request.placeholder(it) }
        error?.let { request = request.error(it) }

        request.into(imageView)
    }

    /**
     * Preload icon from URL into cache
     */
    fun preloadIcon(url: String) {
        Glide.with(context)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .preload()
    }

    /**
     * Load icon as bitmap with callback
     */
    fun loadIconBitmap(
        url: String,
        onLoaded: (Bitmap) -> Unit,
        onError: (() -> Unit)? = null
    ) {
        Glide.with(context)
            .asBitmap()
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    onLoaded(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Cleanup if needed
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    onError?.invoke()
                }
            })
    }

    /**
     * Clear all cached icons
     */
    fun clearCache() {
        Glide.get(context).clearDiskCache()
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    /**
     * Get cache size in bytes
     */
    fun getCacheSize(): Long {
        return cacheDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }
}
