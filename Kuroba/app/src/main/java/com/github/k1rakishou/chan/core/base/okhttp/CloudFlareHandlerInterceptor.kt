package com.github.k1rakishou.chan.core.base.okhttp

import androidx.annotation.GuardedBy
import com.github.k1rakishou.chan.core.site.SiteResolver
import com.github.k1rakishou.chan.core.site.SiteSetting
import com.github.k1rakishou.chan.utils.containsPattern
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.prefs.StringSetting
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.nio.charset.StandardCharsets

class CloudFlareHandlerInterceptor(
  private val siteResolver: SiteResolver,
  private val isOkHttpClientForSiteRequests: Boolean,
  private val verboseLogs: Boolean,
  private val okHttpType: String
) : Interceptor {

  @GuardedBy("this")
  private val sitesThatRequireCloudFlareCache = mutableSetOf<String>()

  override fun intercept(chain: Interceptor.Chain): Response {
    var request = chain.request()
    var addedCookie = false
    val host = request.url.host

    if (requireCloudFlareCookie(request)) {
      if (verboseLogs) {
        Logger.d(TAG, "[$okHttpType] requireCloudFlareCookie() returned true for $host")
      }

      val updatedRequest = addCloudFlareCookie(chain.request())
      if (updatedRequest != null) {
        if (verboseLogs) {
          Logger.d(TAG, "[$okHttpType] Updated request to host: '$host' with cfClearance cookie")
        }

        request = updatedRequest
        addedCookie = true
      }
    }

    val response = chain.proceed(request)

    if (response.code == 503) {
      val body = response.body
      if (body != null) {
        if (tryDetectCloudFlareNeedle(body)) {
          if (verboseLogs) {
            Logger.d(TAG, "[$okHttpType] Found CloudFlare needle in the page's body")
          }

          if (addedCookie && isOkHttpClientForSiteRequests) {
            if (verboseLogs) {
              Logger.d(TAG, "[$okHttpType] Cookie was already added and we still failed, " +
                "removing the old cookie")
            }

            // For some reason CloudFlare still rejected our request even though we added the cookie.
            // This may happen because of many reasons like the cookie expired or it was somehow
            // damaged so we need to delete it and re-request again.
            removeSiteClearanceCookie(chain.request())
          }

          synchronized(this) { sitesThatRequireCloudFlareCache.add(host) }

          // We only want to throw this exception when loading a site's thread endpoint. In any other
          // case (like when opening media files on that site) we only want to add the CloudFlare
          // CfClearance cookie to the headers.
          if (isOkHttpClientForSiteRequests) {
            throw CloudFlareDetectedException(request.url)
          }
        }
      }
    }

    return response
  }

  private fun requireCloudFlareCookie(request: Request): Boolean {
    val host = request.url.host

    val alreadyCheckedSite = synchronized(this) { host in sitesThatRequireCloudFlareCache }
    if (alreadyCheckedSite) {
      return true
    }

    val url = request.url
    val site = siteResolver.findSiteForUrl(url.toString())

    if (site == null) {
      return false
    }

    val cloudFlareClearanceCookieSetting = site.getSettingBySettingId<StringSetting>(
      SiteSetting.SiteSettingId.CloudFlareClearanceCookie
    )

    if (cloudFlareClearanceCookieSetting == null) {
      Logger.e(TAG, "[$okHttpType] requireCloudFlareCookie() CloudFlareClearanceCookie setting was not found")
      return false
    }

    return cloudFlareClearanceCookieSetting.get().isNotEmpty()
  }

  private fun removeSiteClearanceCookie(request: Request) {
    val url = request.url
    val site = siteResolver.findSiteForUrl(url.toString())

    if (site == null) {
      return
    }

    val cloudFlareClearanceCookieSetting = site.getSettingBySettingId<StringSetting>(
      SiteSetting.SiteSettingId.CloudFlareClearanceCookie
    )

    if (cloudFlareClearanceCookieSetting == null) {
      Logger.e(TAG, "[$okHttpType] removeSiteClearanceCookie() CloudFlareClearanceCookie setting was not found")
      return
    }

    val prevValue = cloudFlareClearanceCookieSetting.get()
    if (prevValue.isEmpty()) {
      Logger.e(TAG, "[$okHttpType] removeSiteClearanceCookie() cookieValue is empty")
      return
    }

    cloudFlareClearanceCookieSetting.setSyncNoCheck("")
  }

  private fun addCloudFlareCookie(prevRequest: Request): Request? {
    val url = prevRequest.url
    val site = siteResolver.findSiteForUrl(url.toString())

    if (site == null) {
      return null
    }

    val cloudFlareClearanceCookieSetting = site.getSettingBySettingId<StringSetting>(
      SiteSetting.SiteSettingId.CloudFlareClearanceCookie
    )

    if (cloudFlareClearanceCookieSetting == null) {
      Logger.e(TAG, "[$okHttpType] addCloudFlareCookie() CloudFlareClearanceCookie setting was not found")
      return null
    }

    val cookieValue = cloudFlareClearanceCookieSetting.get()
    if (cookieValue.isEmpty()) {
      Logger.e(TAG, "[$okHttpType] addCloudFlareCookie() cookieValue is empty")
      return null
    }

    if (verboseLogs) {
      Logger.d(TAG, "[$okHttpType] cookieValue=$cookieValue")
    }

    return prevRequest.newBuilder()
      .addHeader("Cookie", "$CF_CLEARANCE=$cookieValue")
      .build()
  }

  private fun tryDetectCloudFlareNeedle(responseBody: ResponseBody): Boolean {
    return responseBody.use { body ->
      return@use body.byteStream().use { inputStream ->
        val totalSize = Math.max(MAX_ALLOWED_CLOUD_FLARE_PAGE_SIZE, inputStream.available())
        if (totalSize > MAX_ALLOWED_CLOUD_FLARE_PAGE_SIZE) {
          // We assume that CloudFlare pages are supposed to be lightweight.
          return@use false
        }

        val bytes = ByteArray(totalSize) { 0x00 }

        val read = inputStream.read(bytes)
        if (read < 0) {
          return@use false
        }

        if (!bytes.containsPattern(0, CLOUD_FLARE_NEEDLE2)) {
          return@use false
        }

        return@use true
      }
    }
  }

  class CloudFlareDetectedException(
    val requestUrl: HttpUrl
  ) : IOException("Url '$requestUrl' cannot be opened without going through CloudFlare checks first!")

  companion object {
    private const val TAG = "CloudFlareHandlerInterceptor"
    private const val MAX_ALLOWED_CLOUD_FLARE_PAGE_SIZE = 128 * 1024 // 128KB

    const val CF_CLEARANCE = "cf_clearance"

    private val CLOUD_FLARE_NEEDLE2 = "Checking your browser before accessing".toByteArray(StandardCharsets.UTF_8)
  }
}