/*
 * KurobaEx - *chan browser https://github.com/K1rakishou/Kuroba-Experimental/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.k1rakishou.chan.core.manager

import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.chan.features.reencoding.ImageReencodingPresenter
import com.github.k1rakishou.chan.features.reply.data.Reply
import com.github.k1rakishou.chan.features.reply.data.ReplyFile
import com.github.k1rakishou.chan.features.reply.data.ReplyFileMeta
import com.github.k1rakishou.chan.features.reply.data.ReplyFilesStorage
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.common.ModularResult.Companion.Try
import com.github.k1rakishou.common.StringUtils
import com.github.k1rakishou.common.SuspendableInitializer
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

/**
 * Manages replies.
 */
class ReplyManager(
  private val appConstants: AppConstants,
  commonGson: Gson
) {
  @Volatile
  private var filesLoaded = false
  private val filesLoadedInitializer = SuspendableInitializer<Unit>("filesLoadedInitializer")

  private val gson = commonGson.newBuilder()
    .excludeFieldsWithoutExposeAnnotation()
    .setVersion(REPLY_FILE_META_GSON_VERSION)
    .create()

  private val drafts: MutableMap<ChanDescriptor, Reply> = HashMap()
  private val replyFilesStorage by lazy { ReplyFilesStorage(gson, appConstants) }

  @OptIn(ExperimentalTime::class)
  suspend fun awaitUntilFilesAreLoaded() {
    if (filesLoadedInitializer.isInitialized()) {
      return
    }

    Logger.d(TAG, "ReplyManager is not ready yet, waiting...")
    val duration = measureTime { filesLoadedInitializer.awaitUntilInitialized() }
    Logger.d(TAG, "ReplyManager initialization completed, took $duration")
  }

  @Synchronized
  fun addNewReplyFileIntoStorage(replyFile: ReplyFile): Boolean {
    ensureFilesLoaded()
    return replyFilesStorage.addNewReplyFile(replyFile)
  }

  @Synchronized
  fun updateFileSelection(fileUuid: UUID, selected: Boolean, notifyListeners: Boolean): ModularResult<Boolean> {
    ensureFilesLoaded()
    return replyFilesStorage.updateFileSelection(fileUuid, selected, notifyListeners)
  }

  @Synchronized
  fun updateFileSpoilerFlag(fileUuid: UUID, spoiler: Boolean, notifyListeners: Boolean): ModularResult<Boolean> {
    ensureFilesLoaded()
    return replyFilesStorage.updateFileSpoilerFlag(fileUuid, spoiler, notifyListeners)
  }

  @Synchronized
  fun deleteFile(fileUuid: UUID, notifyListeners: Boolean): ModularResult<Unit> {
    ensureFilesLoaded()
    return replyFilesStorage.deleteFile(fileUuid, notifyListeners)
  }

  @Synchronized
  fun deleteSelectedFiles(notifyListeners: Boolean): ModularResult<Unit> {
    ensureFilesLoaded()
    return replyFilesStorage.deleteSelectedFiles(notifyListeners)
  }

  @Synchronized
  fun hasSelectedFiles(): ModularResult<Boolean> {
    ensureFilesLoaded()
    return replyFilesStorage.hasSelectedFiles()
  }

  @Synchronized
  fun isSelected(fileUuid: UUID): ModularResult<Boolean> {
    ensureFilesLoaded()
    return replyFilesStorage.isSelected(fileUuid)
  }

  @Synchronized
  fun isMarkedAsSpoiler(fileUuid: UUID): ModularResult<Boolean> {
    ensureFilesLoaded()
    return replyFilesStorage.isMarkedAsSpoiler(fileUuid)
  }

  @Synchronized
  fun selectedFilesCount(): ModularResult<Int> {
    ensureFilesLoaded()
    return replyFilesStorage.selectedFilesCount()
  }

  @Synchronized
  fun totalFilesCount(): ModularResult<Int> {
    ensureFilesLoaded()
    return replyFilesStorage.totalFilesCount()
  }

  @Synchronized
  fun reloadFilesFromDisk(appConstants: AppConstants): ModularResult<Unit> {
    if (filesLoaded) {
      return ModularResult.value(Unit)
    }

    val result = replyFilesStorage.reloadAllFilesFromDisk(
      appConstants.attachFilesDir,
      appConstants.attachFilesMetaDir,
      appConstants.mediaPreviewsDir
    )

    filesLoaded = true
    filesLoadedInitializer.initWithValue(Unit)

    if (result is ModularResult.Error) {
      Logger.e(TAG, "reloadAllFilesFromDisk() error, clearing all files", result.error)
      replyFilesStorage.deleteAllFiles()
      return result
    }

    return ModularResult.value(Unit)
  }

  @Synchronized
  fun takeSelectedFiles(chanDescriptor: ChanDescriptor): ModularResult<Boolean> {
    ensureFilesLoaded()

    return Try {
      return@Try readReply(chanDescriptor) { reply ->
        val selectedFiles = replyFilesStorage.selectedFilesCount().unwrap()
        val takenFiles = replyFilesStorage.takeSelectedFiles(chanDescriptor).unwrap()

        if (takenFiles.size != selectedFiles) {
          Logger.e(TAG, "takeSelectedFiles($chanDescriptor) failed to take some of selected files, " +
            "takenFiles.size=${takenFiles.size}, selectedFiles=$selectedFiles")
          return@readReply false
        }

        reply.putReplyFiles(takenFiles)
        return@readReply true
      }
    }
  }

  @Synchronized
  fun restoreFiles(chanDescriptor: ChanDescriptor) {
    ensureFilesLoaded()

    readReply(chanDescriptor) { reply ->
      if (!replyFilesStorage.putFiles(reply.getAndConsumeFiles())) {
        Logger.e(TAG, "replyFiles.putFiles() Not all files were put back")
      }
    }
  }

  @Synchronized
  fun iterateFilesOrdered(iterator: (Int, ReplyFile, ReplyFileMeta) -> Unit) {
    replyFilesStorage.iterateFilesOrdered(iterator)
  }

  @Synchronized
  fun iterateSelectedFilesOrdered(iterator: (Int, ReplyFile, ReplyFileMeta) -> Unit) {
    replyFilesStorage.iterateFilesOrdered { order, replyFile, replyFileMeta ->
      if (!replyFileMeta.selected) {
        return@iterateFilesOrdered
      }

      iterator(order, replyFile, replyFileMeta)
    }
  }

  @Synchronized
  fun getSelectedFilesOrdered(): List<ReplyFile> {
    val files = mutableListOf<ReplyFile>()

    replyFilesStorage.iterateFilesOrdered { i, replyFile, replyFileMeta ->
      if (replyFileMeta.selected) {
        files += replyFile
      }
    }

    return files
  }

  @Synchronized
  fun getReplyFileByFileUuid(fileUuid: UUID): ModularResult<ReplyFile?> {
    ensureFilesLoaded()
    return replyFilesStorage.getReplyFileByFileUuid(fileUuid)
  }

  @Synchronized
  fun cleanupFiles(chanDescriptor: ChanDescriptor, notifyListeners: Boolean) {
    ensureFilesLoaded()

    readReply(chanDescriptor) { reply ->
      val fileUuids = reply.cleanupFiles()
        .peekError { error -> Logger.e(TAG, "reply.cleanupFiles() error", error) }
        .valueOrNull()

      if (fileUuids == null || fileUuids.isEmpty()) {
        return@readReply
      }

      replyFilesStorage.deleteFiles(fileUuids, notifyListeners)
        .peekError { error -> Logger.e(TAG, "replyFilesStorage.deleteFiles($fileUuids) error", error) }
    }
  }

  @Synchronized
  fun <T> mapOrderedNotNull(mapper: (Int, ReplyFile) -> T?): List<T> {
    ensureFilesLoaded()
    return replyFilesStorage.mapOrderedNotNull(mapper)
  }

  fun listenForReplyFilesUpdates(): Flow<Unit> {
    return replyFilesStorage.listenForReplyFilesUpdates()
  }

  @Synchronized
  fun getReplyOrCreateNew(chanDescriptor: ChanDescriptor): Reply {
    ensureFilesLoaded()

    var reply = drafts[chanDescriptor]
    if (reply == null) {
      reply = Reply(chanDescriptor)
      drafts[chanDescriptor] = reply
    }

    if (reply.postName.isEmpty()) {
      reply.postName = ChanSettings.postDefaultName.get()
    }

    return reply
  }

  @Synchronized
  fun getReplyOrNull(chanDescriptor: ChanDescriptor): Reply? {
    ensureFilesLoaded()
    return drafts[chanDescriptor]
  }

  @Synchronized
  fun containsReply(chanDescriptor: ChanDescriptor): Boolean {
    ensureFilesLoaded()
    return drafts.containsKey(chanDescriptor)
  }

  @Synchronized
  fun <T : Any?> readReply(chanDescriptor: ChanDescriptor, reader: (Reply) -> T): T {
    ensureFilesLoaded()
    return reader(getReplyOrCreateNew(chanDescriptor))
  }

  @Synchronized
  fun createNewEmptyAttachFile(
    uniqueFileName: UniqueFileName,
    originalFileName: String,
    addedOn: Long
  ): ReplyFile? {
    BackgroundUtils.ensureBackgroundThread()

    val attachFile = File(
      appConstants.attachFilesDir,
      uniqueFileName.fullFileName
    )

    val attachFileMeta = File(
      appConstants.attachFilesMetaDir,
      uniqueFileName.fullFileMetaName
    )

    val previewFile = File(
      appConstants.mediaPreviewsDir,
      uniqueFileName.previewFileName
    )

    try {
      if (!attachFile.exists()) {
        if (!attachFile.createNewFile()) {
          throw IOException("Failed to create attach file: " + attachFile.absolutePath)
        }
      }

      if (!attachFileMeta.exists()) {
        if (!attachFileMeta.createNewFile()) {
          throw IOException("Failed to create attach file meta: " + attachFileMeta.absolutePath)
        }
      }

      if (!previewFile.exists()) {
        if (!previewFile.createNewFile()) {
          throw IOException("Failed to create preview file: " + previewFile.absolutePath)
        }
      }

      val replyFile = ReplyFile(gson, attachFile, attachFileMeta, previewFile)

      replyFile.storeFileMetaInfo(
        ReplyFileMeta(
          fileUuidStringNullable = uniqueFileName.fileUuid.toString(),
          originalFileNameNullable = originalFileName,
          fileNameNullable = originalFileName,
          addedOnNullable = addedOn
        )
      ).unwrap()

      return replyFile
    } catch (error: Throwable) {
      Logger.e(TAG, "Failed to create new empty attach file ${uniqueFileName.fullFileName}", error)

      attachFile.delete()
      attachFileMeta.delete()
      previewFile.delete()

      return null
    }
  }

  @Synchronized
  fun generateUniqueFileName(appConstants: AppConstants): UniqueFileName {
    BackgroundUtils.ensureBackgroundThread()

    val attachFilesDir = appConstants.attachFilesDir
    val attachFilesMetaDir = appConstants.attachFilesMetaDir

    val filesInDir = attachFilesDir.listFiles()
    val metaFilesInDir = attachFilesMetaDir.listFiles()

    val allFileNamesSet = filesInDir
      ?.map { file -> file.absolutePath }
      ?.toSet()
      ?: emptySet()

    val allMetaFileNamesSet = metaFilesInDir
      ?.map { file -> file.absolutePath }
      ?.toSet()
      ?: emptySet()

    while (true) {
      val uuid = UUID.randomUUID()
      val fileName = getFileName(uuid.toString())
      val metaFileName = getMetaFileName(uuid.toString())
      val previewFileName = getPreviewFileName(uuid.toString())

      if ((filesInDir == null || filesInDir.isEmpty())
        && (metaFilesInDir == null || metaFilesInDir.isEmpty())) {
        return UniqueFileName(uuid, fileName, metaFileName, previewFileName)
      }

      if (fileName in allFileNamesSet) {
        continue
      }

      if (metaFileName in allMetaFileNamesSet) {
        continue
      }

      return UniqueFileName(
        fileUuid = uuid,
        fullFileName = fileName,
        fullFileMetaName = metaFileName,
        previewFileName = previewFileName
      )
    }
  }

  fun getNewImageName(
    currentFileName: String,
    newType: ImageReencodingPresenter.ReencodeType = ImageReencodingPresenter.ReencodeType.AS_IS
  ): String {
    var currentExt = StringUtils.extractFileNameExtension(currentFileName)
    currentExt = if (currentExt == null) {
      ""
    } else {
      ".$currentExt"
    }

    return when (newType) {
      ImageReencodingPresenter.ReencodeType.AS_PNG -> System.currentTimeMillis().toString() + ".png"
      ImageReencodingPresenter.ReencodeType.AS_JPEG -> System.currentTimeMillis().toString() + ".jpg"
      ImageReencodingPresenter.ReencodeType.AS_IS -> System.currentTimeMillis().toString() + currentExt
      else -> System.currentTimeMillis().toString() + currentExt
    }
  }

  @Synchronized
  private fun ensureFilesLoaded() {
    check(filesLoaded) { "Files are not loaded yet!" }
  }

  data class UniqueFileName(
    val fileUuid: UUID,
    val fullFileName: String,
    val fullFileMetaName: String,
    val previewFileName: String
  )

  companion object {
    private const val TAG = "ReplyManager"
    private const val REPLY_FILE_META_GSON_VERSION = 1.0

    const val ATTACH_FILE_NAME = "attach_file"
    const val ATTACH_FILE_META_NAME = "attach_file_meta"
    const val PREVIEW_FILE_NAME = "preview"

    fun getFileName(uuid: String) = "${ATTACH_FILE_NAME}_$uuid"
    fun getMetaFileName(uuid: String) = "${ATTACH_FILE_META_NAME}_$uuid"
    fun getPreviewFileName(uuid: String) = "${PREVIEW_FILE_NAME}_$uuid"

    fun extractUuidOrNull(fileName: String): UUID? {
      val attachFileName = "${ATTACH_FILE_NAME}_"
      if (fileName.startsWith(attachFileName)) {
        return uuidFromStringOrNull(fileName.substringAfter(attachFileName))
      }

      val attachFileMetaName = "${ATTACH_FILE_META_NAME}_"
      if (fileName.startsWith(attachFileMetaName)) {
        return uuidFromStringOrNull(fileName.substringAfter(attachFileMetaName))
      }

      return null
    }

    private fun uuidFromStringOrNull(uuidString: String): UUID? {
      return try {
        UUID.fromString(uuidString)
      } catch (error: Throwable) {
        Logger.e(TAG, "Bad UUID='$uuidString'")
        return null
      }
    }
  }
}