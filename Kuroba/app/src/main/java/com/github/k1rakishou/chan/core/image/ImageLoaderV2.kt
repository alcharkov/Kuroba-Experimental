package com.github.k1rakishou.chan.core.image

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.GuardedBy
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.bitmap.BitmapPool
import coil.memory.MemoryCache
import coil.network.HttpException
import coil.request.*
import coil.size.*
import coil.transform.Transformation
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.base.okhttp.CoilOkHttpClient
import com.github.k1rakishou.chan.core.cache.CacheHandler
import com.github.k1rakishou.chan.core.manager.ReplyManager
import com.github.k1rakishou.chan.core.site.SiteResolver
import com.github.k1rakishou.chan.ui.widget.FixedViewSizeResolver
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.chan.utils.MediaUtils
import com.github.k1rakishou.chan.utils.getLifecycleFromContext
import com.github.k1rakishou.common.*
import com.github.k1rakishou.common.ModularResult.Companion.Try
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.core_themes.ThemeEngine
import com.github.k1rakishou.fsaf.FileManager
import com.google.android.exoplayer2.util.MimeTypes
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

@DoNotStrip
class ImageLoaderV2(
  private val verboseLogs: Boolean,
  private val appScope: CoroutineScope,
  private val appConstants: AppConstants,
  private val imageLoader: ImageLoader,
  private val replyManager: ReplyManager,
  private val themeEngine: ThemeEngine,
  private val cacheHandler: CacheHandler,
  private val fileManager: FileManager,
  private val siteResolver: SiteResolver,
  private val coilOkHttpClient: CoilOkHttpClient
) {
  private val mutex = Mutex()

  @GuardedBy("mutex")
  private val activeRequests = LruCache<String, ActiveRequest>(1024)

  private var imageNotFoundDrawable: CachedTintedErrorDrawable? = null
  private var imageErrorLoadingDrawable: CachedTintedErrorDrawable? = null

  suspend fun isImageCachedLocally(url: String): Boolean {
    return withContext(Dispatchers.Default) {
      val exists = cacheHandler.cacheFileExists(url)
      val downloaded = cacheHandler.isAlreadyDownloaded(url)

      return@withContext exists && downloaded
    }
  }

  suspend fun loadFromNetworkSuspend(
    context: Context,
    url: String,
    imageSize: ImageSize,
    transformations: List<Transformation> = emptyList()
  ): BitmapDrawable? {
    return suspendCancellableCoroutine { continuation ->
      loadFromNetwork(context, url, imageSize, transformations, object : FailureAwareImageListener {
        override fun onResponse(drawable: BitmapDrawable, isImmediate: Boolean) {
          continuation.resume(drawable)
        }

        override fun onNotFound() {
          continuation.resume(null)
        }

        override fun onResponseError(error: Throwable) {
          continuation.resume(null)
        }
      })
    }
  }

  fun loadFromNetwork(
    context: Context,
    url: String,
    imageSize: ImageSize,
    transformations: List<Transformation>,
    listener: SimpleImageListener,
    @DrawableRes errorDrawableId: Int = R.drawable.ic_image_error_loading,
    @DrawableRes notFoundDrawableId: Int = R.drawable.ic_image_not_found
  ): Disposable {
    BackgroundUtils.ensureMainThread()

    return loadFromNetwork(
      context = context,
      url = url,
      imageSize = imageSize,
      inputTransformations = transformations,
      imageListenerParam = ImageListenerParam.SimpleImageListener(
        listener = listener,
        errorDrawableId = errorDrawableId,
        notFoundDrawableId = notFoundDrawableId
      )
    )
  }

  fun loadFromNetwork(
    context: Context,
    requestUrl: String,
    imageSize: ImageSize,
    transformations: List<Transformation>,
    listener: FailureAwareImageListener
  ): Disposable {
    BackgroundUtils.ensureMainThread()

    return loadFromNetwork(
      context = context,
      url = requestUrl,
      imageSize = imageSize,
      inputTransformations = transformations,
      imageListenerParam = ImageListenerParam.FailureAwareImageListener(listener)
    )
  }

  private fun loadFromNetwork(
    context: Context,
    url: String,
    imageSize: ImageSize,
    inputTransformations: List<Transformation>,
    imageListenerParam: ImageListenerParam
  ): Disposable {
    BackgroundUtils.ensureMainThread()
    val completableDeferred = CompletableDeferred<Unit>()

    val job = appScope.launch(Dispatchers.IO) {
      BackgroundUtils.ensureBackgroundThread()

      try {
        var isFromCache = true

        // 1. Enqueue a new request (or add a callback to an old request if there is already a
        // request with this url).
        val alreadyHasActiveRequest = mutex.withLockNonCancellable {
          var activeRequest = activeRequests.get(url)
          if (activeRequest == null) {
            // Create new ActiveRequest is it's not created yet
            activeRequest = ActiveRequest(url)
            activeRequests.put(url, activeRequest)
          }

          // Add all the listeners into this request (there may be multiple of them)
          return@withLockNonCancellable activeRequest.addImageListener(
            imageListenerParam = imageListenerParam,
            imageSize = imageSize,
            transformations = inputTransformations
          )
        }

        if (alreadyHasActiveRequest) {
          // Another request with the same url is already running, wait until the other request is
          // completed, it will invoke all callbacks.

          if (verboseLogs) {
            Logger.d(TAG, "Request '$url' is already active, waiting for it's completion")
          }

          return@launch
        }

        // 2. Check whether we have this bitmap cached on the disk
        var imageFile = tryLoadFromDiskCacheOrNull(url)

        // 3. Failed to find this bitmap in the disk cache. Load it from the network.
        if (imageFile == null) {
          isFromCache = false

          imageFile = loadFromNetworkInternal(
            context = context,
            url = url,
            imageSize = imageSize
          )

          if (imageFile == null) {
            val errorMessage = "Failed to load image '$url' from disk and network"

            Logger.e(TAG, errorMessage)
            notifyListenersFailure(context, url, IOException(errorMessage))

            return@launch
          }
        }

        // 4. We have this image on disk, now we need to reload it from disk, apply transformations
        // with size and notify all listeners.
        val activeListeners = mutex.withLockNonCancellable {
          val activeRequests = activeRequests.get(url)
            ?: return@withLockNonCancellable null

          return@withLockNonCancellable activeRequests.consumeAllListeners()
        }

        if (activeListeners == null || activeListeners.isEmpty()) {
          if (verboseLogs) {
            Logger.e(TAG, "Failed to load '$url', activeListeners is null or empty")
          }

          return@launch
        }

        activeListeners.forEach { activeListener ->
          val resultBitmapDrawable = applyTransformationsToDrawable(
            context,
            context.getLifecycleFromContext(),
            imageFile,
            activeListener,
            url
          )

          mutex.withLockNonCancellable {
            val activeRequest = activeRequests.get(url)
              ?: return@withLockNonCancellable

            if (activeRequest.removeImageListenerParam(imageListenerParam)) {
              activeRequests.remove(url)
            }
          }

          if (resultBitmapDrawable == null) {
            val transformationKeys = activeListener.transformations
              .joinToString { transformation -> transformation.key() }

            Logger.e(TAG, "Failed to apply transformations '$url' $imageSize, " +
              "transformations: ${transformationKeys}, fromCache=$isFromCache")

            handleFailure(
              actualListener = activeListener.imageListenerParam,
              context = context,
              imageSize = activeListener.imageSize,
              transformations = activeListener.transformations,
              throwable = IOException("applyTransformationsToDrawable() returned null")
            )

            return@forEach
          }

          withContext(Dispatchers.Main) {
            when (val listenerParam = activeListener.imageListenerParam) {
              is ImageListenerParam.SimpleImageListener -> {
                listenerParam.listener.onResponse(resultBitmapDrawable)
              }
              is ImageListenerParam.FailureAwareImageListener -> {
                listenerParam.listener.onResponse(resultBitmapDrawable, isFromCache)
              }
            }
          }
        }
      } catch (error: Throwable) {
        notifyListenersFailure(context, url, error)

        if (error.isCoroutineCancellationException()) {
          return@launch
        }

        if (error.isExceptionImportant()) {
          Logger.e(TAG, "loadFromNetwork() error", error)
        }
      } finally {
        completableDeferred.complete(Unit)
      }
    }

    return ImageLoaderRequestDisposable(
      imageLoaderJob = job,
      imageLoaderCompletableDeferred = completableDeferred
    )
  }

  private suspend fun applyTransformationsToDrawable(
    context: Context,
    lifecycle: Lifecycle?,
    imageFile: File?,
    activeListener: ActiveListener,
    url: String
  ): BitmapDrawable? {
    if (imageFile == null) {
      return null
    }

    val request = with(ImageRequest.Builder(context)) {
      lifecycle(lifecycle)
      allowHardware(true)
      allowRgb565(AppModuleAndroidUtils.isLowRamDevice())
      data(imageFile)
      scale(Scale.FIT)
      transformations(activeListener.transformations + RESIZE_TRANSFORMATION)
      applyImageSize(activeListener.imageSize)

      build()
    }

    return when (val result = imageLoader.execute(request)) {
      is SuccessResult -> {
        val bitmap = result.drawable.toBitmap()
        BitmapDrawable(context.resources, bitmap)
      }
      is ErrorResult -> {
        Logger.e(TAG, "applyTransformationsToDrawable() error, " +
          "imageFile=${imageFile.absolutePath}, error=${result.throwable.errorMessageOrClassName()}")

        cacheHandler.deleteCacheFileByUrl(url)
        null
      }
    }
  }

  private suspend fun notifyListenersFailure(
    context: Context,
    url: String,
    error: Throwable
  ) {
    val listeners = mutex.withLockNonCancellable { activeRequests.get(url)?.consumeAllListeners() }
    if (listeners == null) {
      return
    }

    listeners.forEach { listener ->
      handleFailure(
        actualListener = listener.imageListenerParam,
        context = context,
        imageSize = listener.imageSize,
        transformations = listener.transformations,
        throwable = error
      )
    }
  }

  private suspend fun loadFromNetworkInternal(
    context: Context,
    url: String,
    imageSize: ImageSize,
  ): File? {
    BackgroundUtils.ensureBackgroundThread()

    try {
      return loadFromNetworkIntoFile(url)
    } catch (error: Throwable) {
      notifyListenersFailure(context, url, error)

      if (error.isCoroutineCancellationException()) {
        if (verboseLogs) {
          Logger.e(TAG, "loadFromNetworkInternal() canceled '$url'")
        }

        return null
      }

      if (error.isNotFoundError() || !error.isExceptionImportant()) {
        Logger.e(TAG, "Failed to load '$url' $imageSize, fromCache=false, error: ${error.errorMessageOrClassName()}")
      } else {
        Logger.e(TAG, "Failed to load '$url' $imageSize, fromCache=false", error)
      }
    }

    return null
  }

  private suspend fun handleFailure(
    actualListener: ImageListenerParam,
    context: Context,
    imageSize: ImageSize,
    transformations: List<Transformation>,
    throwable: Throwable
  ) {
    if (throwable.isCoroutineCancellationException()) {
      return
    }

    withContext(Dispatchers.Main.immediate) {
      when (actualListener) {
        is ImageListenerParam.SimpleImageListener -> {
          val errorDrawable = loadErrorDrawableByException(
            context,
            imageSize,
            transformations,
            throwable,
            actualListener.notFoundDrawableId,
            actualListener.errorDrawableId
          )

          actualListener.listener.onResponse(errorDrawable)
        }
        is ImageListenerParam.FailureAwareImageListener -> {
          if (throwable.isNotFoundError()) {
            actualListener.listener.onNotFound()
          } else {
            actualListener.listener.onResponseError(throwable)
          }
        }
      }
    }
  }

  private suspend fun loadErrorDrawableByException(
    context: Context,
    imageSize: ImageSize,
    transformations: List<Transformation>,
    throwable: Throwable?,
    notFoundDrawableId: Int,
    errorDrawableId: Int
  ): BitmapDrawable {
    if (throwable != null && throwable.isNotFoundError()) {
      if (notFoundDrawableId != R.drawable.ic_image_not_found) {
        val drawable = loadFromResources(context, notFoundDrawableId, imageSize, Scale.FIT, transformations)
        if (drawable != null) {
          return drawable
        }
      }

      return getImageNotFoundDrawable(context)
    }

    if (errorDrawableId != R.drawable.ic_image_error_loading) {
      val drawable = loadFromResources(context, errorDrawableId, imageSize, Scale.FIT, transformations)
      if (drawable != null) {
        return drawable
      }
    }

    return getImageErrorLoadingDrawable(context)
  }

  @Throws(HttpException::class)
  private suspend fun loadFromNetworkIntoFile(url: String): File? {
    BackgroundUtils.ensureBackgroundThread()

    val cacheFile = cacheHandler.getOrCreateCacheFile(url)
    if (cacheFile == null) {
      Logger.e(TAG, "loadFromNetworkInternalIntoFile() cacheHandler.getOrCreateCacheFile('$url') -> null")
      return null
    }

    val success = try {
      loadFromNetworkIntoFileInternal(url, cacheFile)
    } catch (error: Throwable) {
      cacheHandler.deleteCacheFile(cacheFile)

      if (error.isCoroutineCancellationException()) {
        return null
      }

      throw error
    }

    if (!success) {
      cacheHandler.deleteCacheFile(cacheFile)
      return null
    }

    return cacheFile
  }

  private suspend fun loadFromNetworkIntoFileInternal(url: String, cacheFile: File): Boolean {
    BackgroundUtils.ensureBackgroundThread()

    val site = siteResolver.findSiteForUrl(url)
    val requestModifier = site?.requestModifier()

    val requestBuilder = Request.Builder()
      .url(url)
      .get()

    if (site != null && requestModifier != null) {
      requestModifier.modifyThumbnailGetRequest(site, requestBuilder)
    }

    val response = coilOkHttpClient.okHttpClient().suspendCall(requestBuilder.build())
    if (!response.isSuccessful) {
      Logger.e(TAG, "loadFromNetworkInternalIntoFile() bad response code: ${response.code}")

      if (response.code == 404) {
        throw HttpException(response)
      }

      return false
    }

    runInterruptible {
      val responseBody = response.body
        ?: throw IOException("Response body is null")
      responseBody.byteStream().use { inputStream ->
        cacheFile.outputStream().use { os ->
          inputStream.copyTo(os)
        }
      }
    }

    if (!cacheHandler.markFileDownloaded(cacheFile)) {
      throw IOException("Failed to mark file '${cacheFile.absolutePath}' as downloaded")
    }

    val fileLength = cacheFile.length()
    if (fileLength <= 0) {
      return false
    }

    cacheHandler.fileWasAdded(fileLength)

    return true
  }

  private fun tryLoadFromDiskCacheOrNull(url: String): File? {
    BackgroundUtils.ensureBackgroundThread()

    val cacheFile = cacheHandler.getCacheFileOrNull(url)
    if (cacheFile == null) {
      return null
    }

    if (!cacheHandler.isAlreadyDownloaded(cacheFile)) {
      return null
    }

    if (!cacheFile.exists() || cacheFile.length() == 0L) {
      return null
    }

    return cacheFile
  }

  fun loadFromResources(
    context: Context,
    @DrawableRes drawableId: Int,
    imageSize: ImageSize,
    scale: Scale,
    transformations: List<Transformation>,
    listener: SimpleImageListener
  ): Disposable {
    val completableDeferred = CompletableDeferred<Unit>()

    val job = appScope.launch(Dispatchers.Main.immediate) {
      try {
        val bitmapDrawable = loadFromResources(context, drawableId, imageSize, scale, transformations)
        if (bitmapDrawable == null) {
          if (verboseLogs ) {
            Logger.d(TAG, "loadFromResources() Failed to load '$drawableId', $imageSize")
          }

          listener.onResponse(getImageErrorLoadingDrawable(context))
          return@launch
        }

        if (verboseLogs ) {
          Logger.d(TAG, "loadFromResources() Loaded '$drawableId', $imageSize, bitmap size = " +
            "${bitmapDrawable.intrinsicWidth}x${bitmapDrawable.intrinsicHeight}")
        }

        listener.onResponse(bitmapDrawable)
      } finally {
        completableDeferred.complete(Unit)
      }
    }

    return ImageLoaderRequestDisposable(
      imageLoaderJob = job,
      imageLoaderCompletableDeferred = completableDeferred
    )
  }

  fun loadFromDisk(
    context: Context,
    inputFile: InputFile,
    imageSize: ImageSize,
    scale: Scale,
    transformations: List<Transformation>,
    listener: SimpleImageListener
  ): Disposable {
    require(imageSize !is ImageSize.UnknownImageSize) { "Cannot use UnknownImageSize here!" }

    val lifecycle = context.getLifecycleFromContext()
    val completableDeferred = CompletableDeferred<Unit>()

    val job = appScope.launch(Dispatchers.Main) {
      try {
        if (verboseLogs) {
          Logger.d(TAG, "loadFromDisk() inputFilePath=${inputFile.path()}, imageSize=${imageSize}")
        }

        val fileName = inputFile.fileName()
        if (fileName == null) {
          listener.onResponse(getImageErrorLoadingDrawable(context))
          return@launch
        }

        suspend fun getBitmapDrawable(): BitmapDrawable? {
          BackgroundUtils.ensureBackgroundThread()

          if (fileIsProbablyVideoInterruptible(fileName, inputFile)) {
            val (width, height) = checkNotNull(imageSize.size())

            val key = MemoryCache.Key.invoke(inputFile.path())
            val fromCache = imageLoader.memoryCache[key]
            if (fromCache != null) {
              return BitmapDrawable(context.resources, fromCache)
            }

            val decoded = decodedFilePreview(
              isProbablyVideo = true,
              inputFile = inputFile,
              context = context,
              width = width,
              height = height,
              scale = scale,
              addAudioIcon = false
            )

            if (decoded !is CachedTintedErrorDrawable) {
              imageLoader.memoryCache[key] = decoded.bitmap
            }

            return decoded
          }

          val request = with(ImageRequest.Builder(context)) {
            when (inputFile) {
              is InputFile.JavaFile -> data(inputFile.file)
              is InputFile.FileUri -> data(inputFile.uri)
            }

            lifecycle(lifecycle)
            transformations(transformations)
            allowHardware(true)
            allowRgb565(AppModuleAndroidUtils.isLowRamDevice())
            scale(scale)
            applyImageSize(imageSize)

            build()
          }

          return when (val imageResult = imageLoader.execute(request)) {
            is SuccessResult -> {
              val bitmap = imageResult.drawable.toBitmap()
              BitmapDrawable(context.resources, bitmap)
            }
            is ErrorResult -> null
          }
        }

        val bitmapDrawable = withContext(Dispatchers.IO) { getBitmapDrawable() }
        if (bitmapDrawable == null) {
          if (verboseLogs) {
            Logger.d(TAG, "loadFromDisk() inputFilePath=${inputFile.path()}, " +
              "imageSize=${imageSize} error or canceled")
          }

          listener.onResponse(getImageErrorLoadingDrawable(context))
          return@launch
        }

        if (verboseLogs) {
          Logger.d(TAG, "loadFromDisk() inputFilePath=${inputFile.path()}, " +
            "imageSize=${imageSize} success")
        }

        listener.onResponse(bitmapDrawable)
      } finally {
        completableDeferred.complete(Unit)
      }
    }

    return ImageLoaderRequestDisposable(
      imageLoaderJob = job,
      imageLoaderCompletableDeferred = completableDeferred
    )
  }

  private suspend fun loadFromResources(
    context: Context,
    @DrawableRes drawableId: Int,
    imageSize: ImageSize,
    scale: Scale,
    transformations: List<Transformation>
  ): BitmapDrawable? {
    val lifecycle = context.getLifecycleFromContext()

    val request = with(ImageRequest.Builder(context)) {
      data(drawableId)
      lifecycle(lifecycle)
      transformations(transformations)
      allowHardware(true)
      allowRgb565(AppModuleAndroidUtils.isLowRamDevice())
      scale(scale)
      applyImageSize(imageSize)

      build()
    }

    return when (val imageResult = imageLoader.execute(request)) {
      is SuccessResult -> {
        val bitmap = imageResult.drawable.toBitmap()
        BitmapDrawable(context.resources, bitmap)
      }
      is ErrorResult -> null
    }
  }

  fun loadRelyFilePreviewFromDisk(
    context: Context,
    fileUuid: UUID,
    imageSize: ImageSize,
    scale: Scale = Scale.FIT,
    transformations: List<Transformation>,
    listener: SimpleImageListener
  ) {
    val replyFileMaybe = replyManager.getReplyFileByFileUuid(fileUuid)
    if (replyFileMaybe is ModularResult.Error) {
      Logger.e(TAG, "loadRelyFilePreviewFromDisk() getReplyFileByFileUuid($fileUuid) error",
        replyFileMaybe.error)
      listener.onResponse(getImageErrorLoadingDrawable(context))
      return
    }

    val replyFile = (replyFileMaybe as ModularResult.Value).value
    if (replyFile == null) {
      Logger.e(TAG, "loadRelyFilePreviewFromDisk() replyFile==null")
      listener.onResponse(getImageErrorLoadingDrawable(context))
      return
    }

    val listenerRef = AtomicReference(listener)
    val lifecycle = context.getLifecycleFromContext()

    val request = with(ImageRequest.Builder(context)) {
      data(replyFile.previewFileOnDisk)
      lifecycle(lifecycle)
      transformations(transformations)
      allowHardware(true)
      allowRgb565(AppModuleAndroidUtils.isLowRamDevice())
      scale(scale)
      applyImageSize(imageSize)

      listener(
        onError = { _, _ ->
          listenerRef.get()?.onResponse(getImageErrorLoadingDrawable(context))
          listenerRef.set(null)
        },
        onCancel = {
          listenerRef.set(null)
        }
      )
      target(
        onSuccess = { drawable ->
          try {
            listenerRef.get()?.onResponse(drawable as BitmapDrawable)
          } finally {
            listenerRef.set(null)
          }
        }
      )

      build()
    }

    imageLoader.enqueue(request)
  }

  @Suppress("BlockingMethodInNonBlockingContext")
  suspend fun calculateFilePreviewAndStoreOnDisk(
    context: Context,
    fileUuid: UUID,
    scale: Scale = Scale.FIT
  ) {
    BackgroundUtils.ensureBackgroundThread()

    val replyFileMaybe = replyManager.getReplyFileByFileUuid(fileUuid)
    if (replyFileMaybe is ModularResult.Error) {
      Logger.e(TAG, "calculateFilePreviewAndStoreOnDisk() " +
        "getReplyFileByFileUuid($fileUuid) error", replyFileMaybe.error)
      return
    }

    val replyFile = (replyFileMaybe as ModularResult.Value).value
    if (replyFile == null) {
      Logger.e(TAG, "calculateFilePreviewAndStoreOnDisk() replyFile==null")
      return
    }

    val replyFileMetaMaybe = replyFile.getReplyFileMeta()
    if (replyFileMetaMaybe is ModularResult.Error) {
      Logger.e(TAG, "calculateFilePreviewAndStoreOnDisk() replyFile.getReplyFileMeta() error",
        replyFileMetaMaybe.error)
      return
    }

    val replyFileMeta = (replyFileMetaMaybe as ModularResult.Value).value
    val inputFile = InputFile.JavaFile(replyFile.fileOnDisk)

    val isProbablyVideo = fileIsProbablyVideoInterruptible(
      replyFileMeta.originalFileName,
      inputFile
    )

    val previewBitmap = decodedFilePreview(
      isProbablyVideo = isProbablyVideo,
      inputFile = inputFile,
      context = context,
      width = PREVIEW_SIZE,
      height = PREVIEW_SIZE,
      scale = scale,
      addAudioIcon = true
    ).bitmap

    val previewFileOnDisk = replyFile.previewFileOnDisk

    if (!previewFileOnDisk.exists()) {
      check(previewFileOnDisk.createNewFile()) {
        "Failed to create previewFileOnDisk, path=${previewFileOnDisk.absolutePath}"
      }
    }

    try {
      runInterruptible {
        FileOutputStream(previewFileOnDisk).use { fos ->
          previewBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }
      }
    } catch (error: Throwable) {
      previewFileOnDisk.delete()
      throw error
    }
  }

  @OptIn(ExperimentalTime::class)
  private suspend fun decodedFilePreview(
    isProbablyVideo: Boolean,
    inputFile: InputFile,
    context: Context,
    width: Int,
    height: Int,
    scale: Scale,
    addAudioIcon: Boolean
  ): BitmapDrawable {
    BackgroundUtils.ensureBackgroundThread()

    if (isProbablyVideo) {
      val videoFrameDecodeMaybe = Try {
        return@Try measureTimedValue {
          return@measureTimedValue MediaUtils.decodeVideoFilePreviewImageInterruptible(
            context,
            inputFile,
            width,
            height,
            addAudioIcon
          )
        }
      }

      if (videoFrameDecodeMaybe is ModularResult.Value) {
        val (videoFrame, decodeVideoFilePreviewImageDuration) = videoFrameDecodeMaybe.value
        Logger.d(TAG, "decodeVideoFilePreviewImageInterruptible duration=$decodeVideoFilePreviewImageDuration")

        if (videoFrame != null) {
          return videoFrame
        }
      }

      videoFrameDecodeMaybe.errorOrNull()?.let { error ->
        if (error.isCoroutineCancellationException()) {
          throw error
        }
      }

      // Failed to decode the file as video let's try decoding it as an image
    }

    val (fileImagePreviewMaybe, getFileImagePreviewDuration) = measureTimedValue {
      return@measureTimedValue getFileImagePreview(
        context = context,
        inputFile = inputFile,
        transformations = emptyList(),
        scale = scale,
        width = width,
        height = height
      )
    }

    Logger.d(TAG, "getFileImagePreviewDuration=$getFileImagePreviewDuration")

    if (fileImagePreviewMaybe is ModularResult.Value) {
      return fileImagePreviewMaybe.value
    }

    fileImagePreviewMaybe.errorOrNull()?.let { error ->
      if (error is CancellationException) {
        throw error
      }
    }

    // Do not recycle bitmaps that are supposed to always stay in memory
    return getImageErrorLoadingDrawable(context)
  }

  suspend fun fileIsProbablyVideoInterruptible(
    fileName: String,
    inputFile: InputFile
  ): Boolean {
    val hasVideoExtension = StringUtils.extractFileNameExtension(fileName)
      ?.let { extension -> extension == "mp4" || extension == "webm" }
      ?: false

    if (hasVideoExtension) {
      return true
    }

    return MediaUtils.decodeFileMimeTypeInterruptible(inputFile)
      ?.let { mimeType -> MimeTypes.isVideo(mimeType) }
      ?: false
  }

  private suspend fun getFileImagePreview(
    context: Context,
    inputFile: InputFile,
    transformations: List<Transformation>,
    scale: Scale,
    width: Int,
    height: Int
  ): ModularResult<BitmapDrawable> {
    return Try {
      val lifecycle = context.getLifecycleFromContext()

      val request = with(ImageRequest.Builder(context)) {
        when (inputFile) {
          is InputFile.FileUri -> data(inputFile.uri)
          is InputFile.JavaFile -> data(inputFile.file)
        }

        lifecycle(lifecycle)
        transformations(transformations)
        allowHardware(true)
        allowRgb565(AppModuleAndroidUtils.isLowRamDevice())
        scale(scale)
        size(width, height)

        build()
      }

      when (val imageResult = imageLoader.execute(request)) {
        is SuccessResult -> return@Try imageResult.drawable as BitmapDrawable
        is ErrorResult -> throw imageResult.throwable
      }
    }
  }

  @Synchronized
  private fun getImageNotFoundDrawable(context: Context): BitmapDrawable {
    if (imageNotFoundDrawable != null && imageNotFoundDrawable!!.isDarkTheme == themeEngine.chanTheme.isDarkTheme) {
      return imageNotFoundDrawable!!.bitmapDrawable
    }

    val drawable = themeEngine.tintDrawable(
      context,
      R.drawable.ic_image_not_found
    )

    requireNotNull(drawable) { "Couldn't load R.drawable.ic_image_not_found" }

    val bitmapDrawable = if (drawable is BitmapDrawable) {
      drawable
    } else {
      BitmapDrawable(context.resources, drawable.toBitmap())
    }

    imageNotFoundDrawable = CachedTintedErrorDrawable(
      context.applicationContext.resources,
      bitmapDrawable,
      themeEngine.chanTheme.isDarkTheme
    )

    return imageNotFoundDrawable!!
  }

  @Synchronized
  private fun getImageErrorLoadingDrawable(context: Context): BitmapDrawable {
    if (imageErrorLoadingDrawable != null && imageErrorLoadingDrawable!!.isDarkTheme == themeEngine.chanTheme.isDarkTheme) {
      return imageErrorLoadingDrawable!!.bitmapDrawable
    }

    val drawable = themeEngine.tintDrawable(
      context,
      R.drawable.ic_image_error_loading
    )

    requireNotNull(drawable) { "Couldn't load R.drawable.ic_image_error_loading" }

    val bitmapDrawable = if (drawable is BitmapDrawable) {
      drawable
    } else {
      BitmapDrawable(context.resources, drawable.toBitmap())
    }

    imageErrorLoadingDrawable = CachedTintedErrorDrawable(
      context.applicationContext.resources,
      bitmapDrawable,
      themeEngine.chanTheme.isDarkTheme
    )

    return imageErrorLoadingDrawable!!
  }

  private fun ImageRequest.Builder.applyImageSize(imageSize: ImageSize) {
    when (imageSize) {
      is ImageSize.FixedImageSize -> {
        val width = imageSize.width
        val height = imageSize.height

        if ((width > 0) && (height > 0)) {
          size(width, height)
        }
      }
      is ImageSize.MeasurableImageSize -> {
        size(imageSize.sizeResolver)
      }
      is ImageSize.UnknownImageSize -> {
        // no-op
      }
    }
  }

  private fun Throwable.isNotFoundError(): Boolean {
    return this is HttpException && this.response.code == 404
  }

  sealed class ImageListenerParam {
    class SimpleImageListener(
      val listener: ImageLoaderV2.SimpleImageListener,
      @DrawableRes val errorDrawableId: Int,
      @DrawableRes val notFoundDrawableId: Int
    ) : ImageListenerParam()

    class FailureAwareImageListener(
      val listener: ImageLoaderV2.FailureAwareImageListener
    ) : ImageListenerParam()
  }

  fun interface SimpleImageListener {
    fun onResponse(drawable: BitmapDrawable)
  }

  interface FailureAwareImageListener {
    fun onResponse(drawable: BitmapDrawable, isImmediate: Boolean)
    fun onNotFound()
    fun onResponseError(error: Throwable)
  }

  sealed class ImageSize {
    suspend fun size(): PixelSize? {
      return when (this) {
        is FixedImageSize -> PixelSize(width, height)
        is MeasurableImageSize -> sizeResolver.size() as PixelSize
        UnknownImageSize -> null
      }
    }

    object UnknownImageSize : ImageSize() {
      override fun toString(): String = "UnknownImageSize"
    }

    data class FixedImageSize(val width: Int, val height: Int) : ImageSize() {
      override fun toString(): String = "FixedImageSize{${width}x${height}}"
    }

    data class MeasurableImageSize private constructor(val sizeResolver: ViewSizeResolver<View>) : ImageSize() {
      override fun toString(): String = "MeasurableImageSize"

      companion object {
        @JvmStatic
        fun create(view: View): MeasurableImageSize {
          return MeasurableImageSize(FixedViewSizeResolver(view))
        }
      }
    }
  }

  class ImageLoaderRequestDisposable(
    private val imageLoaderJob: Job,
    private val imageLoaderCompletableDeferred: CompletableDeferred<Unit>
  ) : Disposable {

    override val isDisposed: Boolean
      get() = !imageLoaderJob.isActive

    @ExperimentalCoilApi
    override suspend fun await() {
      imageLoaderCompletableDeferred.await()
    }

    override fun dispose() {
      imageLoaderJob.cancel()
      imageLoaderCompletableDeferred.cancel()
    }

  }

  private class ResizeTransformation : Transformation {
    override fun key(): String = "${TAG}_ResizeTransformation"

    override suspend fun transform(pool: BitmapPool, input: Bitmap, size: Size): Bitmap {
      val (width, height) = when (size) {
        OriginalSize -> null to null
        is PixelSize -> size.width to size.height
      }

      if (width == null || height == null) {
        return input
      }

      if (input.width == width && input.height == height) {
        return input
      }

      return scale(pool, input, width, height)
    }

    private fun scale(pool: BitmapPool, bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
      val width: Int
      val height: Int
      val widthRatio = bitmap.width.toFloat() / maxWidth
      val heightRatio = bitmap.height.toFloat() / maxHeight

      if (widthRatio >= heightRatio) {
        width = maxWidth
        height = (width.toFloat() / bitmap.width * bitmap.height).toInt()
      } else {
        height = maxHeight
        width = (height.toFloat() / bitmap.height * bitmap.width).toInt()
      }

      val scaledBitmap = pool.get(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
      val ratioX = width.toFloat() / bitmap.width
      val ratioY = height.toFloat() / bitmap.height
      val middleX = width / 2.0f
      val middleY = height / 2.0f
      val scaleMatrix = Matrix()
      scaleMatrix.setScale(ratioX, ratioY, middleX, middleY)

      val canvas = Canvas(scaledBitmap)
      canvas.setMatrix(scaleMatrix)
      canvas.drawBitmap(
        bitmap,
        middleX - bitmap.width / 2,
        middleY - bitmap.height / 2,
        Paint(Paint.FILTER_BITMAP_FLAG)
      )

      return scaledBitmap
    }
  }

  private class CachedTintedErrorDrawable(
    resources: Resources,
    val bitmapDrawable: BitmapDrawable,
    val isDarkTheme: Boolean
  ) : BitmapDrawable(resources, bitmapDrawable.bitmap) {
    override fun draw(canvas: Canvas) {
      bitmapDrawable.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
      bitmapDrawable.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
      bitmapDrawable.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
      return bitmapDrawable.opacity
    }
  }

  class ActiveListener(
    val imageListenerParam: ImageListenerParam,
    val imageSize: ImageSize,
    val transformations: List<Transformation>
  )

  private data class ActiveRequest(val url: String) {
    private val listeners = hashSetOf<ActiveListener>()

    @Synchronized
    fun addImageListener(
      imageListenerParam: ImageListenerParam,
      imageSize: ImageSize,
      transformations: List<Transformation>
    ): Boolean {
      val alreadyHasActiveRequest = listeners.isNotEmpty()
      listeners += ActiveListener(imageListenerParam, imageSize, transformations)

      return alreadyHasActiveRequest
    }

    @Synchronized
    fun consumeAllListeners(): Set<ActiveListener> {
      val imageListenersCopy = listeners.toSet()
      listeners.clear()

      return imageListenersCopy
    }

    @Synchronized
    fun removeImageListenerParam(imageListenerParam: ImageListenerParam): Boolean {
      listeners.removeIfKt { activeListener ->
        activeListener.imageListenerParam === imageListenerParam
      }

      return listeners.isEmpty()
    }
  }

  companion object {
    private const val TAG = "ImageLoaderV2"
    private const val PREVIEW_SIZE = 1024

    private val RESIZE_TRANSFORMATION = ResizeTransformation()
  }

}