package com.github.k1rakishou.chan.core.manager

import com.github.k1rakishou.chan.core.site.Site
import com.github.k1rakishou.chan.core.site.common.CommonClientException
import com.github.k1rakishou.chan.core.site.loader.ChanLoaderException
import com.github.k1rakishou.chan.core.site.loader.ChanThreadLoaderCoordinator
import com.github.k1rakishou.chan.core.site.loader.ThreadLoadResult
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.mutableListWithCap
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.catalog.ChanCatalog
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.options.ChanCacheOptions
import com.github.k1rakishou.model.data.options.ChanCacheUpdateOptions
import com.github.k1rakishou.model.data.options.ChanLoadOption
import com.github.k1rakishou.model.data.options.ChanLoadOptions
import com.github.k1rakishou.model.data.options.ChanReadOptions
import com.github.k1rakishou.model.data.options.PostsToReloadOptions
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.ChanPostImage
import com.github.k1rakishou.model.data.post.LoaderType
import com.github.k1rakishou.model.data.thread.ChanThread
import com.github.k1rakishou.model.repository.ChanPostRepository
import com.github.k1rakishou.model.source.cache.thread.ChanThreadsCache
import com.github.k1rakishou.model.util.ChanPostUtils
import okhttp3.HttpUrl

/**
 * The only manager class that can hold other manager classes. Do not use this class in other manager
 * classes to avoid circular dependencies.
 * */
class ChanThreadManager(
  private val verboseLogs: Boolean,
  private val siteManager: SiteManager,
  private val bookmarksManager: BookmarksManager,
  private val postFilterManager: PostFilterManager,
  private val savedReplyManager: SavedReplyManager,
  private val chanThreadsCache: ChanThreadsCache,
  private val chanPostRepository: ChanPostRepository,
  private val chanThreadLoaderCoordinator: ChanThreadLoaderCoordinator
) {
  @get:Synchronized
  @set:Synchronized
  var currentCatalogDescriptor: ChanDescriptor.CatalogDescriptor? = null
    private set

  @get:Synchronized
  @set:Synchronized
  var currentThreadDescriptor: ChanDescriptor.ThreadDescriptor? = null
    private set

  // Only accessed on the main thread
  private val requestedChanDescriptors = hashSetOf<ChanDescriptor>()

  suspend fun awaitUntilDependenciesInitialized() {
    siteManager.awaitUntilInitialized()
    bookmarksManager.awaitUntilInitialized()
    chanPostRepository.awaitUntilInitialized()
  }

  fun bindChanDescriptor(chanDescriptor: ChanDescriptor) {
    when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> {
        currentThreadDescriptor = chanDescriptor
        chanPostRepository.updateThreadLastAccessTime(chanDescriptor)
      }
      is ChanDescriptor.CatalogDescriptor -> currentCatalogDescriptor = chanDescriptor
    }
  }

  fun unbindChanDescriptor(chanDescriptor: ChanDescriptor) {
    when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> currentThreadDescriptor = null
      is ChanDescriptor.CatalogDescriptor -> currentCatalogDescriptor = null
    }
  }

  suspend fun isRequestAlreadyActive(chanDescriptor: ChanDescriptor): Boolean {
    if (!requestedChanDescriptors.add(chanDescriptor)) {
      // This chan descriptor has already been requested
      if (verboseLogs) {
        Logger.d(TAG, "loadThreadOrCatalog() skipping $chanDescriptor because it was already requested")
      }

      return true
    }

    return false
  }

  suspend fun loadThreadOrCatalog(
    chanDescriptor: ChanDescriptor,
    chanCacheUpdateOptions: ChanCacheUpdateOptions,
    chanLoadOptions: ChanLoadOptions,
    chanCacheOptions: ChanCacheOptions,
    chanReadOptions: ChanReadOptions,
    onReloaded: suspend (ThreadLoadResult) -> Unit
  ) {
    BackgroundUtils.ensureMainThread()

    try {
      Logger.d(TAG, "loadThreadOrCatalog(chanDescriptor=$chanDescriptor, " +
        "chanCacheUpdateOptions=$chanCacheUpdateOptions, " +
        "$chanLoadOptions, $chanCacheOptions, $chanReadOptions)")

      if (chanLoadOptions.isNotDefault()) {
        Logger.d(TAG, "loadThreadOrCatalog() postFilterManager.removeAllForDescriptor()")
        postFilterManager.removeAllForDescriptor(chanDescriptor)
      }

      if (chanLoadOptions.canClearCache() || chanLoadOptions.canForceUpdate()) {
        Logger.d(TAG, "loadThreadOrCatalog() deleting posts from the cache")

        if (chanDescriptor is ChanDescriptor.ThreadDescriptor) {
          when (val chanLoadOption = chanLoadOptions.chanLoadOption) {
            is ChanLoadOption.DeletePostsFromMemoryCache -> {
              chanThreadsCache.deletePosts(chanLoadOption.postDescriptors)
            }
            is ChanLoadOption.ForceUpdatePosts -> {
              chanThreadsCache.forceUpdatePosts(chanDescriptor, chanLoadOption.postDescriptors)
            }
            ChanLoadOption.ClearMemoryAndDatabaseCaches,
            ChanLoadOption.ClearMemoryCache,
            ChanLoadOption.RetainAll -> {
              chanThreadsCache.deleteThread(chanDescriptor)
            }
          }
        }
      }

      if (chanLoadOptions.canClearDatabase()) {
        Logger.d(TAG, "loadThreadOrCatalog() deleting posts from the database")

        when (chanDescriptor) {
          is ChanDescriptor.ThreadDescriptor -> chanPostRepository.deleteThread(chanDescriptor)
          is ChanDescriptor.CatalogDescriptor -> chanPostRepository.deleteCatalog(chanDescriptor)
        }
      }

      val threadLoaderResult = loadInternal(
        chanDescriptor = chanDescriptor,
        chanCacheUpdateOptions = chanCacheUpdateOptions,
        chanLoadOptions = chanLoadOptions,
        chanCacheOptions = chanCacheOptions,
        chanReadOptions = chanReadOptions
      )

      when (threadLoaderResult) {
        is ModularResult.Value -> {
          onReloaded.invoke(threadLoaderResult.value)
        }
        is ModularResult.Error -> {
          val error = threadLoaderResult.error
          if (error is ChanLoaderException) {
            onReloaded.invoke(ThreadLoadResult.Error(error))
          } else {
            onReloaded.invoke(ThreadLoadResult.Error(ChanLoaderException(error)))
          }
        }
      }
    } finally {
      requestedChanDescriptors.remove(chanDescriptor)
    }
  }

  fun iteratePostsWhile(
    chanDescriptor: ChanDescriptor,
    iterator: (ChanPost) -> Boolean
  ) {
    iteratePostsWhile(chanDescriptor, null, iterator)
  }

  fun iteratePostsWhile(
    chanDescriptor: ChanDescriptor,
    postDescriptors: Collection<PostDescriptor>?,
    iterator: (ChanPost) -> Boolean
  ) {
    when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> {
        val chanThread = chanThreadsCache.getThread(chanDescriptor)
          ?: return

        if (postDescriptors != null) {
          for (postDescriptor in postDescriptors) {
            val post = chanThread.getPost(postDescriptor)
              ?: continue

            if (!iterator(post)) {
              return
            }
          }
        } else {
          chanThread.iteratePostsOrderedWhile(iterator)
        }
      }
      is ChanDescriptor.CatalogDescriptor -> {
        val chanCatalog = chanThreadsCache.getCatalog(chanDescriptor)
          ?: return

        if (postDescriptors != null) {
          for (postDescriptor in postDescriptors) {
            val post = chanCatalog.getPost(postDescriptor)
              ?: continue

            if (!iterator(post)) {
              return
            }
          }
        } else {
          chanCatalog.iteratePostsOrderedWhile(iterator)
        }
      }
    }
  }

  fun getSafeToUseThreadSubject(threadDescriptor: ChanDescriptor.ThreadDescriptor): String? {
    val originalPost = chanThreadsCache.getThread(threadDescriptor)
      ?.getOriginalPost()
      ?: return null

    return ChanPostUtils.getSafeToUseTitle(originalPost)
  }

  fun getChanThread(threadDescriptor: ChanDescriptor.ThreadDescriptor?): ChanThread? {
    if (threadDescriptor == null) {
      return null
    }

    return chanThreadsCache.getThread(threadDescriptor)
  }

  fun getChanCatalog(catalogDescriptor: ChanDescriptor.CatalogDescriptor?): ChanCatalog? {
    if (catalogDescriptor == null) {
      return null
    }

    return chanThreadsCache.getCatalog(catalogDescriptor)
  }

  suspend fun deletePost(postDescriptor: PostDescriptor) {
    val result = chanPostRepository.deletePost(postDescriptor)
    if (result is ModularResult.Error) {
      Logger.e(TAG, "Failed to delete post ($postDescriptor) from chanPostRepository")
      return
    }

    postFilterManager.remove(postDescriptor)
    savedReplyManager.unsavePost(postDescriptor)
  }

  fun isCached(chanDescriptor: ChanDescriptor?): Boolean {
    if (chanDescriptor == null) {
      return false
    }

    return chanThreadsCache.contains(chanDescriptor)
  }

  fun findPostByPostNo(chanDescriptor: ChanDescriptor?, postNo: Long): ChanPost? {
    if (chanDescriptor == null) {
      return null
    }

    return chanThreadsCache.getPostFromCache(chanDescriptor, postNo)
  }

  fun getThreadPostsCount(descriptor: ChanDescriptor.ThreadDescriptor): Int {
    return chanThreadsCache.getThreadPostsCount(descriptor)
  }

  fun getLastPost(descriptor: ChanDescriptor.ThreadDescriptor): ChanPost? {
    return chanThreadsCache.getLastPost(descriptor)
  }

  fun findPostWithReplies(descriptor: ChanDescriptor, postNo: Long): Set<ChanPost> {
    val postsSet = hashSetOf<ChanPost>()

    when (descriptor) {
      is ChanDescriptor.ThreadDescriptor -> {
        chanThreadsCache.getThread(descriptor)?.findPostWithRepliesRecursive(postNo, postsSet)
      }
      is ChanDescriptor.CatalogDescriptor -> {
        getChanCatalog(descriptor)?.findPostWithRepliesRecursive(postNo, postsSet)
      }
    }

    return postsSet
  }

  fun getPostImages(imageToGet: Collection<Pair<PostDescriptor, HttpUrl>>): List<ChanPostImage> {
    if (imageToGet.isEmpty()) {
      return emptyList()
    }

    val postImages = mutableListOf<ChanPostImage>()

    imageToGet.forEach { (postDescriptor, imageUrl) ->
      val chanPostImage = chanThreadsCache.getThread(postDescriptor.threadDescriptor())
        ?.getPostImage(postDescriptor, imageUrl)

      if (chanPostImage != null) {
        postImages += chanPostImage
      }
    }

    return postImages
  }

  fun getPost(postDescriptor: PostDescriptor): ChanPost? {
    return chanThreadsCache.getThread(postDescriptor.threadDescriptor())?.getPost(postDescriptor)
  }

  fun getCatalogPreviewPosts(threadDescriptor: ChanDescriptor.ThreadDescriptor): List<ChanPost> {
    val chanThread = chanThreadsCache.getThread(threadDescriptor)
      ?: return emptyList()

    val postsCount = chanThread.postsCount

    if (postsCount < CATALOG_PREVIEW_POSTS_COUNT) {
      return chanThread.slicePosts(0..CATALOG_PREVIEW_POSTS_COUNT)
    }

    return chanThread.slicePosts(
      0 until 1,
      (postsCount - CATALOG_PREVIEW_POSTS_COUNT)..postsCount
    )
  }

  fun <T> iteratePostIndexes(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    input: Collection<T>,
    postDescriptorSelector: (T) -> PostDescriptor,
    iterator: (ChanPost, Int) -> Unit,
  ) {
    chanThreadsCache.getThread(threadDescriptor)
      ?.iteratePostIndexes(input, threadDescriptor, postDescriptorSelector, iterator)
  }

  /**
   * Just an optimization to not convert an immutable list of posts into a mutable list of posts
   * when applying filters to posts. You should prefer immutable version of this method in all
   * different places.
   * */
  fun getMutableListOfPosts(chanDescriptor: ChanDescriptor): MutableList<ChanPost> {
    val listOfPosts = mutableListWithCap<ChanPost>(128)

    when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> {
        val chanThread = chanThreadsCache.getThread(chanDescriptor)
          ?: return mutableListOf()

        chanThread.iteratePostsOrdered { chanPost -> listOfPosts += chanPost }
      }
      is ChanDescriptor.CatalogDescriptor -> {
        val chanCatalog = getChanCatalog(chanDescriptor)
          ?: return mutableListOf()

        chanCatalog.iteratePostsOrdered { chanOriginalPost -> listOfPosts += chanOriginalPost }
      }
    }

    return listOfPosts
  }

  fun getNewPostsCount(
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    lastPostNo: Long
  ): Int {
    return chanThreadsCache.getThread(threadDescriptor)?.getNewPostsCount(lastPostNo) ?: 0
  }

  fun isContentLoadedForLoader(postDescriptor: PostDescriptor, loaderType: LoaderType): Boolean {
    when (val descriptor = postDescriptor.descriptor) {
      is ChanDescriptor.CatalogDescriptor -> {
        return chanThreadsCache.getCatalog(descriptor)
          ?.getPost(postDescriptor)
          ?.isContentLoadedForLoader(loaderType)
          ?: false
      }
      is ChanDescriptor.ThreadDescriptor -> {
        return chanThreadsCache.getThread(descriptor)
          ?.getPost(postDescriptor)
          ?.isContentLoadedForLoader(loaderType)
          ?: false
      }
    }
  }

  fun setContentLoadedForLoader(postDescriptor: PostDescriptor, loaderType: LoaderType) {
    when (val descriptor = postDescriptor.descriptor) {
      is ChanDescriptor.CatalogDescriptor -> {
        chanThreadsCache.getCatalog(descriptor)
          ?.getPost(postDescriptor)
          ?.setContentLoadedForLoader(loaderType)
      }
      is ChanDescriptor.ThreadDescriptor -> {
        chanThreadsCache.getThread(descriptor)
          ?.getPost(postDescriptor)
          ?.setContentLoadedForLoader(loaderType)
      }
    }
  }

  private suspend fun loadInternal(
    chanDescriptor: ChanDescriptor,
    chanCacheUpdateOptions: ChanCacheUpdateOptions,
    chanLoadOptions: ChanLoadOptions,
    chanCacheOptions: ChanCacheOptions,
    chanReadOptions: ChanReadOptions
  ): ModularResult<ThreadLoadResult> {
    BackgroundUtils.ensureMainThread()

    awaitUntilDependenciesInitialized()

    when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> {
        Logger.d(TAG, "loadInternal() Requested thread /$chanDescriptor/")
      }
      is ChanDescriptor.CatalogDescriptor -> {
        Logger.d(TAG, "loadInternal() Requested catalog /$chanDescriptor/")
      }
    }

    val site = siteManager.bySiteDescriptor(chanDescriptor.siteDescriptor())
    if (site == null) {
      val error = CommonClientException("Couldn't find site ${chanDescriptor.siteDescriptor()}")
      return ModularResult.value(ThreadLoadResult.Error(ChanLoaderException(error)))
    }

    if (!chanThreadsCache.cacheNeedsUpdate(chanDescriptor, chanCacheUpdateOptions)) {
      Logger.d(TAG, "loadInternal() chanThreadsCache.cacheNeedsUpdate($chanDescriptor, $chanCacheUpdateOptions) -> false")

      // Do not load new posts from the network, just refresh memory caches with data from the
      //  database
      when (chanDescriptor) {
        is ChanDescriptor.ThreadDescriptor -> {
          val siteDescriptor = chanDescriptor.siteDescriptor()

          val postParser = siteManager.bySiteDescriptor(siteDescriptor)
            ?.chanReader()
            ?.getParser()

          if (postParser == null) {
            val threadLoadResult = ThreadLoadResult.Error(
              ChanLoaderException(SiteManager.SiteNotFoundException(siteDescriptor))
            )
            return ModularResult.value(threadLoadResult)
          }

          val postsToReloadOptions = when (val chanLoadOption = chanLoadOptions.chanLoadOption) {
            is ChanLoadOption.DeletePostsFromMemoryCache -> {
              PostsToReloadOptions.Reload(chanLoadOption.postDescriptors.toSet())
            }
            is ChanLoadOption.ForceUpdatePosts -> {
              PostsToReloadOptions.Reload(chanLoadOption.postDescriptors.toSet())
            }
            ChanLoadOption.ClearMemoryAndDatabaseCaches,
            ChanLoadOption.ClearMemoryCache,
            ChanLoadOption.RetainAll -> PostsToReloadOptions.ReloadAll
          }

          val result = chanThreadLoaderCoordinator.reloadAndReparseThread(
            postParser = postParser,
            threadDescriptor = chanDescriptor,
            cacheUpdateOptions = chanCacheUpdateOptions,
            postsToReloadOptions = postsToReloadOptions
          )

          if (result is ModularResult.Error) {
            return result
          }

          val threadLoadResult = (result as ModularResult.Value).value
          if (threadLoadResult !is ThreadLoadResult.Error || !threadLoadResult.exception.isCacheEmptyException()) {
            return result
          }

          // fallthrough
        }
        is ChanDescriptor.CatalogDescriptor -> {
          return chanThreadLoaderCoordinator.reloadCatalogFromDatabase(chanDescriptor)
        }
      }
    }

    Logger.d(TAG, "loadInternal() chanThreadsCache.cacheNeedsUpdate($chanDescriptor, $chanCacheUpdateOptions) -> true")

    // Notify the bookmarksManager that loader is starting to fetch data from the server so that
    //  bookmarksManager can start loading bookmark info for this thread
    if (chanDescriptor is ChanDescriptor.ThreadDescriptor) {
      bookmarksManager.onThreadIsFetchingData(chanDescriptor)
    }

    return chanThreadLoaderCoordinator.loadThreadOrCatalog(
      url = getChanUrl(site, chanDescriptor).toString(),
      chanDescriptor = chanDescriptor,
      chanCacheOptions = chanCacheOptions,
      cacheUpdateOptions = chanCacheUpdateOptions,
      chanReadOptions = chanReadOptions,
      chanReader = site.chanReader()
    )
  }

  private fun getChanUrl(site: Site, chanDescriptor: ChanDescriptor): HttpUrl {
    return getChanUrlFullLoad(site, chanDescriptor)

    // TODO(KurobaEx v0.8.0): 2ch.hk API v2 support
//    val isThreadCached = if (chanDescriptor is ChanDescriptor.ThreadDescriptor) {
//      chanThreadsCache.getThreadPostsCount(chanDescriptor) > 1
//    } else {
//      false
//    }
//
//    if (!isThreadCached || chanDescriptor is ChanDescriptor.CatalogDescriptor) {
//      return getChanUrlFullLoad(site, chanDescriptor)
//    }
//
//    val threadDescriptor = chanDescriptor as ChanDescriptor.ThreadDescriptor
//
//    val lastPost = chanThreadsCache.getLastPost(threadDescriptor)
//    if (lastPost == null) {
//      return getChanUrlFullLoad(site, chanDescriptor)
//    }
//
//    val threadPartialLoadUrl = getChanUrlPartialLoad(site, threadDescriptor, lastPost.postDescriptor)
//    if (threadPartialLoadUrl == null) {
//      // Not supported by the site
//      return getChanUrlFullLoad(site, chanDescriptor)
//    }
//
//    return threadPartialLoadUrl
  }

  private fun getChanUrlFullLoad(site: Site, chanDescriptor: ChanDescriptor): HttpUrl {
    return when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> site.endpoints().thread(chanDescriptor)
      is ChanDescriptor.CatalogDescriptor -> site.endpoints().catalog(chanDescriptor.boardDescriptor)
      else -> throw IllegalArgumentException("Unknown mode")
    }
  }

  private fun getChanUrlPartialLoad(
    site: Site,
    threadDescriptor: ChanDescriptor.ThreadDescriptor,
    postDescriptor: PostDescriptor
  ): HttpUrl? {
    check(threadDescriptor == postDescriptor.threadDescriptor()) {
      "ThreadDescriptor ($threadDescriptor) differs from descriptor in this PostDescriptor ($postDescriptor)"
    }

    return site.endpoints().threadPartial(postDescriptor)
  }

  companion object {
    private const val TAG = "ChanThreadManager"
    private const val CATALOG_PREVIEW_POSTS_COUNT = 6 // Original post + 5 last posts
  }
}