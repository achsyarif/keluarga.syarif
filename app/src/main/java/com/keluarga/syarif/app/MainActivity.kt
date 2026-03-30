package com.keluarga.syarif.app

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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

    companion object {
        const val WEB_URL = "https://achsyarif.rf.gd/Menu-apk/"
        const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/achsyarif/keluarga.syarif/main/update_info.json"
        const val PERMISSION_REQUEST_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()

        // Padding otomatis untuk sistem bar (status bar & navigasi)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        myWebView = findViewById(R.id.webView)

        checkAndRequestPermissions()
        setupWebView()
        setupBackPressHandler()
        checkForUpdates()

        if (savedInstanceState == null) {
            myWebView.loadUrl(WEB_URL)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            // Untuk Android 10 kebawah butuh WRITE_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val listToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (listToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, listToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
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
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }

        myWebView.webViewClient = MyCustomWebViewClient()
        myWebView.webChromeClient = MyCustomWebChromeClient()
        myWebView.addJavascriptInterface(BlobWebInterface(), "AndroidBlobDownloader")

        myWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:")) {
                vibratePhone(50)
                val js = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            if (this.status == 200) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    AndroidBlobDownloader.getBase64FromBlob(reader.result, '$mimetype');
                                };
                                reader.readAsDataURL(this.response);
                            }
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                myWebView.evaluateJavascript(js, null)
                Toast.makeText(this, "Memproses file...", Toast.LENGTH_SHORT).show()
            } else if (url.startsWith("data:")) {
                handleBase64Download(url, mimetype)
            } else {
                handleStandardDownload(url, userAgent, contentDisposition, mimetype)
            }
        }
    }

    private fun vibratePhone(duration: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun handleBase64Download(url: String, mimetype: String) {
        try {
            val cleanUrl = if (url.contains(",")) url.split(",")[1] else url
            val decodedBytes = Base64.decode(cleanUrl, Base64.DEFAULT)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype) ?: "jpg"
            val fileName = "Struk_$timeStamp.$extension"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimetype)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(decodedBytes)
                }
                vibratePhone(100)
                Toast.makeText(this, "Tersimpan: $fileName", Toast.LENGTH_LONG).show()
                shareFile(it, mimetype)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleStandardDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype)
                addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("User-Agent", userAgent)
                setDescription("Mengunduh file...")
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
            }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Mulai mengunduh...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download Manager gagal.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(uri: Uri, mimeType: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Bagikan via..."))
        } catch (e: Exception) {
            Log.e("Share", e.message ?: "Error sharing")
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (myWebView.canGoBack()) {
                    myWebView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            val urlString = uri.toString()

            if (urlString.contains("drive.google.com") || urlString.contains("maps.google.com")) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }

            if (uri.scheme != "http" && uri.scheme != "https") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    Toast.makeText(applicationContext, "Aplikasi tidak ditemukan.", Toast.LENGTH_SHORT).show()
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
                .show()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Memberikan izin secara otomatis ke web (Kamera/Mikrofon)
                request?.grant(request.resources)
            }
        }


        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Info")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result?.confirm() }
                .show()
            return true
        }


    }

    private fun checkForUpdates() {
        val queue = Volley.newRequestQueue(this)
        val stringRequest = StringRequest(Request.Method.GET, UPDATE_JSON_URL,
            { response ->
                try {
                    val json = JSONObject(response)
                    val latest = json.getInt("latestVersionCode")
                    val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(packageName, 0).versionCode
                    }

                    if (latest > current) {
                        showUpdateDialog(json.getString("downloadUrl"), json.getString("releaseNotes"))
                    }
                } catch (e: Exception) { Log.e("Update", "Error: ${e.message}") }
            },
            { Log.e("Update", "Volley Error") }
        )
        queue.add(stringRequest)
    }

    private fun showUpdateDialog(url: String, notes: String) {
        AlertDialog.Builder(this)
            .setTitle("Update Tersedia")
            .setMessage("Pembaruan diperlukan:\n$notes")
            .setPositiveButton("Update") { _, _ ->
                myWebView.clearCache(true)
                WebStorage.getInstance().deleteAllData()
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
            .setNegativeButton("Nanti", null)
            .setCancelable(false)
            .show()
    }
}