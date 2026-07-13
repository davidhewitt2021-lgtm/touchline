package com.david.touchline.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Checks GitHub Releases for a newer build.
 * Release tags follow "build-N" where N is the CI run number (= versionCode).
 * The repo must be public for the unauthenticated API call to work.
 */
object Updater {

    const val REPO = "davidhewitt2021-lgtm/touchline"

    data class UpdateInfo(val buildNumber: Int, val downloadUrl: String, val releaseUrl: String)

    /** Runs off the main thread; calls back with an update (if newer), null (up to date), or an error string. */
    fun check(currentBuild: Int, callback: (UpdateInfo?, String?) -> Unit) {
        thread {
            try {
                val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code != 200) {
                    callback(null, if (code == 404) "No releases found (is the repo public?)" else "GitHub returned $code")
                    return@thread
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                val buildNum = tag.removePrefix("build-").toIntOrNull() ?: -1
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                val releaseUrl = json.optString("html_url", "https://github.com/$REPO/releases")
                if (buildNum > currentBuild && apkUrl.isNotEmpty()) {
                    callback(UpdateInfo(buildNum, apkUrl, releaseUrl), null)
                } else {
                    callback(null, null) // up to date
                }
            } catch (e: Exception) {
                callback(null, e.message ?: "Network error")
            }
        }
    }

    fun openDownload(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
