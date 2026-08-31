package com.huawei.docscanner.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * 纯 Java 文档扫描处理器（无原生库依赖，不会原生崩溃）
 * 流程：灰度 + 亮度投影自动裁剪（去掉深色边框）+ 对比度增强
 */
object ScanProcessor {

    private const val TAG = "ScanProcessor"

    /**
     * 处理文档图片：自动裁剪到文档区域 + 增强对比度
     * 返回处理后的 Bitmap（一定非空，不会抛出原生崩溃）
     */
    fun correctDocument(bitmap: Bitmap): Bitmap {
        return try {
            // 1. 缩小到处理工作尺寸（提升像素处理速度）
            val scale = min(1.0, 1600.0 / max(bitmap.width, bitmap.height))
            val work = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else {
                bitmap
            }

            // 2. 转灰度
            val w = work.width
            val h = work.height
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }

            // 3. 亮度投影找文档包围盒（去掉深色背景边框）
            val bbox = findDocumentBox(gray, w, h)

            // 4. 映射回原图尺寸并裁剪
            val cropped = if (bbox != null && bbox.width() > 20 && bbox.height() > 20) {
                val sx = bitmap.width.toFloat() / w
                val sy = bitmap.height.toFloat() / h
                val left = (bbox.left * sx).toInt().coerceIn(0, bitmap.width - 1)
                val top = (bbox.top * sy).toInt().coerceIn(0, bitmap.height - 1)
                val right = (bbox.right * sx).toInt().coerceIn(left + 1, bitmap.width)
                val bottom = (bbox.bottom * sy).toInt().coerceIn(top + 1, bitmap.height)
                try {
                    Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                } catch (e: Exception) {
                    bitmap
                }
            } else {
                bitmap
            }

            // 5. 增强对比度
            val enhanced = enhanceContrast(cropped)

            // 释放中间位图（若非原图）
            if (work !== bitmap) work.recycle()

            enhanced
        } catch (e: Exception) {
            Log.e(TAG, "文档处理失败，返回原图", e)
            bitmap
        }
    }

    /**
     * 用行/列亮度投影找出文档区域（纸张通常是最亮的矩形）
     */
    private fun findDocumentBox(gray: IntArray, w: Int, h: Int): Rect? {
        // 计算灰度均值作为阈值
        var sum = 0L
        for (g in gray) sum += g
        val mean = (sum / gray.size).toInt()
        val brightThreshold = max(mean, 100)

        // 每行的亮点数
        val rowBright = IntArray(h)
        val colBright = IntArray(w)
        for (y in 0 until h) {
            var count = 0
            val base = y * w
            for (x in 0 until w) {
                if (gray[base + x] >= brightThreshold) {
                    count++
                    colBright[x]++
                }
            }
            rowBright[y] = count
        }

        // 找到主要亮度的连续行区间
        val rowSpan = findLongestSpan(rowBright, (w * 0.3).toInt())
        val colSpan = findLongestSpan(colBright, (h * 0.3).toInt())  // 阈值用列高

        if (rowSpan == null || colSpan == null) return null

        return Rect(colSpan.first, rowSpan.first, colSpan.second, rowSpan.second)
    }

    /**
     * 在数组中找最长的连续连续段，其中值都 >= minCount
     * 返回 [start, end)（start/end 为索引）
     */
    private fun findLongestSpan(arr: IntArray, minCount: Int): Pair<Int, Int>? {
        var bestStart = -1
        var bestEnd = -1
        var curStart = -1
        for (i in arr.indices) {
            if (arr[i] >= minCount) {
                if (curStart == -1) curStart = i
            } else {
                if (curStart != -1) {
                    if (i - curStart > bestEnd - bestStart) {
                        bestStart = curStart
                        bestEnd = i
                    }
                    curStart = -1
                }
            }
        }
        if (curStart != -1 && arr.size - curStart > bestEnd - bestStart) {
            bestStart = curStart
            bestEnd = arr.size
        }
        if (bestStart == -1 || bestEnd - bestStart < 30) return null
        return bestStart to bestEnd
    }

    /**
     * 对比度增强：直方图拉伸，让文字更清晰
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        // 缩小后再增强，避免大图像素处理卡顿
        val scale = min(1.0, 1200.0 / max(bitmap.width, bitmap.height))
        val src = if (scale < 1.0) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }

        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 计算灰度直方图
        val hist = IntArray(256)
        for (p in pixels) {
            val g = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            hist[g]++
        }

        // 计算直方图拉伸的上下界（忽略头尾 1% 像素，防止极端值干扰）
        var lo = 0
        var hi = 255
        val total = pixels.size
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc > total / 200) { lo = i; break }
        }
        acc = 0
        for (i in 255 downTo 0) {
            acc += hist[i]
            if (acc > total / 200) { hi = i; break }
        }
        if (hi - lo < 30) return bitmap  // 对比度已足够，无需处理

        // 拉伸
        val result = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = stretch(Color.red(p), lo, hi)
            val g = stretch(Color.green(p), lo, hi)
            val b = stretch(Color.blue(p), lo, hi)
            result[i] = Color.rgb(r, g, b)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)

        if (src !== bitmap) src.recycle()
        return out
    }

    private fun stretch(v: Int, lo: Int, hi: Int): Int {
        val x = (v - lo) * 255 / (hi - lo)
        return x.coerceIn(0, 255)
    }
}
