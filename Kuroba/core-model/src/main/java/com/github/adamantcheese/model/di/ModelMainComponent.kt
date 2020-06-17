package com.github.adamantcheese.model.di

import android.app.Application
import com.github.adamantcheese.common.AppConstants
import com.github.adamantcheese.model.di.annotation.*
import com.github.adamantcheese.model.repository.*
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.CoroutineScope
import okhttp3.Dns
import javax.inject.Singleton

@Singleton
@Component(
  modules = [
    NetworkModule::class,
    ModelMainModule::class
  ]
)
interface ModelMainComponent {
  fun inject(application: Application)

  fun getMediaServiceLinkExtraContentRepository(): MediaServiceLinkExtraContentRepository
  fun getSeenPostRepository(): SeenPostRepository
  fun getInlinedFileInfoRepository(): InlinedFileInfoRepository
  fun getChanPostRepository(): ChanPostRepository
  fun getThirdPartyArchiveInfoRepository(): ThirdPartyArchiveInfoRepository
  fun getHistoryNavigationRepository(): HistoryNavigationRepository
  fun getBookmarksRepository(): BookmarksRepository

  @Component.Builder
  interface Builder {
    @BindsInstance
    fun application(application: Application): Builder

    @BindsInstance
    fun loggerTagPrefix(@LoggerTagPrefix loggerTagPrefix: String): Builder

    @BindsInstance
    fun verboseLogs(@VerboseLogs verboseLogs: Boolean): Builder

    @BindsInstance
    fun okHttpDns(@OkHttpDns dns: Dns): Builder

    @BindsInstance
    fun okHttpProtocols(@OkHttpProtocols okHttpProtocols: NetworkModule.OkHttpProtocolList): Builder

    @BindsInstance
    fun appConstants(appConstants: AppConstants): Builder

    @BindsInstance
    fun appCoroutineScope(@AppCoroutineScope scope: CoroutineScope): Builder

    fun build(): ModelMainComponent
  }

}