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
package com.github.k1rakishou.chan.core.site

import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.Setting
import com.github.k1rakishou.SharedPreferencesSettingProvider
import com.github.k1rakishou.chan.Chan
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.base.okhttp.RealProxiedOkHttpClient
import com.github.k1rakishou.chan.core.image.ImageLoaderV2
import com.github.k1rakishou.chan.core.manager.ArchivesManager
import com.github.k1rakishou.chan.core.manager.BoardManager
import com.github.k1rakishou.chan.core.manager.PostFilterManager
import com.github.k1rakishou.chan.core.manager.ReplyManager
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.net.JsonReaderRequest
import com.github.k1rakishou.chan.core.repository.StaticBoardFlagInfoRepository
import com.github.k1rakishou.chan.core.site.http.HttpCallManager
import com.github.k1rakishou.chan.core.site.parser.MockReplyManager
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.getString
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.ModularResult
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.board.ChanBoard
import com.github.k1rakishou.model.data.descriptor.BoardDescriptor
import com.github.k1rakishou.model.data.site.SiteBoards
import com.github.k1rakishou.persist_state.ReplyMode
import com.github.k1rakishou.prefs.OptionsSetting
import com.github.k1rakishou.prefs.StringSetting
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.security.SecureRandom
import java.util.*
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

abstract class SiteBase : Site, CoroutineScope {
  private val job = SupervisorJob()

  @Inject
  lateinit var proxiedOkHttpClient: RealProxiedOkHttpClient
    protected set
  @Inject
  lateinit var appConstants: AppConstants
    protected set

  @Inject
  protected lateinit var httpCallManager: HttpCallManager
  @Inject
  protected lateinit var siteManager: SiteManager
  @Inject
  protected lateinit var imageLoaderV2: ImageLoaderV2
  @Inject
  protected lateinit var archivesManager: ArchivesManager
  @Inject
  protected lateinit var boardManager: BoardManager
  @Inject
  protected lateinit var postFilterManager: PostFilterManager
  @Inject
  protected lateinit var mockReplyManager: MockReplyManager
  @Inject
  protected lateinit var replyManager: ReplyManager
  @Inject
  protected lateinit var gson: Gson
  @Inject
  protected lateinit var staticBoardFlagInfoRepository: StaticBoardFlagInfoRepository

  override val coroutineContext: CoroutineContext
    get() = job + Dispatchers.Main + CoroutineName("SiteBase")

  protected val prefs by lazy {
    val sharedPrefs = AppModuleAndroidUtils.getPreferencesForSite(siteDescriptor())
    return@lazy SharedPreferencesSettingProvider(sharedPrefs)
  }

  lateinit var concurrentFileDownloadingChunks: OptionsSetting<ChanSettings.ConcurrentFileDownloadingChunks>
  lateinit var cloudFlareClearanceCookie: StringSetting
  lateinit var lastUsedReplyMode: OptionsSetting<ReplyMode>

  private var initialized = false

  override fun initialize() {
    if (initialized) {
      throw IllegalStateException("Already initialized")
    }

    Chan.getComponent()
      .inject(this)

    initialized = true
  }

  override fun postInitialize() {
    concurrentFileDownloadingChunks = OptionsSetting(
      prefs,
      "concurrent_download_chunk_count",
      ChanSettings.ConcurrentFileDownloadingChunks::class.java,
      ChanSettings.ConcurrentFileDownloadingChunks.Two
    )

    cloudFlareClearanceCookie = StringSetting(
      prefs,
      "cloud_flare_clearance_cookie",
      ""
    )

    lastUsedReplyMode = OptionsSetting(
      prefs,
      "last_used_reply_mode",
      ReplyMode::class.java,
      ReplyMode.Unknown
    )
  }

  override fun loadBoardInfo(callback: ((ModularResult<JsonReaderRequest.JsonReaderResponse<SiteBoards>>) -> Unit)?) {
    if (!enabled()) {
      val response = JsonReaderRequest.JsonReaderResponse.Success(SiteBoards(siteDescriptor(), emptyList()))
      callback?.invoke(ModularResult.value(response))
      return
    }

    if (!boardsType().canList) {
      val response = JsonReaderRequest.JsonReaderResponse.Success(SiteBoards(siteDescriptor(), emptyList()))
      callback?.invoke(ModularResult.value(response))
      return
    }

    launch(Dispatchers.IO) {
      val result = ModularResult.Try {
        boardManager.awaitUntilInitialized()
        Logger.d(TAG, "Requesting boards for site ${name()}")

        val readerResponse = actions().boards()

        when (readerResponse) {
          is JsonReaderRequest.JsonReaderResponse.Success -> {
            boardManager.createOrUpdateBoards(readerResponse.result.boards)

            Logger.d(TAG, "Got the boards for site ${readerResponse.result.siteDescriptor.siteName}, " +
              "boards count = ${readerResponse.result.boards.size}")
          }
          is JsonReaderRequest.JsonReaderResponse.ServerError -> {
            Logger.e(TAG, "Couldn't get site boards, bad status code: ${readerResponse.statusCode}")
          }
          is JsonReaderRequest.JsonReaderResponse.UnknownServerError -> {
            Logger.e(TAG, "Couldn't get site boards, unknown server error", readerResponse.error)
          }
          is JsonReaderRequest.JsonReaderResponse.ParsingError -> {
            Logger.e(TAG, "Couldn't get site boards, parsing error", readerResponse.error)
          }
        }

        return@Try readerResponse
      }

      if (callback != null) {
        callback.invoke(result)
        return@launch
      }

      if (result is ModularResult.Error) {
        Logger.e(TAG, "loadBoardInfo error", result.error)
        throw result.error
      }
    }
  }

  override fun board(code: String): ChanBoard? {
    val boardDescriptor = BoardDescriptor.create(siteDescriptor(), code)
    return boardManager.byBoardDescriptor(boardDescriptor)
  }

  override fun <T : Setting<*>> getSettingBySettingId(settingId: SiteSetting.SiteSettingId): T? {
    return when (settingId) {
      SiteSetting.SiteSettingId.CloudFlareClearanceCookie -> cloudFlareClearanceCookie as T
      SiteSetting.SiteSettingId.LastUsedReplyMode -> lastUsedReplyMode as T
      // 4chan only
      SiteSetting.SiteSettingId.LastUsedCountryFlagPerBoard -> null
      // 2ch.hk only
      SiteSetting.SiteSettingId.DvachUserCodeCookie -> null
      // 2ch.hk only
      SiteSetting.SiteSettingId.DvachAntiSpamCookie -> null
    }
  }

  override fun settings(): List<SiteSetting> {
    return listOf<SiteSetting>(
      SiteSetting.SiteOptionsSetting(
        getString(R.string.settings_concurrent_file_downloading_name),
        getString(R.string.settings_concurrent_file_downloading_description),
        concurrentFileDownloadingChunks,
        ChanSettings.ConcurrentFileDownloadingChunks.values().map { it.name }
      ),
      SiteSetting.SiteStringSetting(
        getString(R.string.cloud_flare_cookie_setting_title),
        getString(R.string.cloud_flare_cookie_setting_description),
        cloudFlareClearanceCookie
      )
    )
  }

  override suspend fun createBoard(boardName: String, boardCode: String): ModularResult<ChanBoard?> {
    val existing = board(boardCode)
    if (existing != null) {
      return ModularResult.value(existing)
    }

    val boardDescriptor = BoardDescriptor.create(siteDescriptor(), boardCode)
    val board = ChanBoard.create(boardDescriptor, boardName)

    val created = boardManager.createOrUpdateBoards(listOf(board))
    if (!created) {
      return ModularResult.value(null)
    }

    return ModularResult.value(board)
  }

  companion object {
    private const val TAG = "SiteBase"
    val secureRandom: Random = SecureRandom()

    @JvmStatic
    fun containsMediaHostUrl(desiredSiteUrl: HttpUrl, siteMediaUrls: Array<HttpUrl>): Boolean {
      val desiredHost = desiredSiteUrl.host

      for (siteMediaUrl in siteMediaUrls) {
        val siteMediaHost = siteMediaUrl.host

        if (desiredHost == siteMediaHost) {
          return true
        }

        if (desiredHost == "www.$siteMediaHost") {
          return true
        }
      }

      return false
    }
  }
}