package com.playpoints.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class ClaimResult(
    val success: Boolean,
    val alreadyClaimed: Boolean = false,
    val sessionExpired: Boolean = false,
    val pointsCount: Int = 0,
    val message: String
)

object ClaimEngine {
    // إجبار اللغة الإنجليزية لتوحيد أسماء الأزرار ونص النتيجة
    const val PERKS_URL = "https://play.google.com/store/points/perks?hl=en"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-A155F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun claimAccount(context: Context, account: AccountItem): ClaimResult =
        withContext(Dispatchers.Main) {
            if (!account.hasValidSession()) {
                return@withContext ClaimResult(
                    success = false,
                    sessionExpired = true,
                    message = "الجلسة غير محفوظة، يرجى إعادة تسجيل الدخول"
                )
            }

            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                val hostActivity = context as? Activity
                val decorView = hostActivity?.window?.decorView as? ViewGroup

                fun cleanupWebView() {
                    try {
                        webView?.let { wv ->
                            wv.stopLoading()
                            decorView?.removeView(wv)
                            wv.destroy()
                        }
                    } catch (_: Exception) {
                    }
                    webView = null
                }

                fun finish(result: ClaimResult) {
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacksAndMessages(null)
                        cleanupWebView()
                        if (cont.isActive) cont.resume(result)
                    }
                }

                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacksAndMessages(null)
                        cleanupWebView()
                    }
                }

                AccountStore.restoreCookies(account.cookiesJson) {
                    val wv = WebView(hostActivity ?: context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.userAgentString = USER_AGENT
                        // إبقاء المتصفح نشطاً بصرياً حتى لا يُجمّد Chromium حركة فتح الصندوق
                        alpha = 0.01f
                        layoutParams = FrameLayout.LayoutParams(360, 640)
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    decorView?.addView(wv)
                    webView = wv

                    var pollingStarted = false
                    var claimClicked = false
                    var boxTappedCount = 0
                    var attempts = 0

                    // مهلة أمان 35 ثانية
                    handler.postDelayed({
                        finish(ClaimResult(false, message = "انتهت المهلة، تأكد من سرعة الإنترنت"))
                    }, 35_000)

                    val pollRunnable = object : Runnable {
                        override fun run() {
                            if (done.get()) return
                            attempts++

                            val jsStep = """
                                (function() {
                                    function simulateTap(el) {
                                        if (!el) return;
                                        var rect = el.getBoundingClientRect();
                                        var cx = rect.left + rect.width / 2;
                                        var cy = rect.top + rect.height / 2;
                                        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(type) {
                                            el.dispatchEvent(new MouseEvent(type, {
                                                bubbles: true,
                                                cancelable: true,
                                                view: window,
                                                clientX: cx,
                                                clientY: cy
                                            }));
                                        });
                                    }

                                    var fullText = (document.body.innerText || '');
                                    var lowerFull = fullText.toLowerCase();

                                    // 1. فحص ظهور نتيجة الفوز بالنقاط في النافذة المنبثقة أو الصفحة
                                    var dialog = document.querySelector('[role="dialog"]');
                                    var dialogText = dialog ? (dialog.innerText || '') : '';
                                    var wonMatch = (dialogText + '\n' + fullText).match(/(?:you won|you earned|you got)\s*\+?(\d+)\s*point/i)
                                        || dialogText.match(/\+(\d+)\s*point/i);
                                    if (wonMatch) {
                                        return JSON.stringify({ state: 'WON', pts: parseInt(wonMatch[1], 10) });
                                    }

                                    // 2. إذا كانت النافذة المنبثقة مفتوحة، انقر على زر الفتح أو الصندوق بداخلها
                                    if (dialog) {
                                        var dBtns = Array.from(dialog.querySelectorAll('button, [role="button"]'));
                                        var openBtn = dBtns.find(function(b) {
                                            var r = b.getBoundingClientRect();
                                            if (r.width === 0 || r.height === 0) return false;
                                            var t = (b.innerText || '').trim().toLowerCase();
                                            return t === 'open' || t === 'claim' || t.indexOf('tap') !== -1;
                                        });
                                        if (openBtn) {
                                            simulateTap(openBtn);
                                            return JSON.stringify({ state: 'TAPPED_BOX' });
                                        }
                                        var boxTarget = dialog.querySelector('canvas, img, svg') || dialog;
                                        simulateTap(boxTarget);
                                        return JSON.stringify({ state: 'TAPPED_BOX', dialogText: dialogText.trim().substring(0, 140) });
                                    }

                                    // 3. البحث عن زر Claim الرئيسي في الصفحة
                                    var pageBtns = Array.from(document.querySelectorAll('button, [role="button"]'));
                                    var claimBtn = pageBtns.find(function(b) {
                                        var r = b.getBoundingClientRect();
                                        if (r.width === 0 || r.height === 0) return false;
                                        var t = (b.innerText || '').trim().toLowerCase();
                                        return t === 'claim' && !b.disabled && b.getAttribute('aria-disabled') !== 'true';
                                    });
                                    if (claimBtn) {
                                        simulateTap(claimBtn);
                                        return JSON.stringify({ state: 'CLICKED_CLAIM' });
                                    }

                                    // 4. التحقق الصارم من أن الجائزة مستلمة مسبقاً لهذا الأسبوع
                                    if (lowerFull.indexOf('available on friday') !== -1 || lowerFull.indexOf('check back on friday') !== -1) {
                                        return JSON.stringify({ state: 'ALREADY_CLAIMED' });
                                    }

                                    return JSON.stringify({
                                        state: 'WAITING',
                                        snippet: fullText.trim().replace(/\s+/g, ' ').substring(0, 150)
                                    });
                                })();
                            """.trimIndent()

                            wv.evaluateJavascript(jsStep) { raw ->
                                if (done.get()) return@evaluateJavascript
                                val clean = raw.trim().removeSurrounding("\"")
                                    .replace("\\\"", "\"")
                                    .replace("\\\\", "\\")
                                val obj = runCatching { JSONObject(clean) }.getOrNull()
                                val state = obj?.optString("state") ?: "WAITING"

                                when (state) {
                                    "WON" -> {
                                        val pts = obj?.optInt("pts", 0) ?: 0
                                        val freshCookies = AccountStore.captureCurrentCookiesJson()
                                        if (freshCookies.contains("SID=")) {
                                            account.cookiesJson = freshCookies
                                        }
                                        account.lastClaimedWeek = AccountStore.currentWeekKey(account.timezone)
                                        account.lastPointsText = "+$pts نقاط"
                                        AccountStore.addOrUpdate(context, account)
                                        finish(ClaimResult(success = true, pointsCount = pts, message = "حصلت على +$pts نقاط ✓"))
                                    }

                                    "CLICKED_CLAIM" -> {
                                        claimClicked = true
                                        handler.postDelayed(this, 800)
                                    }

                                    "TAPPED_BOX" -> {
                                        boxTappedCount++
                                        val dText = obj?.optString("dialogText", "") ?: ""
                                        if (boxTappedCount > 12) {
                                            finish(
                                                ClaimResult(
                                                    success = false,
                                                    message = "تم فتح الصندوق لكن لم يظهر رصيد النقاط.\nالرد: $dText"
                                                )
                                            )
                                        } else {
                                            handler.postDelayed(this, 800)
                                        }
                                    }

                                    "ALREADY_CLAIMED" -> {
                                        if (claimClicked) {
                                            // اختفى زر Claim وظهرت عبارة الجمعة القادمة = نجح الاستلام
                                            account.lastClaimedWeek = AccountStore.currentWeekKey(account.timezone)
                                            account.lastPointsText = "تم الاستلام"
                                            AccountStore.addOrUpdate(context, account)
                                            finish(ClaimResult(success = true, message = "تم استلام الجائزة الأسبوعية بنجاح ✓"))
                                        } else {
                                            account.lastClaimedWeek = AccountStore.currentWeekKey(account.timezone)
                                            AccountStore.addOrUpdate(context, account)
                                            finish(
                                                ClaimResult(
                                                    success = true,
                                                    alreadyClaimed = true,
                                                    message = "جائزة هذا الأسبوع مستلمة مسبقاً"
                                                )
                                            )
                                        }
                                    }

                                    else -> {
                                        if (attempts >= 22) {
                                            val snippet = obj?.optString("snippet", "") ?: ""
                                            finish(
                                                ClaimResult(
                                                    success = false,
                                                    message = "لم يظهر زر Claim في الصفحة.\nالنص الظاهر: $snippet"
                                                )
                                            )
                                        } else {
                                            handler.postDelayed(this, 800)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url == null || done.get()) return

                            if (url.contains("accounts.google.com/v3/signin") ||
                                url.contains("accounts.google.com/ServiceLogin")
                            ) {
                                finish(
                                    ClaimResult(
                                        success = false,
                                        sessionExpired = true,
                                        message = "انتهت الجلسة، يرجى إعادة تسجيل الدخول"
                                    )
                                )
                                return
                            }

                            if (url.contains("play.google.com/store/points") && !pollingStarted) {
                                pollingStarted = true
                                handler.postDelayed(pollRunnable, 1500)
                            }
                        }
                    }

                    wv.loadUrl(PERKS_URL)
                }
            }
        }
}
