package com.huawei.docscanner.util

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * OpenCV 文档扫描处理器
 * 流程：灰度 -> 模糊 -> Canny边缘 -> 轮廓查找 -> 找最大四边形 -> 透视矫正 -> 增强
 */
object ScanProcessor {

    private const val TAG = "ScanProcessor"

    /**
     * 校正文档：检测边缘、透视矫正并增强对比度
     * @return 校正后的 Bitmap；若未检测到文档边缘返回 null
     */
    fun correctDocument(bitmap: Bitmap): Bitmap? {
        return try {
            // Bitmap -> Mat
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)

            // 转为灰度
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

            // 高斯模糊减少噪声
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            // Canny 边缘检测
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 75.0, 200.0)

            // 形态学膨胀，连接边缘
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(edges, edges, kernel)

            // 找轮廓
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            // 找最大四边形（文档）
            val docContour = findLargestQuad(contours)

            if (docContour == null) {
                Log.d(TAG, "未检测到文档边缘")
                releaseMats(src, gray, blurred, edges, hierarchy)
                return null
            }

            // 透视矫正
            val warped = warpPerspective(src, docContour)
            releaseMats(src, gray, blurred, edges, hierarchy)

            if (warped == null) {
                Log.d(TAG, "透视变换失败")
                return null
            }

            // 增强对比度（灰度 -> 自适应二值化，让文字更清晰）
            val enhanced = enhance(warped)
            releaseMat(warped)

            // Mat -> Bitmap
            val result = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(enhanced, result)
            releaseMat(enhanced)

            result
        } catch (e: Exception) {
            Log.e(TAG, "文档校正失败", e)
            null
        }
    }

    /**
     * 在轮廓列表中找到面积最大的四边形
     */
    private fun findLargestQuad(contours: List<MatOfPoint>): MatOfPoint? {
        var largest: MatOfPoint? = null
        var maxArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < 10000.0) continue  // 忽略太小的轮廓

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

    /**
     * 对检测到的四边形做透视矫正，得到正视图
     */
    private fun warpPerspective(src: Mat, quad: MatOfPoint): Mat? {
        val points = quad.toArray()
        if (points.size != 4) return null

        // 排序并计算四边长度确定宽高
        val tl = points.minByOrNull { it.x + it.y }!!
        val br = points.maxByOrNull { it.x + it.y }!!
        val tr = points.maxByOrNull { it.x - it.y }!!
        val bl = points.minByOrNull { it.x - it.y }!!

        val widthTop = dist(tl, tr)
        val widthBottom = dist(bl, br)
        val width = maxOf(widthTop, widthBottom)

        val heightLeft = dist(tl, bl)
        val heightRight = dist(tr, br)
        val height = maxOf(heightLeft, heightRight)

        if (width < 50 || height < 50) return null

        val resultTl = Point(0.0, 0.0)
        val resultTr = Point(width - 1, 0.0)
        val resultBr = Point(width - 1, height - 1)
        val resultBl = Point(0.0, height - 1)

        val srcMat = MatOfPoint2f(tl, tr, br, bl)
        val destMat = MatOfPoint2f(resultTl, resultTr, resultBr, resultBl)

        val transform = Imgproc.getPerspectiveTransform(srcMat, destMat)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(width, height))
        return warped
    }

    /**
     * 增强：灰度 + 自适应阈值二值化，让文字清晰
     */
    private fun enhance(src: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)

        // 自适应阈值二值化（更适合文档）
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            gray, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY, 31, 15.0
        )
        releaseMat(gray)
        return binary
    }

    private fun dist(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return Math.sqrt(dx * dx + dy * dy)
    }

    private fun releaseMats(vararg mats: Mat) {
        mats.forEach(::releaseMat)
    }

    private fun releaseMat(mat: Mat) {
        runCatching { mat.release() }
    }
}
