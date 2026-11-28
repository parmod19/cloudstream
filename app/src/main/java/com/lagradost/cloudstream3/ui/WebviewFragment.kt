package com.lagradost.cloudstream3.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.databinding.FragmentWebviewBinding
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppContextUtils.loadRepository
import org.json.JSONArray
import org.json.JSONObject

class WebviewFragment : BaseFragment<FragmentWebviewBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentWebviewBinding::inflate)
) {

    override fun fixLayout(view: View) = Unit

    override fun onBindingCreated(binding: FragmentWebviewBinding) {
        val initialUrl = arguments?.getString(WEBVIEW_URL).orEmpty().ifBlank { DEFAULT_URL }

        fun loadAddress(rawAddress: String) {
            val address = rawAddress.trim()
            if (address.isEmpty()) return

            val normalized = if (address.contains("://")) address else "https://$address"
            val uri = runCatching { Uri.parse(normalized) }.getOrNull()
            if (uri == null || uri.host.isNullOrBlank() ||
                (uri.scheme != "https" && uri.scheme != "http")
            ) {
                Toast.makeText(activity, "Enter a valid http or https address", Toast.LENGTH_SHORT).show()
                return
            }
            binding.webView.loadUrl(uri.toString())
        }

        binding.goButton.setOnClickListener { loadAddress(binding.addressInput.text.toString()) }
        binding.addressInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadAddress(binding.addressInput.text.toString())
                true
            } else false
        }
        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.forwardButton.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.refreshButton.setOnClickListener { binding.webView.reload() }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val requestUrl = request?.url?.toString() ?: return false
                val performedAction = MainActivity.handleAppIntentUrl(activity, requestUrl, true)
                if (performedAction) {
                    findNavController().popBackStack()
                    return true
                }
                val scheme = request.url?.scheme
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank()) binding.addressInput.setText(url)
                findPublicVideos(view)
            }
        }

        binding.webView.apply {
            WebViewResolver.webViewUserAgent = settings.userAgentString
            addJavascriptInterface(RepoApi(activity), "RepoApi")
            settings.javaScriptEnabled = true
            settings.userAgentString = USER_AGENT
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            loadUrl(initialUrl)
        }
    }

    private fun findPublicVideos(webView: WebView?) {
        val script = """(function() {
            const urls = new Set();
            const add = value => {
                if (!value) return;
                try {
                    const url = new URL(value, document.baseURI);
                    if ((url.protocol === 'https:' || url.protocol === 'http:') && /\\.(mp4|webm)(?:$|[?#])/i.test(url.href)) urls.add(url.href);
                } catch (_) {}
            };
            document.querySelectorAll('video').forEach(video => {
                add(video.currentSrc);
                add(video.src);
            });
            document.querySelectorAll('video source, a[href]').forEach(node => add(node.src || node.href));
            return JSON.stringify(Array.from(urls));
        })()"""

        webView?.evaluateJavascript(script) { result ->
            if (!isAdded || result.isNullOrBlank() || result == "null") return@evaluateJavascript
            val jsonText = runCatching {
                JSONObject("{\"result\":$result}").getString("result")
            }.getOrNull() ?: return@evaluateJavascript
            val urls = runCatching { JSONArray(jsonText) }.getOrNull() ?: return@evaluateJavascript
            val safeUrls = (0 until urls.length()).mapNotNull { index ->
                urls.optString(index).takeIf { it.startsWith("https://") || it.startsWith("http://") }
            }.distinct()
            showVideoCards(safeUrls)
        }
    }

    private fun showVideoCards(urls: List<String>) {
        val container = binding.mediaContainer
        container.removeAllViews()
        if (urls.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "No direct public MP4/WebM videos found on this page. DRM and streaming-only sources are not supported."
                setPadding(16, 8, 16, 8)
            })
            return
        }

        urls.forEach { mediaUrl ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 8, 12, 8)
            }
            val label = TextView(requireContext()).apply {
                text = mediaUrl.substringAfterLast('/').ifBlank { "Public video" }
                maxLines = 2
                contentDescription = mediaUrl
            }
            val actions = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(requireContext()).apply {
                text = "Play"
                setOnClickListener { playPublicVideo(mediaUrl) }
            })
            actions.addView(Button(requireContext()).apply {
                text = "Download"
                setOnClickListener { downloadPublicVideo(mediaUrl) }
            })
            card.addView(label)
            card.addView(actions)
            container.addView(card)
        }
    }

    private fun playPublicVideo(url: String) {
        val mimeType = if (url.substringBefore('?').endsWith(".webm", true)) "video/webm" else "video/mp4"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mimeType)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No video player is installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadPublicVideo(url: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(url.substringAfterLast('/').ifBlank { "Public video" })
                .setDescription("Downloading public video")
                .setMimeType(if (url.substringBefore('?').endsWith(".webm", true)) "video/webm" else "video/mp4")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val manager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(activity, "Download started", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "Could not start this download", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val WEBVIEW_URL = "webview_url"
        private const val DEFAULT_URL = "https://example.com"

        fun newInstance(webViewUrl: String) = Bundle().apply {
            putString(WEBVIEW_URL, webViewUrl)
        }
    }

    private class RepoApi(val activity: FragmentActivity?) {
        @JavascriptInterface
        fun installRepo(repoUrl: String) {
            activity?.loadRepository(repoUrl)
        }
    }
}
