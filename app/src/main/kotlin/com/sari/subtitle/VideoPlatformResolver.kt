package com.sari.subtitle

import android.net.Uri

enum class VideoPlatform {
    YOUTUBE, TIKTOK, INSTAGRAM, FACEBOOK, X, VIMEO, TWITCH, DIRECT, UNKNOWN
}

data class ResolvedVideoLink(
    val uri: Uri,
    val platform: VideoPlatform,
    val isDirectMedia: Boolean,
    val host: String
)

object VideoPlatformResolver {
    private val directExtensions = setOf("mp4", "m4v", "webm", "m3u8", "mpd", "mp3", "m4a", "wav", "ogg")

    fun resolve(raw: String): ResolvedVideoLink? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        if (scheme !in setOf("http", "https") || host.isBlank()) return null
        val platform = when {
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> VideoPlatform.YOUTUBE
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> VideoPlatform.TIKTOK
            host == "instagram.com" || host.endsWith(".instagram.com") -> VideoPlatform.INSTAGRAM
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" -> VideoPlatform.FACEBOOK
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") -> VideoPlatform.X
            host == "vimeo.com" || host.endsWith(".vimeo.com") -> VideoPlatform.VIMEO
            host == "twitch.tv" || host.endsWith(".twitch.tv") -> VideoPlatform.TWITCH
            else -> VideoPlatform.UNKNOWN
        }
        val pathExt = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        val direct = pathExt in directExtensions ||
            uri.getQueryParameter("format")?.lowercase() in directExtensions
        return ResolvedVideoLink(uri, if (direct) VideoPlatform.DIRECT else platform, direct, host)
    }

    fun isSupportedHost(link: ResolvedVideoLink): Boolean =
        link.platform != VideoPlatform.UNKNOWN || link.isDirectMedia

    fun displayName(platform: VideoPlatform): String = when (platform) {
        VideoPlatform.YOUTUBE -> "YouTube"
        VideoPlatform.TIKTOK -> "TikTok"
        VideoPlatform.INSTAGRAM -> "Instagram / Reels"
        VideoPlatform.FACEBOOK -> "Facebook / Watch"
        VideoPlatform.X -> "X"
        VideoPlatform.VIMEO -> "Vimeo"
        VideoPlatform.TWITCH -> "Twitch"
        VideoPlatform.DIRECT -> "رابط فيديو مباشر"
        VideoPlatform.UNKNOWN -> "موقع غير معروف"
    }
}
