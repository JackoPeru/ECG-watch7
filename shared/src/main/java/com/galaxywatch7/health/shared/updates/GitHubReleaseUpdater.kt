package com.galaxywatch7.health.shared.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val localVersion: String,
    val latestVersion: String?,
    val message: String,
    val releaseUrl: String,
    val assetUrl: String?,
    val assetName: String?,
    val releaseNotes: String
)

data class DownloadProgress(
    val progress: Float,
    val message: String,
    val sizeLabel: String
)

object GitHubReleaseUpdater {
    const val RELEASES_PAGE = "https://github.com/JackoPeru/ECG-watch7/releases"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/JackoPeru/ECG-watch7/releases/latest"
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L

    fun check(localVersion: String, assetFlavor: String): UpdateCheckResult {
        return try {
            val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "GalaxyWatch7Health")
            }

            connection.use {
                if (it.responseCode !in 200..299) {
                    return UpdateCheckResult(
                        hasUpdate = false,
                        localVersion = localVersion,
                        latestVersion = null,
                        message = "Nessuna release GitHub trovata. Crea tag vX.Y.Z con asset APK.",
                        releaseUrl = RELEASES_PAGE,
                        assetUrl = null,
                        assetName = null,
                        releaseNotes = ""
                    )
                }

                val json = JSONObject(it.inputStream.bufferedReader().use { reader -> reader.readText() })
                val latest = normalizeVersion(json.optString("tag_name"))
                val releaseUrl = json.optString("html_url", RELEASES_PAGE)
                val releaseNotes = json.optString("body").trim().take(4_000)
                val asset = findApkAsset(json.optJSONArray("assets") ?: JSONArray(), assetFlavor)
                val hasUpdate = compareVersions(latest, localVersion) > 0
                val message = when {
                    hasUpdate && asset == null -> "Update $localVersion -> $latest trovato, ma manca asset APK '$assetFlavor'."
                    hasUpdate -> "Update disponibile: $localVersion -> $latest."
                    else -> "App aggiornata. Locale $localVersion, GitHub $latest."
                }

                UpdateCheckResult(
                    hasUpdate = hasUpdate,
                    localVersion = localVersion,
                    latestVersion = latest,
                    message = message,
                    releaseUrl = releaseUrl,
                    assetUrl = asset?.second,
                    assetName = asset?.first,
                    releaseNotes = releaseNotes
                )
            }
        } catch (ex: Exception) {
            UpdateCheckResult(
                hasUpdate = false,
                localVersion = localVersion,
                latestVersion = null,
                message = "Controllo update fallito: ${ex.message ?: ex.javaClass.simpleName}",
                releaseUrl = RELEASES_PAGE,
                assetUrl = null,
                assetName = null,
                releaseNotes = ""
            )
        }
    }

    fun findDownloadedApk(context: Context, version: String, assetFlavor: String): File? {
        val file = targetFile(context, version, assetFlavor)
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    fun downloadApk(
        context: Context,
        assetUrl: String,
        version: String,
        assetFlavor: String,
        onProgress: (DownloadProgress) -> Unit
    ): File? {
        if (!isAllowedDownloadUrl(assetUrl)) return null
        val targetFile = targetFile(context, version, assetFlavor)
        targetFile.parentFile?.mkdirs()

        return try {
            val connection = (URL(assetUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "GalaxyWatch7Health")
            }

            connection.use {
                if (it.responseCode !in 200..299) return null
                val totalBytes = it.contentLengthLong
                if (totalBytes > MAX_APK_BYTES) return null
                var downloadedBytes = 0L
                var lastPercent = -1
                if (targetFile.exists()) targetFile.delete()

                it.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (downloadedBytes > MAX_APK_BYTES) {
                                targetFile.delete()
                                return null
                            }
                            val progress = if (totalBytes > 0) {
                                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            val percent = (progress * 100).toInt()
                            if (percent != lastPercent || totalBytes <= 0) {
                                lastPercent = percent
                                onProgress(
                                    DownloadProgress(
                                        progress = progress,
                                        message = "Download APK... $percent%",
                                        sizeLabel = sizeLabel(downloadedBytes, totalBytes)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            onProgress(DownloadProgress(1f, "Download completato. APK pronto.", targetFile.length().toReadableFileSize()))
            targetFile
        } catch (_: Exception) {
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    fun installDownloadedApk(context: Context, apkFile: File): String {
        if (!apkFile.exists()) return "APK non trovato. Riscarica update."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            openIntent(
                context,
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Consenti installazione APK sconosciuti, poi premi di nuovo Installa."
        }

        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return if (openIntent(context, intent)) {
            "Installer Android aperto. Conferma update."
        } else {
            "Impossibile aprire installer Android."
        }
    }

    private fun findApkAsset(assets: JSONArray, assetFlavor: String): Pair<String, String>? {
        val normalizedFlavor = assetFlavor.lowercase()
        val fallback = mutableListOf<Pair<String, String>>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            val candidate = name to url
            if (name.lowercase().contains(normalizedFlavor)) return candidate
            fallback.add(candidate)
        }
        return fallback.firstOrNull()
    }

    private fun compareVersions(latest: String, local: String): Int {
        val latestParts = parseVersionParts(latest)
        val localParts = parseVersionParts(local)
        for (i in 0 until max(latestParts.size, localParts.size)) {
            val left = latestParts.getOrElse(i) { 0 }
            val right = localParts.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    private fun parseVersionParts(value: String): List<Int> =
        normalizeVersion(value)
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    private fun normalizeVersion(value: String): String = value.trim().trimStart('v', 'V')

    private fun targetFile(context: Context, version: String, assetFlavor: String): File {
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates")
        val safeVersion = normalizeVersion(version).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeFlavor = assetFlavor.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "Watch7Health-$safeFlavor-$safeVersion.apk")
    }

    private fun isAllowedDownloadUrl(raw: String): Boolean {
        val uri = Uri.parse(raw)
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (host == "github.com" || host.endsWith(".github.com") || host.endsWith("githubusercontent.com"))
    }

    private fun sizeLabel(downloadedBytes: Long, totalBytes: Long): String =
        if (totalBytes > 0) "${downloadedBytes.toReadableFileSize()} / ${totalBytes.toReadableFileSize()}" else downloadedBytes.toReadableFileSize()

    private fun Long.toReadableFileSize(): String {
        if (this <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var size = toDouble()
        var index = 0
        while (size >= 1024.0 && index < units.lastIndex) {
            size /= 1024.0
            index++
        }
        return if (index == 0) "${size.toLong()} ${units[index]}" else String.format(java.util.Locale.US, "%.1f %s", size, units[index])
    }

    private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private fun openIntent(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
