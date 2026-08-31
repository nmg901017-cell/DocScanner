package com.huawei.docscanner.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageUtil {

    /**
     * 把 Bitmap 保存到系统相册（Android 10+ 用 MediaStore，无需存储权限）
     */
    fun saveBitmapToPictures(context: Context, bitmap: Bitmap): String? {
        return try {
            val fileName = "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocScanner")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                uri.toString()
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocScanner")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 把一页或多页导出为 PDF，保存到相册 Downloads/DocScanner
     * @return 显示 uri 或路径；失败返回 null
     */
    fun exportPdf(context: Context, pages: List<Bitmap>): String? {
        return try {
            val fileName = "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.pdf"
            val doc = PdfDocument()

            pages.forEachIndexed { index, bitmap ->
                val a4W = 595
                val a4H = 842
                // 按 A4 比例缩放图片
                val scale = minOf(a4W.toFloat() / bitmap.width, a4H.toFloat() / bitmap.height)
                val w = (bitmap.width * scale).toInt()
                val h = (bitmap.height * scale).toInt()
                val pageInfo = PdfDocument.PageInfo.Builder(w, h, index + 1).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                doc.finishPage(page)
            }

            val output: java.io.OutputStream
            val display: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocScanner")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                output = context.contentResolver.openOutputStream(uri)!!
                display = uri.toString()
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DocScanner")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                output = file.outputStream()
                display = file.absolutePath
            }
            output.use { doc.writeTo(it) }
            doc.close()
            display
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 保存到缓存区并返回 FileProvider Uri（用于分享）
     */
    fun saveToCacheAndGetUri(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, "scan_share_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            FileProvider.getUriForFile(context, "com.huawei.docscanner.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
