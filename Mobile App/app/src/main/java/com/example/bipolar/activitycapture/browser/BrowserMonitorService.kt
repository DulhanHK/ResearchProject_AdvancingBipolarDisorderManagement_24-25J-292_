package com.example.bipolar.activitycapture.browser

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.bipolar.utils.NotificationUtils.initialize
import com.example.bipolar.utils.NotificationUtils.updateCombinedNotification
import com.google.firebase.auth.FirebaseAuth
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType // Add this import if not present
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class BrowserMonitorService : AccessibilityService() {
    private var lastEventTime: Long = 0
    private val executor: ExecutorService? = Executors.newSingleThreadExecutor()
    private var lastProcessedUrl: String? = null
    private var lastProcessedTime: Long = 0
    var firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
    private val client = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun saveEmotionToPrefs(emotion: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_TEXT_EMOTION, emotion)
        editor.apply()
        Log.d(TAG, "Saved text_emotion: $emotion")
        updateNotification()
    }

    private fun updateNotification() {
        val prefs = getSharedPreferences("BipolaDisorderPrefs", MODE_PRIVATE)
        val steps = prefs.getInt("steps", -1)
        val textEmotion = prefs.getString("text_emotion", "Unknown")!!
        val audioEmotion = prefs.getString("audio_emotion", "Unknown")!!
        updateCombinedNotification(this, steps, textEmotion, audioEmotion)
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo()
        info.eventTypes =
            AccessibilityEvent.TYPE_VIEW_CLICKED or  //                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.packageNames = arrayOf(
            "com.android.chrome", "org.mozilla.firefox", "com.opera.browser", "com.brave.browser",
            "com.microsoft.emmx", "com.sec.android.app.sbrowser"
        )
        info.flags = AccessibilityServiceInfo.DEFAULT
        info.notificationTimeout = 100
        serviceInfo = info

        lastEventTime = System.currentTimeMillis()
        startWatchdog()
        initialize(this)
        Log.d(
            TAG,
            "Service connected, monitoring all packages with browser filter: " + BROWSER_PACKAGES
        )
    }

    private fun startWatchdog() {
        executor!!.submit {
            while (!executor.isShutdown) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                    if (System.currentTimeMillis() - lastEventTime > WATCHDOG_INTERVAL_MS) {
                        Log.w(
                            TAG,
                            "No events for 5 minutes, restarting service"
                        )
                        val intent =
                            Intent(
                                this,
                                BrowserMonitorService::class.java
                            )
                        stopService(intent)
                        startService(intent)
                    }
                } catch (e: InterruptedException) {
                    Log.e(
                        TAG,
                        "Watchdog interrupted: " + e.message
                    )
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventTime = System.currentTimeMillis()
        if (event == null) {
            Log.d(TAG, "Received null event, skipping")
            return
        }

        val packageName = if (event.packageName != null) event.packageName.toString() else ""
        Log.d(
            TAG,
            "Event received from: " + packageName + " | Type: " + AccessibilityEvent.eventTypeToString(
                event.eventType
            )
        )

        if (!BROWSER_PACKAGES.contains(packageName)) {
            Log.d(
                TAG,
                "Ignored non-browser package: $packageName"
            )
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            Log.d(TAG, "Search result click detected, expecting navigation")
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(
                TAG,
                "Root node is null for package: $packageName"
            )
            return
        }

        try {
            val url = findUrlInAddressBar(rootNode, packageName)
            if (url != null) {
                Log.d(
                    TAG,
                    "Captured URL from address bar for event " + AccessibilityEvent.eventTypeToString(
                        event.eventType
                    ) + ": " + url
                )
                processBrowsingData(url)
            } else {
                Log.d(
                    TAG,
                    "No URL found in address bar for package: $packageName"
                )
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findUrlInAddressBar(node: AccessibilityNodeInfo?, packageName: String): String? {
        if (node == null) return null
        try {
            // Try known Chrome address bar view IDs
            val possibleViewIds = arrayOf(
                "$packageName:id/url_bar",
                "$packageName:id/omnibox",
                "$packageName:id/location_bar"
            )
            for (viewId in possibleViewIds) {
                val urlNodes = node.findAccessibilityNodeInfosByViewId(viewId)
                for (urlNode in urlNodes) {
                    val text = urlNode.text
                    if (text != null && !text.toString().trim { it <= ' ' }.isEmpty()) {
                        val rawText = text.toString().trim { it <= ' ' }
                        // Handle partial URLs (e.g., allrecipes.com/...)
                        val url = if (rawText.startsWith("http")) {
                            rawText
                        } else if (rawText.contains("/")) {
                            // Prepend https:// for partial URLs
                            "https://$rawText"
                        } else {
                            Log.d(
                                TAG,
                                "Node found for " + viewId + " but no valid URL: text=" + rawText + ", class=" + urlNode.className
                            )
                            urlNode.recycle()
                            continue
                        }
                        Log.d(
                            TAG,
                            "URL from address bar ($viewId): $url"
                        )
                        urlNode.recycle()
                        return url
                    }
                    Log.d(
                        TAG,
                        "Node found for " + viewId + " but no text: class=" + urlNode.className
                    )
                    urlNode.recycle()
                }
            }

            // Debug: Log all nodes with text
            val nodeText = node.text
            if (nodeText != null && !nodeText.toString().trim { it <= ' ' }.isEmpty()) {
                val text = nodeText.toString().trim { it <= ' ' }
                Log.d(
                    TAG,
                    "Node text: " + text + " | class=" + node.className + " | viewId=" + node.viewIdResourceName + " | editable=" + node.isEditable
                )
                if (text.startsWith("http")) {
                    Log.d(
                        TAG,
                        "URL from generic node: $text"
                    )
                    return text
                } else if (text.contains("/")) {
                    // Prepend https:// for partial URLs
                    val url = "https://$text"
                    Log.d(
                        TAG,
                        "URL from generic node (constructed): $url"
                    )
                    return url
                }
            }

            // Recurse through children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val url = findUrlInAddressBar(child, packageName)
                    if (url != null) {
                        child.recycle()
                        return url
                    }
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding URL in address bar: " + e.message)
        }
        return null
    }

    private fun processBrowsingData(data: String?) {
        if (data == null || data.trim { it <= ' ' }.isEmpty()) {
            Log.d(TAG, "Empty or null data, skipping")
            return
        }
        if (!data.startsWith("http") || data.contains("google.com/search")) {
            Log.d(
                TAG,
                "Skipping non-URL or search results page: $data"
            )
            return
        }
        if (lastProcessedUrl != null && lastProcessedUrl == data && System.currentTimeMillis() - lastProcessedTime < 5000) {
            Log.d(TAG, "Skipping duplicate URL: $data")
            return
        }
        Log.d(OUTPUT_TAG, "User-navigated URL: $data")
        lastProcessedUrl = data
        lastProcessedTime = System.currentTimeMillis()
        Log.d(
            SCRAPE_TAG,
            "Calling scrapeWebContent for URL: $data"
        )
        scrapeWebContent(data)
    }

    private fun scrapeWebContent(url: String) {
        Log.d(
            SCRAPE_TAG,
            "Starting scrapeWebContent for URL: $url"
        )

        // Create a new ThreadPoolExecutor for scraping
        val scrapeExecutor = ThreadPoolExecutor(
            1, 1, 60, TimeUnit.SECONDS, LinkedBlockingQueue(10)
        ) { r ->
            val t = Thread(r, "ScrapeThread-" + url.hashCode())
            Log.d(
                SCRAPE_TAG,
                "Created new thread for URL: " + url + ", Thread: " + t.name
            )
            t
        }

        // Test executor with a simple task
        Log.d(
            SCRAPE_TAG,
            "Testing executor for URL: $url"
        )
        try {
            scrapeExecutor.submit {
                Log.d(
                    SCRAPE_TAG,
                    "Test task running for URL: " + url + ", Thread: " + Thread.currentThread().name
                )
            }[1, TimeUnit.SECONDS]
            Log.d(
                SCRAPE_TAG,
                "Test task completed for URL: $url"
            )
        } catch (e: Exception) {
            Log.e(SCRAPE_TAG, "Test task failed for URL: " + url + ": " + e.message)
            scrapeExecutor.shutdown()
            return
        }

        Log.d(
            SCRAPE_TAG,
            "Preparing to submit scrape task for URL: $url"
        )
        try {
            scrapeExecutor.submit {
                Log.d(
                    SCRAPE_TAG,
                    "Executor task started for URL: " + url + ", Thread: " + Thread.currentThread().name
                )
                val MAX_RETRIES = 3
                for (attempt in 1..MAX_RETRIES) {
                    try {
                        val doc = Jsoup.connect(url)
                            .userAgent("Mozilla/5.0 (Android)")
                            .timeout(10000)
                            .get()

                        var title = doc.title()
                        if (title == null || title.trim { it <= ' ' }.isEmpty()) {
                            val metaTitle =
                                doc.selectFirst("meta[property=og:title]")
                            title =
                                metaTitle?.attr("content") ?: "No title found"
                        }

                        var paragraph: String
                        // Collect all <p> tags and combine their text
                        val contentBuilder = StringBuilder()
                        val paragraphs = doc.select("p")
                        var hasValidContent = false
                        for (p in paragraphs) {
                            val text = p.text().trim { it <= ' ' }
                            if (!text.isEmpty() && !text.lowercase(Locale.getDefault())
                                    .contains("sign up") && !text.lowercase(Locale.getDefault())
                                    .contains("login")
                            ) {
                                contentBuilder.append(text).append(" ")
                                hasValidContent = true
                            }
                        }

                        if (hasValidContent) {
                            // Extract first 300 characters, avoiding mid-word cutoff
                            val fullContent =
                                contentBuilder.toString().trim { it <= ' ' }
                            if (fullContent.length > 500) {
                                paragraph = fullContent.substring(0, 500)
                                // Find last space to avoid cutting mid-word
                                val lastSpace = paragraph.lastIndexOf(" ")
                                if (lastSpace > 0) {
                                    paragraph = paragraph.substring(0, lastSpace) + "..."
                                }
                            } else {
                                paragraph = fullContent
                            }
                        } else {
                            // Fallback to meta description if no valid content
                            val metaDesc =
                                doc.selectFirst("meta[name=description]")
                            paragraph =
                                metaDesc?.attr("content")
                                    ?: "Paywall detected, no content available"
                        }

                        val result = "URL: $url\nTitle: $title\nParagraph: $paragraph\n"
                        Log.d(SCRAPE_TAG, result)
                        makeEmotionPredictionRequest(title, paragraph)
                        return@submit
                    } catch (t: Throwable) {
                        Log.e(
                            SCRAPE_TAG,
                            "Attempt " + attempt + " failed for URL " + url + ": " + t.message
                        )
                        if (attempt == MAX_RETRIES) {
                            Log.e(
                                SCRAPE_TAG,
                                "Giving up on $url"
                            )
                        }
                        try {
                            Thread.sleep(1000)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            Log.e(
                                SCRAPE_TAG,
                                "Retry interrupted for URL $url"
                            )
                            return@submit
                        }
                    }
                }
                Log.d(
                    SCRAPE_TAG,
                    "Finished scrapeWebContent for URL: $url"
                )
            }
            Log.d(
                SCRAPE_TAG,
                "Scrape task submitted for URL: $url"
            )
        } catch (e: Exception) {
            Log.e(SCRAPE_TAG, "Failed to submit scrape task for URL " + url + ": " + e.message)
        } finally {
            scrapeExecutor.shutdown()
            Log.d(
                SCRAPE_TAG,
                "Scrape executor shutdown for URL: $url"
            )
        }
    }

    private fun makeEmotionPredictionRequest(term: String, contentdata: String) {
//        val url = "http://192.168.1.183:5000/api/v1/predict_text" // Update with your server URL
        val url = "https://bipolar-backend-75820532432.asia-south1.run.app/api/v1/predict_text"
        val userId = firebaseAuth.currentUser!!.uid // Replace with dynamic user ID if needed

        try {
            val jsonBody = JSONObject()
            jsonBody.put("userID", userId)
            jsonBody.put("Term", term)
            jsonBody.put("contentdata", contentdata)

            val requestBody: RequestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Emotion prediction request failed: " + e.message)
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        val responseBody = response.body!!.string()
                        Log.d(
                            TAG,
                            "Emotion prediction response: $responseBody"
                        )
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val emotion = jsonResponse.optString("emotion", "Unknown")
                            saveEmotionToPrefs(emotion)
                            sendEmotionBroadcast(emotion)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing emotion response: " + e.message)
                            sendEmotionBroadcast("None")
                        }
                    } else {
                        Log.e(
                            TAG,
                            "Emotion prediction request unsuccessful: " + response.code + " " + response.message
                        )
                        Log.e(
                            TAG, "Response body: " + response.body!!
                                .string()
                        )
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing emotion prediction request: " + e.message)
        }
    }

    private fun sendEmotionBroadcast(emotion: String) {
        val intent = Intent(BROADCAST_ACTION)
            .putExtra("emotion", emotion)
        sendBroadcast(intent)
        Log.d(
            TAG,
            "Broadcast sent with emotion: $emotion"
        )
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (executor != null && !executor.isShutdown) {
            executor.shutdown()
        }
        Log.d(TAG, "Service destroyed")
    }

    companion object {
        private const val TAG = "BrowserMonitorService"
        private const val OUTPUT_TAG = "BrowserMonitorOutput"
        private const val SCRAPE_TAG = "BrowserMonitorScrape"
        private val BROWSER_PACKAGES: Set<String> = HashSet(
            mutableListOf(
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.opera.browser",
                "com.brave.browser",
                "com.microsoft.emmx",
                "com.sec.android.app.sbrowser"
            )
        )
        private const val WATCHDOG_INTERVAL_MS: Long = 300000 // 5 minutes
        const val BROADCAST_ACTION: String = "com.g292.bipoladisorder.BROWSER_UPDATE"
        private const val PREFS_NAME = "BipolaDisorderPrefs"
        private const val KEY_TEXT_EMOTION = "text_emotion"
    }
}



