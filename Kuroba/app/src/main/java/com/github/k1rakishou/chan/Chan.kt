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
package com.github.k1rakishou.chan

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import com.github.k1rakishou.BookmarkGridViewInfo
import com.github.k1rakishou.ChanSettings
import com.github.k1rakishou.ChanSettingsInfo
import com.github.k1rakishou.PersistableChanStateInfo
import com.github.k1rakishou.chan.core.AppDependenciesInitializer
import com.github.k1rakishou.chan.core.cache.downloader.FileCacheException
import com.github.k1rakishou.chan.core.cache.downloader.FileCacheException.FileNotFoundOnTheServerException
import com.github.k1rakishou.chan.core.di.component.application.ApplicationComponent
import com.github.k1rakishou.chan.core.di.component.application.DaggerApplicationComponent
import com.github.k1rakishou.chan.core.di.module.application.AppModule
import com.github.k1rakishou.chan.core.di.module.application.ExecutorsModule
import com.github.k1rakishou.chan.core.di.module.application.GsonModule
import com.github.k1rakishou.chan.core.di.module.application.LoaderModule
import com.github.k1rakishou.chan.core.di.module.application.ManagerModule
import com.github.k1rakishou.chan.core.di.module.application.NetModule
import com.github.k1rakishou.chan.core.di.module.application.ParserModule
import com.github.k1rakishou.chan.core.di.module.application.RepositoryModule
import com.github.k1rakishou.chan.core.di.module.application.RoomDatabaseModule
import com.github.k1rakishou.chan.core.di.module.application.SiteModule
import com.github.k1rakishou.chan.core.di.module.application.UseCaseModule
import com.github.k1rakishou.chan.core.diagnostics.AnrSupervisor
import com.github.k1rakishou.chan.core.helper.ImageSaverFileManagerWrapper
import com.github.k1rakishou.chan.core.manager.ApplicationVisibilityManager
import com.github.k1rakishou.chan.core.manager.HistoryNavigationManager
import com.github.k1rakishou.chan.core.manager.ReportManager
import com.github.k1rakishou.chan.core.manager.SettingsNotificationManager
import com.github.k1rakishou.chan.core.manager.ThreadBookmarkGroupManager
import com.github.k1rakishou.chan.core.manager.watcher.BookmarkWatcherCoordinator
import com.github.k1rakishou.chan.core.manager.watcher.FilterWatcherCoordinator
import com.github.k1rakishou.chan.ui.adapter.PostsFilter
import com.github.k1rakishou.chan.ui.settings.SettingNotificationType
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.getDimen
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.isDevBuild
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.isTablet
import com.github.k1rakishou.common.AndroidUtils
import com.github.k1rakishou.common.AndroidUtils.getApplicationLabel
import com.github.k1rakishou.common.AppConstants
import com.github.k1rakishou.common.dns.DnsOverHttpsSelector
import com.github.k1rakishou.common.dns.DnsOverHttpsSelectorFactory
import com.github.k1rakishou.common.dns.NormalDnsSelector
import com.github.k1rakishou.common.dns.NormalDnsSelectorFactory
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.core_spannable.SpannableModuleInjector
import com.github.k1rakishou.core_themes.ThemesModuleInjector
import com.github.k1rakishou.fsaf.BadPathSymbolResolutionStrategy
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.manager.base_directory.DirectoryManager
import com.github.k1rakishou.model.ModelModuleInjector
import com.github.k1rakishou.model.di.NetworkModule
import com.github.k1rakishou.persist_state.PersistableChanState
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.dnsoverhttps.DnsOverHttps
import org.greenrobot.eventbus.EventBus
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetAddress
import java.util.*
import javax.inject.Inject
import kotlin.system.exitProcess

class Chan : Application(), ActivityLifecycleCallbacks {
  private var activityForegroundCounter = 0

  private val job = SupervisorJob(null)
  private lateinit var applicationScope: CoroutineScope

  private val coroutineExceptionHandler = CoroutineExceptionHandler { _, exception ->
    onUnhandledException(exception, exceptionToString(UnhandlerExceptionHandlerType.Coroutines, exception))
  }

  private val tagPrefix by lazy { getApplicationLabel().toString() + " | " }

  @Inject
  lateinit var appDependenciesInitializer: AppDependenciesInitializer
  @Inject
  lateinit var bookmarkWatcherCoordinator: BookmarkWatcherCoordinator
  @Inject
  lateinit var filterWatcherCoordinator: FilterWatcherCoordinator
  @Inject
  lateinit var threadBookmarkGroupManager: ThreadBookmarkGroupManager
  @Inject
  lateinit var settingsNotificationManager: SettingsNotificationManager
  @Inject
  lateinit var applicationVisibilityManager: ApplicationVisibilityManager
  @Inject
  lateinit var historyNavigationManager: HistoryNavigationManager
  @Inject
  lateinit var reportManager: ReportManager
  @Inject
  lateinit var anrSupervisor: AnrSupervisor

  private val normalDnsCreatorFactory: NormalDnsSelectorFactory = object : NormalDnsSelectorFactory {
    override fun createDnsSelector(okHttpClient: OkHttpClient): NormalDnsSelector {
      Logger.d(AppModule.DI_TAG, "NormalDnsSelectorFactory created")

      if (ChanSettings.okHttpAllowIpv6.get()) {
        Logger.d(AppModule.DI_TAG, "Using DnsSelector.Mode.SYSTEM")
        return NormalDnsSelector(NormalDnsSelector.Mode.SYSTEM)
      }

      Logger.d(AppModule.DI_TAG, "Using DnsSelector.Mode.IPV4_ONLY")
      return NormalDnsSelector(NormalDnsSelector.Mode.IPV4_ONLY)
    }
  }

  private val dnsOverHttpsCreatorFactory: DnsOverHttpsSelectorFactory = object : DnsOverHttpsSelectorFactory {
    override fun createDnsSelector(okHttpClient: OkHttpClient): DnsOverHttpsSelector {
      Logger.d(AppModule.DI_TAG, "DnsOverHttpsSelectorFactory created")

      val selector = DnsOverHttps.Builder()
        .includeIPv6(ChanSettings.okHttpAllowIpv6.get())
        .client(okHttpClient)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
          listOf(
            InetAddress.getByName("162.159.36.1"),
            InetAddress.getByName("162.159.46.1"),
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
            InetAddress.getByName("162.159.132.53"),
            InetAddress.getByName("2606:4700:4700::1111"),
            InetAddress.getByName("2606:4700:4700::1001"),
            InetAddress.getByName("2606:4700:4700::0064"),
            InetAddress.getByName("2606:4700:4700::6400")
          )
        )
        .build()

      return DnsOverHttpsSelector(selector)
    }
  }

  private val okHttpProtocols: OkHttpProtocols
    get() {
      if (ChanSettings.okHttpAllowHttp2.get()) {
        Logger.d(AppModule.DI_TAG, "Using HTTP_2 and HTTP_1_1")
        return OkHttpProtocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
      }

      Logger.d(AppModule.DI_TAG, "Using HTTP_1_1")
      return OkHttpProtocols(listOf(Protocol.HTTP_1_1))
    }

  private val isEmulator: Boolean
    get() = (Build.MODEL.contains("google_sdk")
      || Build.MODEL.contains("Emulator")
      || Build.MODEL.contains("Android SDK"))


  val applicationInForeground: Boolean
    get() = activityForegroundCounter > 0

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)

    AndroidUtils.init(this)
    AppModuleAndroidUtils.init(this)
    Logger.init(tagPrefix, isDevBuild())
    ChanSettings.init(createChanSettingsInfo())
    PersistableChanState.init(createPersistableChanStateInfo())

    AppModuleAndroidUtils.printApplicationSignatureHash()

    // remove this if you need to debug some sort of event bus issue
    EventBus.builder()
      .logNoSubscriberMessages(false)
      .installDefaultEventBus()
  }

  override fun onCreate() {
    super.onCreate()

    val start = System.currentTimeMillis()
    onCreateInternal()
    val diff = System.currentTimeMillis() - start

    Logger.d(TAG, "Application initialization took " + diff + "ms")
  }

  private fun onCreateInternal() {
    registerActivityLifecycleCallbacks(this)
    applicationScope = CoroutineScope(job + Dispatchers.Main + CoroutineName("Chan") + coroutineExceptionHandler)

    val isDev = isDevBuild()
    val flavorType = AppModuleAndroidUtils.getFlavorType()

    if (isDev && ENABLE_STRICT_MODE) {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
          .detectAll()
          .penaltyLog()
          .penaltyFlashScreen()
          .build()
      )

      StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
          .detectAll()
          .penaltyLog()
          .build()
      )
    }

    System.setProperty("kotlinx.coroutines.debug", if (isDev) "on" else "off")

    val kurobaExUserAgent = buildString {
      append(getApplicationLabel())
      append(" ")
      append(BuildConfig.VERSION_NAME)
      append(".")
      append(BuildConfig.BUILD_NUMBER)
    }

    val appConstants = AppConstants(
      context = applicationContext,
      flavorType = flavorType,
      kurobaExUserAgent = kurobaExUserAgent,
      maxPostsInDatabaseSettingValue = ChanSettings.databaseMaxPostsCount.get(),
      maxThreadsInDatabaseSettingValue = ChanSettings.databaseMaxThreadsCount.get()
    )

    logAppConstantsAndSettings(appConstants)

    val okHttpProtocols = okHttpProtocols
    val fileManager = provideApplicationFileManager()
    val imageSaverFileManagerWrapper =  provideImageSaverFileManagerWrapper()

    val themeEngine = ThemesModuleInjector.build(
      application = this,
      scope = applicationScope,
      fileManager = fileManager
    ).getThemeEngine()

    themeEngine.initialize(this)
    SpannableModuleInjector.initialize(themeEngine)

    val modelComponent = ModelModuleInjector.build(
      application = this,
      scope = applicationScope,
      normalDnsSelectorFactory = normalDnsCreatorFactory,
      dnsOverHttpsSelectorFactory = dnsOverHttpsCreatorFactory,
      protocols = NetworkModule.OkHttpProtocolList(okHttpProtocols.protocols),
      verboseLogs = ChanSettings.verboseLogs.get(),
      isDevFlavor = isDev,
      okHttpUseDnsOverHttps = ChanSettings.okHttpUseDnsOverHttps.get(),
      appConstants = appConstants
    )

    // We need to start initializing ChanPostRepository first because it deletes old posts during
    // the initialization.
    modelComponent.getChanPostRepository().initialize()

    applicationComponent = DaggerApplicationComponent.builder()
      .application(this)
      .appContext(this)
      .themeEngine(themeEngine)
      .fileManager(fileManager)
      .imageSaverFileManagerWrapper(imageSaverFileManagerWrapper)
      .applicationCoroutineScope(applicationScope)
      .normalDnsSelectorFactory(normalDnsCreatorFactory)
      .dnsOverHttpsSelectorFactory(dnsOverHttpsCreatorFactory)
      .okHttpProtocols(okHttpProtocols)
      .appConstants(appConstants)
      .modelMainComponent(modelComponent)
      .appModule(AppModule())
      .executorsModule(ExecutorsModule())
      .roomDatabaseModule(RoomDatabaseModule())
      .gsonModule(GsonModule())
      .loaderModule(LoaderModule())
      .managerModule(ManagerModule())
      .netModule(NetModule())
      .repositoryModule(RepositoryModule())
      .siteModule(SiteModule())
      .parserModule(ParserModule())
      .useCaseModule(UseCaseModule())
      .build()
      .also { component -> component.inject(this) }

    anrSupervisor.start()
    appDependenciesInitializer.init()
    setupErrorHandlers()

    reportManager.postTask {
      if (ChanSettings.collectCrashLogs.get() || ChanSettings.collectANRs.get()) {
        if (reportManager.hasReportFiles()) {
          settingsNotificationManager.notify(SettingNotificationType.CrashLogOrAnr)
        }

        return@postTask
      }
    }

    anrSupervisor.onApplicationLoaded()
  }

  private fun setupErrorHandlers() {
    RxJavaPlugins.setErrorHandler { e: Throwable? ->
      var error = e

      if (error is UndeliverableException) {
        error = error.cause
      }

      if (error == null) {
        return@setErrorHandler
      }

      if (error is IOException) {
        // fine, irrelevant network problem or API that throws on cancellation
        return@setErrorHandler
      }

      if (error is InterruptedException) {
        // fine, some blocking code was interrupted by a dispose call
        return@setErrorHandler
      }

      if (error is RuntimeException && error.cause is InterruptedException) {
        // fine, DB synchronous call (via runTask) was interrupted when a reactive stream
        // was disposed of.
        return@setErrorHandler
      }

      if (error is FileCacheException.CancellationException
        || error is FileNotFoundOnTheServerException
      ) {
        // fine, sometimes they get through all the checks but it doesn't really matter
        return@setErrorHandler
      }

      if (error is NullPointerException || error is IllegalArgumentException) {
        // that's likely a bug in the application
        Thread.currentThread().uncaughtExceptionHandler!!.uncaughtException(
          Thread.currentThread(),
          error
        )
        return@setErrorHandler
      }

      if (error is IllegalStateException) {
        // that's a bug in RxJava or in a custom operator
        Thread.currentThread().uncaughtExceptionHandler!!.uncaughtException(
          Thread.currentThread(),
          error
        )
        return@setErrorHandler
      }

      Logger.e(TAG, "RxJava undeliverable exception", error)
      onUnhandledException(error, exceptionToString(UnhandlerExceptionHandlerType.RxJava, error))
    }

    Thread.setDefaultUncaughtExceptionHandler { _, e ->
      // if there's any uncaught crash stuff, just dump them to the log and exit immediately
      Logger.e(TAG, "Unhandled exception", e)
      onUnhandledException(e, exceptionToString(UnhandlerExceptionHandlerType.Normal, e))
      exitProcess(999)
    }
  }

  private fun logAppConstantsAndSettings(appConstants: AppConstants) {
    Logger.d(TAG, "maxPostsCountInPostsCache = " + appConstants.maxPostsCountInPostsCache)
    Logger.d(TAG, "maxAmountOfPostsInDatabase = " + appConstants.maxAmountOfPostsInDatabase)
    Logger.d(TAG, "maxAmountOfThreadsInDatabase = " + appConstants.maxAmountOfThreadsInDatabase)
    Logger.d(TAG, "diskCacheCleanupRemovePercent = " + ChanSettings.diskCacheCleanupRemovePercent.get())
    Logger.d(TAG, "databasePostsCleanupRemovePercent = " + ChanSettings.databasePostsCleanupRemovePercent.get())
    Logger.d(TAG, "userAgent = " + appConstants.userAgent)
    Logger.d(TAG, "kurobaExUserAgent = " + appConstants.kurobaExUserAgent)
  }

  private fun exceptionToString(type: UnhandlerExceptionHandlerType, e: Throwable): String {
    try {
      StringWriter().use { sw ->
        PrintWriter(sw).use { pw ->
          e.printStackTrace(pw)
          val stackTrace = sw.toString()

          return when (type) {
            UnhandlerExceptionHandlerType.Normal -> {
              "Called from unhandled exception handler.\n$stackTrace"
            }
            UnhandlerExceptionHandlerType.RxJava -> {
              "Called from RxJava onError handler.\n$stackTrace"
            }
            UnhandlerExceptionHandlerType.Coroutines -> {
              "Called from Coroutines exception handler.\n$stackTrace"
            }
          }
        }
      }
    } catch (ex: IOException) {
      throw RuntimeException("Error while trying to convert exception to string!", ex)
    }
  }

  private fun onUnhandledException(exception: Throwable, errorText: String) {
    if (!isDevBuild()) {
      if ("Debug crash" == exception.message) {
        return
      }

      if (isEmulator) {
        return
      }
    }

    if (ChanSettings.collectCrashLogs.get()) {
      reportManager.storeCrashLog(exception.message, errorText)
    }
  }

  private fun activityEnteredForeground() {
    val lastForeground = applicationInForeground
    activityForegroundCounter++

    if (applicationInForeground != lastForeground) {
      Logger.d(TAG, "^^^ App went foreground ^^^")

      applicationVisibilityManager.onEnteredForeground()
    }
  }

  private fun activityEnteredBackground() {
    val lastForeground = applicationInForeground
    activityForegroundCounter--

    if (activityForegroundCounter < 0) {
      activityForegroundCounter = 0
    }

    if (applicationInForeground != lastForeground) {
      Logger.d(TAG, "vvv App went background vvv")

      applicationVisibilityManager.onEnteredBackground()
    }
  }

  private fun createPersistableChanStateInfo(): PersistableChanStateInfo {
    return PersistableChanStateInfo(
      versionCode = BuildConfig.VERSION_CODE,
      commitHash = BuildConfig.COMMIT_HASH
    )
  }

  private fun createChanSettingsInfo(): ChanSettingsInfo {
    return ChanSettingsInfo(
      applicationId = BuildConfig.APPLICATION_ID,
      isTablet = isTablet(),
      defaultFilterOrderName = PostsFilter.Order.BUMP.orderName,
      isDevBuild = isDevBuild(),
      isBetaBuild = AppModuleAndroidUtils.isBetaBuild(),
      bookmarkGridViewInfo = BookmarkGridViewInfo(
        getDimen(R.dimen.thread_grid_bookmark_view_default_width),
        getDimen(R.dimen.thread_grid_bookmark_view_min_width),
        getDimen(R.dimen.thread_grid_bookmark_view_max_width)
      )
    )
  }

  /**
   * This is the main instance of FileManager that is used by the most of the app.
   * */
  private fun provideApplicationFileManager(): FileManager {
    val directoryManager = DirectoryManager(this)

    // Add new base directories here
    var resolutionStrategy = BadPathSymbolResolutionStrategy.ReplaceBadSymbols

    if (AppModuleAndroidUtils.getFlavorType() != AndroidUtils.FlavorType.Stable) {
      resolutionStrategy = BadPathSymbolResolutionStrategy.ThrowAnException
    }

    return FileManager(
      appContext = this,
      badPathSymbolResolutionStrategy = resolutionStrategy,
      directoryManager = directoryManager
    )
  }

  /**
   * This is a separate copy of FileManager that exist for the sole purpose of only being used by
   * ImageSaver. That's because all public methods of FileManager are globally locked and the SAF
   * version of the FileManager is slow as fuck so when you download albums the rest of the app will
   * HANG because of the FileManager methods will be locked. To avoid this situation we use a
   * second, separate, instance of FileManager that will only be used in ImageSaver so the other file
   * manager that is used by the app is not getting locked while the user downloads something.
   * */
  private fun provideImageSaverFileManagerWrapper(): ImageSaverFileManagerWrapper {
    val directoryManager = DirectoryManager(this)

    // Add new base directories here
    var resolutionStrategy = BadPathSymbolResolutionStrategy.ReplaceBadSymbols

    if (AppModuleAndroidUtils.getFlavorType() != AndroidUtils.FlavorType.Stable) {
      resolutionStrategy = BadPathSymbolResolutionStrategy.ThrowAnException
    }

    val fileManager = FileManager(
      appContext = this,
      badPathSymbolResolutionStrategy = resolutionStrategy,
      directoryManager = directoryManager
    )

    return ImageSaverFileManagerWrapper(fileManager)
  }

  private enum class UnhandlerExceptionHandlerType {
    Normal,
    RxJava,
    Coroutines
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
  override fun onActivityStarted(activity: Activity) {
    activityEnteredForeground()
  }

  override fun onActivityResumed(activity: Activity) {}
  override fun onActivityPaused(activity: Activity) {}
  override fun onActivityStopped(activity: Activity) {
    activityEnteredBackground()
  }

  override fun onActivityDestroyed(activity: Activity) {}
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

  class OkHttpProtocols(val protocols: List<Protocol>)

  companion object {
    private const val TAG = "Chan"
    private const val ENABLE_STRICT_MODE = false

    private lateinit var applicationComponent: ApplicationComponent

    @JvmStatic
    fun getComponent(): ApplicationComponent {
      return applicationComponent
    }
  }
}