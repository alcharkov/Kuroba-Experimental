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
package com.github.k1rakishou.chan.ui.captcha.v1;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import com.github.k1rakishou.chan.core.base.okhttp.ProxiedOkHttpClient;
import com.github.k1rakishou.chan.core.site.Site;
import com.github.k1rakishou.chan.core.site.SiteAuthentication;
import com.github.k1rakishou.chan.ui.captcha.AuthenticationLayoutCallback;
import com.github.k1rakishou.chan.ui.captcha.AuthenticationLayoutInterface;
import com.github.k1rakishou.chan.ui.captcha.CaptchaHolder;
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils;
import com.github.k1rakishou.chan.utils.BackgroundUtils;
import com.github.k1rakishou.core_logger.Logger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static com.github.k1rakishou.chan.utils.AppModuleAndroidUtils.openLink;

/**
 * It directly loads the captcha2 fallback url into a webview, and on each requests it executes
 * some javascript that will tell the callback if the token is there.
 */
public class CaptchaNojsLayoutV1
        extends WebView
        implements AuthenticationLayoutInterface {
    private static final String TAG = "CaptchaNojsLayout";
    private static final long RECAPTCHA_TOKEN_LIVE_TIME = TimeUnit.MINUTES.toMillis(2);

    @Inject
    CaptchaHolder captchaHolder;
    @Inject
    ProxiedOkHttpClient proxiedOkHttpClient;

    private AuthenticationLayoutCallback callback;
    private String baseUrl;
    private String siteKey;

    private String webviewUserAgent;

    public CaptchaNojsLayoutV1(Context context) {
        super(context);
        init();
    }

    public CaptchaNojsLayoutV1(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptchaNojsLayoutV1(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        AppModuleAndroidUtils.extractActivityComponent(getContext())
                .inject(this);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    public void initialize(Site site, AuthenticationLayoutCallback callback) {
        this.callback = callback;
        SiteAuthentication authentication = site.actions().postAuthenticate();

        this.siteKey = authentication.siteKey;
        this.baseUrl = authentication.baseUrl;

        requestDisallowInterceptTouchEvent(true);

        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        webviewUserAgent = settings.getUserAgentString();

        setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(@NonNull ConsoleMessage consoleMessage) {
                Logger.i(TAG, consoleMessage.lineNumber() + ":" +
                        consoleMessage.message() + " " + consoleMessage.sourceId());
                return true;
            }
        });

        setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Fails if there is no token yet, which is ok.
                final String setResponseJavascript = "CaptchaCallback.onCaptchaEntered("
                        + "document.querySelector('.fbc-verification-token textarea').value);";
                view.loadUrl("javascript:" + setResponseJavascript);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                String host = Uri.parse(url).getHost();
                if (host == null) {
                    return false;
                }

                if (host.equals(Uri.parse(CaptchaNojsLayoutV1.this.baseUrl).getHost())) {
                    return false;
                } else {
                    openLink(url);
                    return true;
                }
            }
        });
        setBackgroundColor(0x00000000);
        addJavascriptInterface(new CaptchaInterface(this), "CaptchaCallback");
    }

    @Override
    public void onDestroy() {
    }

    public void reset() {
        hardReset();
    }

    @Override
    public void hardReset() {
        loadRecaptchaAndSetWebViewData();
    }

    private void loadRecaptchaAndSetWebViewData() {
        final String recaptchaUrl = "https://www.google.com/recaptcha/api/fallback?k=" + siteKey;

        Request request = new Request.Builder().url(recaptchaUrl)
                .header("User-Agent", webviewUserAgent)
                .header("Referer", baseUrl)
                .build();

        proxiedOkHttpClient.okHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
            }

            @Override
            public void onResponse(Call call, Response response)
                    throws IOException {
                ResponseBody body = response.body();
                if (body == null) throw new IOException();
                String responseHtml = body.string();

                post(() -> loadDataWithBaseURL(recaptchaUrl, responseHtml, "text/html", "UTF-8", null));
            }
        });
    }

    private void onCaptchaEntered(String response) {
        if (TextUtils.isEmpty(response)) {
            reset();
        } else {
            captchaHolder.addNewToken(response, RECAPTCHA_TOKEN_LIVE_TIME);
            callback.onAuthenticationComplete();
        }
    }

    public static class CaptchaInterface {
        private final CaptchaNojsLayoutV1 layout;

        public CaptchaInterface(CaptchaNojsLayoutV1 layout) {
            this.layout = layout;
        }

        @JavascriptInterface
        public void onCaptchaEntered(final String response) {
            BackgroundUtils.runOnMainThread(() -> layout.onCaptchaEntered(response));
        }
    }
}
