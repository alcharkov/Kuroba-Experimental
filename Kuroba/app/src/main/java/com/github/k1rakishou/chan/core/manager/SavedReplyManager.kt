package com.github.k1rakishou.chan.core.manager

import androidx.annotation.GuardedBy
import com.github.k1rakishou.chan.core.usecase.ParsePostRepliesUseCase
import com.github.k1rakishou.common.hashSetWithCap
import com.github.k1rakishou.common.mutableMapWithCap
import com.github.k1rakishou.common.putIfNotContains
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.post.ChanSavedReply
import com.github.k1rakishou.model.repository.ChanSavedReplyRepository
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

class SavedReplyManager(
  private val verboseLogsEnabled: Boolean,
  private val savedReplyRepository: ChanSavedReplyRepository
) {
  private val lock = ReentrantReadWriteLock()
  @GuardedBy("lock")
  private val savedReplyMap = mutableMapWithCap<ChanDescriptor.ThreadDescriptor, MutableList<ChanSavedReply>>(128)
  @GuardedBy("lock")
  private val alreadyPreloadedSet = hashSetWithCap<ChanDescriptor.ThreadDescriptor>(128)

  @OptIn(ExperimentalTime::class)
  suspend fun preloadForThread(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    val alreadyPreloaded = lock.read { alreadyPreloadedSet.contains(threadDescriptor) }
    if (alreadyPreloaded) {
      return
    }

    if (verboseLogsEnabled) {
      Logger.d(TAG, "preloadForThread($threadDescriptor) begin")
    }

    val time = measureTime { preloadForThreadInternal(threadDescriptor) }

    if (verboseLogsEnabled) {
      Logger.d(TAG, "preloadForThread($threadDescriptor) end, took $time")
    }
  }

  private suspend fun preloadForThreadInternal(threadDescriptor: ChanDescriptor.ThreadDescriptor) {
    val savedReplies = savedReplyRepository.preloadForThread(threadDescriptor)
      .safeUnwrap { error ->
        Logger.e(TAG, "Error while trying to preload saved replies for thread ($threadDescriptor)", error)
        return
      }

    lock.write {
      savedReplyMap[threadDescriptor] = savedReplies.toMutableList()
      alreadyPreloadedSet.add(threadDescriptor)
    }
  }

  fun isSaved(chanDescriptor: ChanDescriptor, postNo: Long, postSubNo: Long): Boolean {
    val threadDescriptor = when (chanDescriptor) {
      is ChanDescriptor.ThreadDescriptor -> chanDescriptor
      is ChanDescriptor.CatalogDescriptor -> ChanDescriptor.ThreadDescriptor.create(chanDescriptor, postNo)
    }

    return lock.read {
      return@read savedReplyMap[threadDescriptor]?.any { chanSavedReply ->
        chanSavedReply.postDescriptor.postNo == postNo
          && chanSavedReply.postDescriptor.postSubNo == postSubNo
      } ?: false
    }
  }

  fun isSaved(postDescriptor: PostDescriptor): Boolean {
    return lock.read {
      return@read savedReplyMap[postDescriptor.threadDescriptor()]?.any { chanSavedReply ->
        chanSavedReply.postDescriptor == postDescriptor
      } ?: false
    }
  }

  fun getSavedReply(postDescriptor: PostDescriptor): ChanSavedReply? {
    return lock.read {
      return@read savedReplyMap[postDescriptor.threadDescriptor()]?.firstOrNull { chanSavedReply ->
        chanSavedReply.postDescriptor == postDescriptor
      }
    }
  }

  fun getAllRepliesCatalog(
    catalogDescriptor: ChanDescriptor.CatalogDescriptor,
    threadPostIds: Set<Long>
  ): List<ChanSavedReply> {
    return lock.read {
      return@read threadPostIds.mapNotNull { threadPostId ->
        val threadDescriptor = ChanDescriptor.ThreadDescriptor.create(catalogDescriptor, threadPostId)

        return@mapNotNull savedReplyMap[threadDescriptor]
          ?.firstOrNull { chanSavedReply -> chanSavedReply.postDescriptor.postNo == threadPostId }
      }
    }
  }

  fun getAllRepliesThread(threadDescriptor: ChanDescriptor.ThreadDescriptor): List<ChanSavedReply> {
    return lock.read {
      return@read savedReplyMap[threadDescriptor]
        ?: emptyList()
    }
  }

  suspend fun unsavePost(postDescriptor: PostDescriptor) {
    savedReplyRepository.unsavePost(postDescriptor)
      .safeUnwrap { error ->
        Logger.e(TAG, "unsavePost($postDescriptor) error", error)
        return
      }

    lock.write {
      val index = savedReplyMap[postDescriptor.threadDescriptor()]
        ?.indexOfFirst { chanSavedReply -> chanSavedReply.postDescriptor == postDescriptor } ?: -1

      if (index >= 0) {
        savedReplyMap[postDescriptor.threadDescriptor()]?.removeAt(index)
      }
    }
  }

  suspend fun savePost(postDescriptor: PostDescriptor) {
    val savedReply = ChanSavedReply(postDescriptor)
    saveReply(savedReply)
  }

  suspend fun saveReply(savedReply: ChanSavedReply) {
    val postDescriptor = savedReply.postDescriptor

    savedReplyRepository.savePost(savedReply)
      .safeUnwrap { error ->
        Logger.e(TAG, "savePost($postDescriptor) error", error)
        return
      }

    lock.write {
      val index = savedReplyMap[postDescriptor.threadDescriptor()]
        ?.indexOfFirst { chanSavedReply -> chanSavedReply.postDescriptor == postDescriptor } ?: -1

      if (index < 0) {
        savedReplyMap[postDescriptor.threadDescriptor()]?.add(savedReply)
      }
    }
  }

  fun retainSavedPostNoMap(
    quoteOwnerPostsMap: Map<Long, Set<ParsePostRepliesUseCase.TempReplyToMyPost>>,
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ): Map<Long, MutableSet<ParsePostRepliesUseCase.TempReplyToMyPost>> {
    val resultMap: MutableMap<Long, MutableSet<ParsePostRepliesUseCase.TempReplyToMyPost>> = HashMap(16)

    lock.read {
      val savedRepliesNoSet = savedReplyMap[threadDescriptor]
        ?.map { chanSavedReply -> chanSavedReply.postDescriptor.postNo }
        ?.toSet()
        ?: return@read

      for ((quotePostNo, tempReplies) in quoteOwnerPostsMap) {
        for (tempReply in tempReplies) {
          if (!savedRepliesNoSet.contains(quotePostNo)) {
            continue
          }

          resultMap.putIfNotContains(quotePostNo, mutableSetOf())
          resultMap[quotePostNo]!!.add(tempReply)
        }
      }
    }

    return resultMap
  }

  fun retainSavedPostNoMap(
    postList: List<PostDescriptor>,
    threadDescriptor: ChanDescriptor.ThreadDescriptor
  ): List<Long> {
    if (postList.isEmpty()) {
      return emptyList()
    }

    return lock.read {
      val savedRepliesNoSet = savedReplyMap[threadDescriptor]
        ?.map { chanSavedReply -> chanSavedReply.postDescriptor.postNo }
        ?.toSet()
        ?: return@read emptyList()

      return@read postList
        .filter { post -> savedRepliesNoSet.contains(post.postNo) }
        .map { post -> post.postNo }
    }
  }

  companion object {
    private const val TAG = "SavedReplyManager"
  }
}