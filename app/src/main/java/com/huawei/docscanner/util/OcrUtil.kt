package com.huawei.docscanner.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 离线 OCR 工具（Tesseract4Android）
 * 首次使用会从网络下载中文+英文训练数据，之后离线可用
 */
object OcrUtil {

    private const val TAG = "OcrUtil"

    // 中文+英文训练数据下载地址（tessdata_fast，体积小、速度快）
    private val TRAINED_DATA_SOURCES = listOf(
        "chi_sim" to "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/chi_sim.traineddata",
        "eng" to "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata"
    )

    fun recognizeText(context: Context, bitmap: Bitmap, callback: (String?, Boolean) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val tessDataDir = ensureTessData(context)
                if (tessDataDir == null) {
                    callback(null, false)
                    executor.shutdown()
                    return@execute
                }

                val baseApi = com.googlecode.tesseract.android.TessBaseAPI()
                baseApi.init(tessDataDir.absolutePath, "chi_sim+eng")
                baseApi.setPageSegMode(com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_AUTO)


                baseApi.setImage(bitmap)
                val text = baseApi.getUTF8Text().trim()
                baseApi.recycle()

                callback(text, text.isNotEmpty())
            } catch (e: Exception) {
                Log.e(TAG, "OCR 失败", e)
                callback(null, false)
            } finally {
                executor.shutdown()
            }
        }
    }

    /**
     * 确保训练数据存在，返回 tessdata 父目录；失败返回 null
     */
    private fun ensureTessData(context: Context): File? {
        val dir = File(context.filesDir, "tessdata").apply { mkdirs() }

        for ((lang, urlStr) in TRAINED_DATA_SOURCES) {
            val trainedData = File(dir, "$lang.traineddata")
            if (!trainedData.exists() || trainedData.length() < 1000) {
                try {
                    downloadFile(urlStr, trainedData)
                    Log.d(TAG, "$lang.traineddata 下载完成: ${trainedData.length()} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "下载 $lang 训练数据失败", e)
                    // eng 失败但仍可用 chi_sim；若 chi_sim 也失败则 OCR 不可用
                    if (lang == "chi_sim") {
                        return null
                    }
                }
            }
        }
        return context.filesDir
    }

    private fun downloadFile(urlStr: String, target: File) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"

        conn.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
    }
}
