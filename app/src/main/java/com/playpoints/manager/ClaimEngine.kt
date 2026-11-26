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
                        alpha = 0.01f
                        layoutParams = FrameLayout.LayoutParams(412, 915)
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    decorView?.addView(wv)
                    webView = wv

                    var rpcStarted = false

                    handler.postDelayed({
                        finish(ClaimResult(false, message = "انتهت المهلة، تأكد من سرعة الإنترنت"))
                    }, 35_000)

                    fun triggerDirectRpcClaim() {
                        val jsDirectClaim = """
                            (function() {
                                window.__claimRpcResult = null;
                                (async function() {
                                    try {
                                        var wiz = window.WIZ_global_data || {};
                                        var atToken = wiz.SNlM0e || '';
                                        var bl = wiz.cfb2h || 'boq_playuiserver_20261118.02_p0';
                                        var fSid = wiz.FdrFJe || '';

                                        var html = document.documentElement.innerHTML || '';
                                        if (!atToken) {
                                            var mAt = html.match(/"SNlM0e":"([^"]+)"/);
                                            if (mAt) atToken = mAt[1];
                                        }
                                        if (!atToken) {
                                            window.__claimRpcResult = JSON.stringify({ status: 'NO_TOKEN' });
                                            return;
                                        }

                                        var rewardIds = [];
                                        var foundMatch = html.match(/WEEKLY_REWARD_PROB_\d+/g);
                                        if (foundMatch && foundMatch.length > 0) {
                                            foundMatch.forEach(function(id) {
                                                if (rewardIds.indexOf(id) === -1) rewardIds.push(id);
                                            });
                                        }
                                        ['WEEKLY_REWARD_PROB_2', 'WEEKLY_REWARD_PROB_3', 'WEEKLY_REWARD_PROB_4', 'WEEKLY_REWARD_PROB_5', 'WEEKLY_REWARD_PROB_6', 'WEEKLY_REWARD_PROB_1'].forEach(function(id) {
                                            if (rewardIds.indexOf(id) === -1) rewardIds.push(id);
                                        });

                                        async function callRpc(rpcId, payloadArray) {
                                            var fReq = JSON.stringify([[[rpcId, JSON.stringify(payloadArray), null, "generic"]]]);
                                            var query = 'rpcids=' + encodeURIComponent(rpcId) +
                                                '&source-path=%2Fstore%2Fpoints%2Fperks' +
                                                (fSid ? '&f.sid=' + encodeURIComponent(fSid) : '') +
                                                '&bl=' + encodeURIComponent(bl) +
                                                '&hl=en&soc-app=121&soc-platform=1&soc-device=1&rt=c';

                                            var body = 'f.req=' + encodeURIComponent(fReq) + '&at=' + encodeURIComponent(atToken);
                                            var resp = await fetch('/_/PlayStoreUi/data/batchexecute?' + query, {
                                                method: 'POST',
                                                headers: {
                                                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
                                                },
                                                body: body,
                                                credentials: 'include'
                                            });
                                            return await resp.text();
                                        }

                                        // فك تشفير مصفوفة wrb.fr واستخراج النص الداخلي النظيف
                                        function extractInnerPayload(rawResponse, targetRpcId) {
                                            var lines = rawResponse.split('\n');
                                            for (var k = 0; k < lines.length; k++) {
                                                var line = lines[k].trim();
                                                if (line.indexOf('"wrb.fr"') !== -1 && line.indexOf(targetRpcId) !== -1) {
                                                    try {
                                                        var arr = JSON.parse(line);
                                                        for (var j = 0; j < arr.length; j++) {
                                                            if (arr[j][0] === 'wrb.fr' && arr[j][1] === targetRpcId && arr[j][2]) {
                                                                return arr[j][2];
                                                            }
                                                        }
                                                    } catch (err) {}
                                                }
                                            }
                                            return '';
                                        }

                                        // استخراج رقم النقاط من النص الداخلي بعد فك التشفير
                                        function parseWonPoints(innerStr) {
                                            if (!innerStr) return 0;
                                            // 1. البحث عن نص مثل: "You won 5 Play Points!" أو "5 Play Points"
                                            var txtMatch = innerStr.match(/(?:won|earned|got|\+)\s*(\d+)\s*(?:Play\s*)?Points?/i)
                                                || innerStr.match(/(\d+)\s*Play\s*Points?/i);
                                            if (txtMatch) return parseInt(txtMatch[1], 10);

                                            // 2. البحث في بنية المصفوفة الرقمية الخاصة بالجائزة
                                            var cleaned = innerStr.replace(/WEEKLY_REWARD_PROB_\d+/g, '');
                                            var numMatches = cleaned.match(/[\[,]"?(\d{1,4})"?[\],]/g);
                                            if (numMatches) {
                                                for (var m = 0; m < numMatches.length; m++) {
                                                    var val = parseInt(numMatches[m].replace(/\D/g, ''), 10);
                                                    if (val >= 1 && val <= 1000) return val;
                                                }
                                            }
                                            return 0;
                                        }

                                        var lastRaw = '';
                                        for (var i = 0; i < rewardIds.length; i++) {
                                            var rId = rewardIds[i];
                                            await callRpc('xnSgJb', [null, [null, null, null, null, [rId]]]);
                                            var resText = await callRpc('nTRzR', [null, null, [null, null, null, null, [rId]]]);
                                            lastRaw = resText;

                                            var innerData = extractInnerPayload(resText, 'nTRzR');
                                            if (innerData && innerData.indexOf(rId) !== -1) {
                                                var pts = parseWonPoints(innerData);
                                                if (pts > 0 || innerData.indexOf('null,null,null,null,null') === -1) {
                                                    window.__claimRpcResult = JSON.stringify({
                                                        status: 'SUCCESS',
                                                        rewardId: rId,
                                                        pts: pts
                                                    });
                                                    return;
                                                }
                                            }
                                        }

                                        var lowerBody = (document.body.innerText || '').toLowerCase();
                                        if (lowerBody.indexOf('available on friday') !== -1 || lowerBody.indexOf('check back on friday') !== -1) {
                                            window.__claimRpcResult = JSON.stringify({ status: 'ALREADY_CLAIMED' });
                                        } else {
                                            window.__claimRpcResult = JSON.stringify({
                                                status: 'FAILED',
                                                raw: lastRaw.replace(/\s+/g, ' ').substring(0, 200)
                                            });
                                        }
                                    } catch (e) {
                                        window.__claimRpcResult = JSON.stringify({ status: 'ERROR', msg: String(e) });
                                    }
                                })();
                            })();
                        """.trimIndent()

                        wv.evaluateJavascript(jsDirectClaim, null)

                        var pollCount = 0
                        val checkResult = object : Runnable {
                            override fun run() {
                                if (done.get()) return
                                pollCount++
                                wv.evaluateJavascript("window.__claimRpcResult") { raw ->
                                    if (done.get()) return@evaluateJavascript
                                    if (raw == null || raw == "null") {
                                        if (pollCount >= 25) {
                                            finish(ClaimResult(false, message = "انتهى وقت انتظار رد خادم Google"))
                                        } else {
                                            handler.postDelayed(this, 600)
                                        }
                                        return@evaluateJavascript
                                    }

                                    val clean = raw.trim().removeSurrounding("\"")
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\")
                                    val obj = runCatching { JSONObject(clean) }.getOrNull()
                                    val status = obj?.optString("status") ?: "FAILED"

                                    when (status) {
                                        "SUCCESS" -> {
                                            val pts = obj?.optInt("pts", 0) ?: 0
                                            val freshCookies = AccountStore.captureCurrentCookiesJson()
                                            if (freshCookies.contains("SID=")) {
                                                account.cookiesJson = freshCookies
                                            }
                                            account.lastClaimedWeek = AccountStore.currentWeekKey(account.timezone)
                                            account.lastPointsText = if (pts > 0) "+$pts نقاط" else "تم الاستلام"
                                            AccountStore.addOrUpdate(context, account)

                                            val msg = if (pts > 0) {
                                                "حصلت على +$pts نقاط ✓"
                                            } else {
                                                "تم استلام الجائزة الأسبوعية بنجاح ✓"
                                            }
                                            finish(ClaimResult(success = true, pointsCount = pts, message = msg))
                                        }

                                        "ALREADY_CLAIMED" -> {
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

                                        "NO_TOKEN" -> {
                                            finish(
                                                ClaimResult(
                                                    success = false,
                                                    sessionExpired = true,
                                                    message = "تعذر قراءة رمز الجلسة، يرجى إعادة تسجيل الدخول"
                                                )
                                            )
                                        }

                                        else -> {
                                            val rawDetail = obj?.optString("raw", obj.optString("msg", "")) ?: ""
                                            finish(
                                                ClaimResult(
                                                    success = false,
                                                    message = "لم يتم استلام الجائزة.\nرد الخادم: $rawDetail"
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        handler.postDelayed(checkResult, 1000)
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

                            if (url.contains("play.google.com/store/points") && !rpcStarted) {
                                rpcStarted = true
                                handler.postDelayed({ triggerDirectRpcClaim() }, 1200)
                            }
                        }
                    }

                    wv.loadUrl(PERKS_URL)
                }
            }
        }
}
