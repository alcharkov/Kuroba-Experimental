package com.github.k1rakishou.chan.core.site.loader

import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.base.okhttp.CloudFlareHandlerInterceptor
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.google.gson.JsonParseException
import okhttp3.HttpUrl
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException


open class ChanLoaderException(
  private val exception: Throwable
) : Exception(exception) {

  val isNotFound: Boolean
    get() = exception is ServerException && isServerErrorNotFound(exception)

  val errorMessage: Int
    get() {
      return when {
        exception is SocketTimeoutException
          || exception is SocketException
          || exception is UnknownHostException
          || (exception is ServerException && exception.isAuthError()) -> {
          R.string.thread_load_failed_network
        }
        exception is ServerException -> {
          if (isServerErrorNotFound(exception)) {
            R.string.thread_load_failed_not_found
          } else {
            R.string.thread_load_failed_server
          }
        }
        exception is SSLException -> R.string.thread_load_failed_ssl
        exception is JsonParseException -> R.string.thread_load_failed_json_parsing
        exception is CloudFlareHandlerInterceptor.CloudFlareDetectedException -> {
          R.string.thread_load_failed_cloud_flare_detected
        }
        else -> R.string.thread_load_failed_parsing
      }
    }

  private fun isServerErrorNotFound(exception: ServerException): Boolean {
    return exception.statusCode == 404
  }

  fun isCloudFlareError(): Boolean = exception is CloudFlareHandlerInterceptor.CloudFlareDetectedException

  fun getOriginalRequestHost(): String {
    if (!isCloudFlareError()) {
      throw IllegalStateException("Not a CloudFlareDetectedException error!")
    }

    val fullUrl = (exception as CloudFlareHandlerInterceptor.CloudFlareDetectedException).requestUrl

    return HttpUrl.Builder()
      .scheme("https")
      .host(fullUrl.host)
      .build()
      .toString()
  }

  fun isCoroutineCancellationError(): Boolean {
    return exception is CancellationException
  }

  fun isCacheEmptyException(): Boolean = exception is CacheIsEmptyException

  companion object {
    fun cacheIsEmptyException(chanDescriptor: ChanDescriptor): ChanLoaderException {
      return ChanLoaderException(CacheIsEmptyException(chanDescriptor))
    }
  }

}

class ClientException(message: String) : ChanLoaderException(Exception(message))

class ServerException(val statusCode: Int) : Exception("Bad status code: ${statusCode}") {
  fun isAuthError(): Boolean {
    return statusCode == 401 || statusCode == 403
  }
}

class CacheIsEmptyException(chanDescriptor: ChanDescriptor) : Exception("Cache is empty for /$chanDescriptor/")