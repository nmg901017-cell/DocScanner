package com.huawei.docscanner.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * 文档扫描处理器
 * - correctionDocument: 透视矫正 + 自动裁剪（OpenCV，失败降级纯 Java）
 * - applyFilter: 滤镜（原图/扫描/灰度/黑白），纯 Java
 */
object ScanProcessor {

    private const val TAG = "ScanProcessor"
    val opencvReady: Boolean = runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)

    /** 滤镜类型 */
    enum class Filter(val label: String) {
        ORIGINAL("原图"),
        SCAN("扫描"),
        GRAY("灰度"),
        BW("黑白")
    }

    /**
     * 矫正文档：透视矫正 + 裁剪。优先 OpenCV，失败用纯 Java 亮度投影裁剪。
     * 返回色彩图（未加滤镜）。
     */
    fun correctDocument(bitmap: Bitmap): Bitmap {
        if (opencvReady) {
            val cvResult = runCatching { openCvCorrect(bitmap) }.getOrNull()
            if (cvResult != null) return cvResult
        }
        return pureJavaCorrect(bitmap)
    }

    /**
     * 应用滤镜
     */
    fun applyFilter(bitmap: Bitmap, filter: Filter): Bitmap {
        return when (filter) {
            Filter.ORIGINAL -> bitmap // 原图：保持色彩
            Filter.SCAN -> scanEnhance(bitmap)   // 扫描模式：灰度+对比度增强
            Filter.GRAY -> toGray(bitmap)        // 灰度
            Filter.BW -> toBinary(bitmap)        // 黑白二值
        }
    }

    // ==================== OpenCV 透视矫正 ====================

    private fun openCvCorrect(bitmap: Bitmap): Bitmap? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // 缩小到工作尺寸
        val workScale = min(1.0, 1600.0 / max(src.cols(), src.rows()))
        val work = Mat()
        if (workScale < 1.0) {
            Imgproc.resize(src, work, Size((src.cols() * workScale), (src.rows() * workScale)))
        } else {
            src.copyTo(work)
        }

        val gray = Mat()
        Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 75.0, 200.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(edges, edges, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val docContour = findLargestQuad(contours)
        val warped = if (docContour != null) warpPerspective(work, docContour) else null

        // 释放
        listOf(src, work, gray, blurred, edges, hierarchy).forEach { runCatching { it.release() } }

        if (warped == null) return null
        val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, result)
        runCatching { warped.release() }
        return result
    }

    private fun findLargestQuad(contours: List<MatOfPoint>): MatOfPoint? {
        var largest: MatOfPoint? = null
        var maxArea = 0.0
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < 10000.0) continue
            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)
            if (approx.total() == 4L && area > maxArea) {
                maxArea = area
                largest = MatOfPoint(*approx.toArray())
            }
        }
        return largest
    }

    private fun warpPerspective(src: Mat, quad: MatOfPoint): Mat? {
        val points = quad.toArray()
        if (points.size != 4) return null
        val tl = points.minByOrNull { it.x + it.y }!!
        val br = points.maxByOrNull { it.x + it.y }!!
        val tr = points.maxByOrNull { it.x - it.y }!!
        val bl = points.minByOrNull { it.x - it.y }!!
        val width = maxOf(dist(tl, tr), dist(bl, br))
        val height = maxOf(dist(tl, bl), dist(tr, br))
        if (width < 50 || height < 50) return null
        val srcMat = MatOfPoint2f(tl, tr, br, bl)
        val destMat = MatOfPoint2f(Point(0.0, 0.0), Point(width - 1, 0.0), Point(width - 1, height - 1), Point(0.0, height - 1))
        val transform = Imgproc.getPerspectiveTransform(srcMat, destMat)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(width, height))
        return warped
    }

    private fun dist(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return Math.sqrt(dx * dx + dy * dy)
    }

    // ==================== 纯 Java 兜底矫正 ====================

    private fun pureJavaCorrect(bitmap: Bitmap): Bitmap {
        return try {
            // 缩小处理
            val scale = min(1.0, 1600.0 / max(bitmap.width, bitmap.height))
            val work = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val w = work.width
            val h = work.height
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }
            val bbox = findDocumentBox(gray, w, h)
            val cropped = if (bbox != null && bbox.width() > 20 && bbox.height() > 20) {
                val sx = bitmap.width.toFloat() / w
                val sy = bitmap.height.toFloat() / h
                val left = (bbox.left * sx).toInt().coerceIn(0, bitmap.width - 1)
                val top = (bbox.top * sy).toInt().coerceIn(0, bitmap.height - 1)
                val right = (bbox.right * sx).toInt().coerceIn(left + 1, bitmap.width)
                val bottom = (bbox.bottom * sy).toInt().coerceIn(top + 1, bitmap.height)
                runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }.getOrDefault(bitmap)
            } else bitmap
            if (work !== bitmap) work.recycle()
            cropped
        } catch (e: Exception) {
            Log.e(TAG, "纯Java矫正失败", e)
            bitmap
        }
    }

    private fun findDocumentBox(gray: IntArray, w: Int, h: Int): Rect? {
        var sum = 0L
        for (g in gray) sum += g
        val mean = (sum / gray.size).toInt()
        val brightThreshold = max(mean, 100)
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
        val rowSpan = findLongestSpan(rowBright, (w * 0.3).toInt())
        val colSpan = findLongestSpan(colBright, (h * 0.3).toInt())
        if (rowSpan == null || colSpan == null) return null
        return Rect(colSpan.first, rowSpan.first, colSpan.second, rowSpan.second)
    }

    private fun findLongestSpan(arr: IntArray, minCount: Int): Pair<Int, Int>? {
        var bestStart = -1
        var bestEnd = -1
        var curStart = -1
        for (i in arr.indices) {
            if (arr[i] >= minCount) {
                if (curStart == -1) curStart = i
            } else {
                if (curStart != -1) {
                    if (i - curStart > bestEnd - bestStart) { bestStart = curStart; bestEnd = i }
                    curStart = -1
                }
            }
        }
        if (curStart != -1 && arr.size - curStart > bestEnd - bestStart) {
            bestStart = curStart; bestEnd = arr.size
        }
        if (bestStart == -1 || bestEnd - bestStart < 30) return null
        return bestStart to bestEnd
    }

    // ==================== 滤镜 ====================

    /** 扫描模式：灰度 + 直方图拉伸（文档白底黑字清晰化） */
    private fun scanEnhance(bitmap: Bitmap): Bitmap = enhance(grayPixels(bitmap))

    /** 灰度 */
    private fun toGray(bitmap: Bitmap): Bitmap = applyPixels(bitmap) { r, g, b ->
        val gray = (r + g + b) / 3
        Color.rgb(gray, gray, gray)
    }

    /** 黑白二值 */
    private fun toBinary(bitmap: Bitmap): Bitmap {
        val gray = grayPixels(bitmap)
        // 计算均值做二值阈值
        var sum = 0L
        gray.forEach { sum += it }
        val mean = (sum / gray.size).toInt()
        return applyPixels(bitmap) { r, g, b ->
            val gv = (r + g + b) / 3
            if (gv >= mean) Color.WHITE else Color.BLACK
        }
    }

    /** 灰度像素数组 */
    private fun grayPixels(bitmap: Bitmap): Bitmap {
        return applyPixels(bitmap) { r, g, b ->
            val gray = (r + g + b) / 3
            Color.rgb(gray, gray, gray)
        }
    }

    /** 直方图拉伸增强 */
    private fun enhance(src: Bitmap): Bitmap {
        val scale = min(1.0, 1200.0 / max(src.width, src.height))
        val work = if (scale < 1.0) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val w = work.width
        val h = work.height
        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (p in pixels) {
            hist[(Color.red(p) + Color.green(p) + Color.blue(p)) / 3]++
        }
        var lo = 0
        var hi = 255
        var acc = 0
        val total = pixels.size
        for (i in 0..255) { acc += hist[i]; if (acc > total / 200) { lo = i; break } }
        acc = 0
        for (i in 255 downTo 0) { acc += hist[i]; if (acc > total / 200) { hi = i; break } }
        if (hi - lo < 30) return src
        val result = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            result[i] = Color.rgb(stretch(Color.red(p), lo, hi), stretch(Color.green(p), lo, hi), stretch(Color.blue(p), lo, hi))
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        if (work !== src) work.recycle()
        return out
    }

    /** 通用像素变换 */
    private fun applyPixels(bitmap: Bitmap, fn: (Int, Int, Int) -> Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            result[i] = fn(Color.red(p), Color.green(p), Color.blue(p))
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        return out
    }

    private fun stretch(v: Int, lo: Int, hi: Int): Int = ((v - lo) * 255 / (hi - lo)).coerceIn(0, 255)
}
