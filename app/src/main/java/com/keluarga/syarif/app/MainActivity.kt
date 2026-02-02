package com.keluarga.syarif.app

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var myWebView: WebView
    private var isWebViewReady = false

    companion object {
        // PERBAIKAN: String URL tidak boleh diputus dengan tanda + di tengah https
        const val WEB_URL = "https://achsyarif.rf.gd/Menu-apk/"
        const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/achsyarif/keluarga.syarif/main/update_info.json"
        const val STORAGE_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        myWebView = findViewById(R.id.webView)

        checkPermissions() // Cek izin penyimpanan saat pertama kali buka
        setupSplashScreenListener()
        setupWebView()
        setupBackPressHandler()
        checkForUpdates()

        if (savedInstanceState == null) {
            myWebView.loadUrl(WEB_URL)
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
            }
        }
    }

    private fun setupSplashScreenListener() {
        val content: View = findViewById(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    return if (isWebViewReady) {
                        content.viewTreeObserver.removeOnPreDrawListener(this)
                        true
                    } else {
                        false
                    }
                }
            }
        )
    }

    private fun setupWebView() {
        myWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "") // Menghapus tanda webview agar dikenali sebagai browser mobile biasa
        }

        myWebView.webViewClient = MyCustomWebViewClient()
        myWebView.webChromeClient = MyCustomWebChromeClient()
        myWebView.addJavascriptInterface(BlobWebInterface(), "AndroidBlobDownloader")

        myWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            try {
                if (url.startsWith("blob:")) {
                    val js = """
                        javascript: 
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.setRequestHeader('Content-type','$mimetype');
                        xhr.responseType = 'blob';
                        xhr.onload = function(e) {
                            if (this.status == 200) {
                                var blobFile = this.response;
                                var reader = new FileReader();
                                reader.readAsDataURL(blobFile);
                                reader.onloadend = function() {
                                    base64data = reader.result;
                                    AndroidBlobDownloader.getBase64FromBlob(base64data, '$mimetype');
                                }
                            }
                        };
                        xhr.send();
                    """.trimIndent()
                    myWebView.evaluateJavascript(js, null)
                    Toast.makeText(applicationContext, "Memproses struk...", Toast.LENGTH_SHORT).show()

                } else if (url.startsWith("data:")) {
                    handleBase64Download(url, mimetype)
                } else {
                    val request = DownloadManager.Request(Uri.parse(url))
                    request.setMimeType(mimetype)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    request.addRequestHeader("cookie", cookies)
                    request.addRequestHeader("User-Agent", userAgent)
                    request.setDescription("Mengunduh file...")
                    request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))

                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(applicationContext, "Sedang mengunduh...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(applicationContext, "Gagal mengunduh: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (myWebView.canGoBack()) {
                    // Jika bisa mundur ke halaman sebelumnya (history), mundur dulu
                    myWebView.goBack()
                } else {
                    // Jika tidak ada history, baru keluar aplikasi
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun handleBase64Download(url: String, mimetype: String) {
        val cleanUrl = if (url.contains(",")) url.split(",")[1] else url
        val decodedBytes = Base64.decode(cleanUrl, Base64.DEFAULT)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = if (mimetype.contains("png")) "png" else if (mimetype.contains("pdf")) "pdf" else "jpg"
        val fileName = "Struk_$timeStamp.$extension"

        var os: OutputStream? = null
        var savedFileUri: Uri? = null

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimetype)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = contentResolver
            savedFileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (savedFileUri != null) {
                os = resolver.openOutputStream(savedFileUri)
                os?.write(decodedBytes)
                os?.close()

                Toast.makeText(this, "Tersimpan: $fileName", Toast.LENGTH_LONG).show()
                shareImage(savedFileUri, mimetype)
            } else {
                Toast.makeText(this, "Gagal membuat file.", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            os?.close()
        }
    }

    private fun shareImage(uri: Uri, mimeType: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan via..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class BlobWebInterface {
        @JavascriptInterface
        fun getBase64FromBlob(base64Data: String, mimeType: String) {
            runOnUiThread {
                handleBase64Download(base64Data, mimeType)
            }
        }
    }

    private inner class MyCustomWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            isWebViewReady = true
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val urlString = request?.url?.toString() ?: return false
            val uri = request?.url

            if (urlString.contains("drive.google.com") || urlString.contains("maps.google.com")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    Toast.makeText(applicationContext, "Browser eksternal diperlukan.", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            if (uri != null && uri.scheme != "http" && uri.scheme != "https") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    val msg = when (uri.scheme) {
                        "whatsapp" -> "WhatsApp tidak terinstall."
                        "tel" -> "Tidak bisa menelpon."
                        "mailto" -> "Tidak ada aplikasi email."
                        else -> "Aplikasi tidak ditemukan."
                    }
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                }
                return true
            }
            return false
        }
    }

    private inner class MyCustomWebChromeClient : WebChromeClient() {
        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Konfirmasi")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result?.confirm() }
                .setNegativeButton("Batal") { _, _ -> result?.cancel() }
                .setOnCancelListener { result?.cancel() }
                .show()
            return true
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Info")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.confirm() }
                .show()
            return true
        }
    }

    private fun checkForUpdates() {
        val queue = Volley.newRequestQueue(this)
        val stringRequest = StringRequest(Request.Method.GET, UPDATE_JSON_URL,
            { response ->
                try {
                    val jsonObject = JSONObject(response)
                    val latestVersionCode = jsonObject.getInt("latestVersionCode")
                    val downloadUrl = jsonObject.getString("downloadUrl")
                    val releaseNotes = jsonObject.getString("releaseNotes")

                    @Suppress("DEPRECATION")
                    val currentVersionCode = packageManager.getPackageInfo(packageName, 0).versionCode

                    if (latestVersionCode > currentVersionCode) {
                        showUpdateDialog(downloadUrl, releaseNotes)
                    }
                } catch (e: Exception) {
                    Log.e("UpdateCheck", "Error parsing: ${e.message}")
                }
            },
            { error -> Log.e("UpdateCheck", "Volley Error: ${error.message}") }
        )
        queue.add(stringRequest)
    }

    private fun showUpdateDialog(downloadUrl: String, releaseNotes: String) {
        AlertDialog.Builder(this)
            .setTitle("Update Tersedia")
            .setMessage("Versi baru: $releaseNotes")
            .setPositiveButton("Update") { _, _ ->
                myWebView.clearCache(true)
                WebStorage.getInstance().deleteAllData()
                cacheDir.deleteRecursively()
                startActivity(Intent(Intent.ACTION_VIEW, downloadUrl.toUri()))
            }
            .setNegativeButton("Nanti", null)
            .setCancelable(false)
            .show()
    }
}