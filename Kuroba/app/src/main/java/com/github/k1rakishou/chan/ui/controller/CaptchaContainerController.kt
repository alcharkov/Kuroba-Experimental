package com.github.k1rakishou.chan.ui.controller

import android.content.Context
import android.content.res.Resources
import android.util.AndroidRuntimeException
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.core.manager.SiteManager
import com.github.k1rakishou.chan.core.site.SiteAuthentication
import com.github.k1rakishou.chan.ui.captcha.AuthenticationLayoutCallback
import com.github.k1rakishou.chan.ui.captcha.AuthenticationLayoutInterface
import com.github.k1rakishou.chan.ui.captcha.CaptchaLayout
import com.github.k1rakishou.chan.ui.captcha.GenericWebViewAuthenticationLayout
import com.github.k1rakishou.chan.ui.captcha.v1.CaptchaNojsLayoutV1
import com.github.k1rakishou.chan.ui.captcha.v2.CaptchaNoJsLayoutV2
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.dp
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import javax.inject.Inject

class CaptchaContainerController(
  context: Context,
  private val chanDescriptor: ChanDescriptor,
  private val authenticationCallback: (AuthenticationResult) -> Unit
) : BaseFloatingController(context), AuthenticationLayoutCallback {
  private lateinit var authenticationLayout: AuthenticationLayoutInterface
  private lateinit var captchaContainer: FrameLayout

  @Inject
  lateinit var siteManager: SiteManager

  override fun getLayoutId(): Int = R.layout.layout_reply_captcha

  override fun injectDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override fun onCreate() {
    super.onCreate()

    captchaContainer = view.findViewById(R.id.captcha_container)
    view.findViewById<FrameLayout>(R.id.outside_area)
      .setOnClickListener { pop() }

    try {
      initAuthenticationInternal(useV2NoJsCaptcha = true)
    } catch (error: Throwable) {
      Logger.e(TAG, "initAuthenticationInternal error", error)
      showToast(getReason(error))

      pop()
    }
  }

  private fun getReason(error: Throwable): String {
    if (error is AndroidRuntimeException && error.message != null) {
      if (error.message?.contains("MissingWebViewPackageException") == true) {
        return AppModuleAndroidUtils.getString(R.string.fail_reason_webview_is_not_installed)
      }

      // Fallthrough
    } else if (error is Resources.NotFoundException) {
      return AppModuleAndroidUtils.getString(
        R.string.fail_reason_some_part_of_webview_not_initialized,
        error.message
      )
    }

    if (error.message != null) {
      return String.format("%s: %s", error.javaClass.simpleName, error.message)
    }

    return error.javaClass.simpleName
  }

  private fun initAuthenticationInternal(useV2NoJsCaptcha: Boolean) {
    val site = siteManager.bySiteDescriptor(chanDescriptor.siteDescriptor())
    if (site == null) {
      showToast("Failed to find site by site descriptor ${chanDescriptor.siteDescriptor()}")
      pop()
      return
    }

    captchaContainer.removeAllViews()

    val authenticationLayout = createAuthenticationLayout(
      authentication = site.actions().postAuthenticate(),
      useV2NoJsCaptcha = useV2NoJsCaptcha
    )

    captchaContainer.addView(authenticationLayout as View, 0)
    authenticationLayout.initialize(site, this)
    authenticationLayout.reset()
  }

  override fun onDestroy() {
    super.onDestroy()

    if (::authenticationLayout.isInitialized) {
      authenticationLayout.onDestroy()
    }
  }

  override fun onAuthenticationComplete() {
    authenticationCallback(AuthenticationResult.Success)
    pop()
  }

  override fun onAuthenticationFailed(error: Throwable) {
    authenticationCallback(AuthenticationResult.Failure(error))
    pop()
  }

  override fun onFallbackToV1CaptchaView() {
    initAuthenticationInternal(useV2NoJsCaptcha = false)
  }

  private fun createAuthenticationLayout(
    authentication: SiteAuthentication,
    useV2NoJsCaptcha: Boolean
  ): AuthenticationLayoutInterface {
    when (authentication.type) {
      SiteAuthentication.Type.NONE -> {
        throw IllegalArgumentException("${authentication.type} is not supposed to be used here")
      }
      SiteAuthentication.Type.CAPTCHA2_INVISIBLE,
      SiteAuthentication.Type.CAPTCHA2 -> {
        val view = CaptchaLayout(context)
        val params = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          WEBVIEW_BASE_CAPTCHA_VIEW_HEIGHT
        )

        view.layoutParams = params
        return view
      }
      SiteAuthentication.Type.CAPTCHA2_NOJS -> {
        return if (useV2NoJsCaptcha) {
          // new captcha window without webview
          CaptchaNoJsLayoutV2(context)
        } else {
          // default webview-based captcha view
          CaptchaNojsLayoutV1(context)
        }
      }
      SiteAuthentication.Type.GENERIC_WEBVIEW -> {
        val view = GenericWebViewAuthenticationLayout(context)
        val params = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          WEBVIEW_BASE_CAPTCHA_VIEW_HEIGHT
        )

        view.layoutParams = params
        return view
      }
    }
  }

  sealed class AuthenticationResult {
    object Success : AuthenticationResult()
    data class Failure(val throwable: Throwable) : AuthenticationResult()
  }

  companion object {
    private const val TAG = "CaptchaContainerController"
    private val WEBVIEW_BASE_CAPTCHA_VIEW_HEIGHT = dp(600f)
  }
}