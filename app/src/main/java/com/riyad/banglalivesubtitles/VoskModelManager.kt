package com.riyad.banglalivesubtitles

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the small offline Japanese speech model used by Vosk
 * for the phone-audio mode. The model is free (Apache 2.0) from Alpha Cephei.
 */
object VoskModelManager {

    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
    const val MODEL_DIR_NAME = "vosk-model-small-ja-0.22"

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.exists() && File(dir, "am/final.mdl").exists()
    }

    fun downloadModel(
        context: Context,
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val zipFile = File(context.filesDir, "vosk-model.zip")
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    onError("ডাউনলোড ব্যর্থ (HTTP ${connection.responseCode})")
                    return@Thread
                }
                val total = connection.contentLength
                connection.inputStream.use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt())
                            }
                        }
                    }
                }
                unzip(zipFile, context.filesDir)
                zipFile.delete()
                if (isModelReady(context)) {
                    onDone()
                } else {
                    onError("আনজিপ করার পর মডেল পাওয়া যায়নি")
                }
            } catch (e: Exception) {
                onError("ডাউনলোড ব্যর্থ: ${e.message}")
            }
        }.start()
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                // Guard against zip-slip paths.
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
