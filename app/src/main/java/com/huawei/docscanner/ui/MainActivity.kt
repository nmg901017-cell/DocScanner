package com.huawei.docscanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.huawei.docscanner.R
import com.huawei.docscanner.util.ImageUtil
import com.huawei.docscanner.util.OcrUtil
import com.huawei.docscanner.util.ScanProcessor
import com.huawei.docscanner.util.ScanProcessor.Filter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var docGuide: View
    private lateinit var mainImage: ImageView
    private lateinit var captureBtn: FrameLayout
    private lateinit var hint: TextView
    private lateinit var pageCount: TextView
    private lateinit var pageStrip: HorizontalScrollView
    private lateinit var pageStripInner: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var progress: ProgressBar

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val TAG = "DocScanner"

    private class Page(val corrected: Bitmap, var filter: Filter = Filter.SCAN) {
        var display: Bitmap? = null
        fun ensureDisplay(): Bitmap {
            if (display == null) display = ScanProcessor.applyFilter(corrected, filter)
            return display!!
        }
        fun invalidate() { display = null }
    }

    private val pages = ArrayList<Page>()
    private var selectedIndex = -1
    private var cameraActive = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] ?: false) enterCameraMode()
        else Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            initViews()
            buildFilterChips()
            setupListeners()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                enterCameraMode()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
        } catch (e: Throwable) {
            showError(e)
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.preview_view)
        docGuide = findViewById(R.id.doc_guide)
        mainImage = findViewById(R.id.main_image)
        captureBtn = findViewById(R.id.capture_btn)
        hint = findViewById(R.id.hint)
        pageCount = findViewById(R.id.page_count)
        pageStrip = findViewById(R.id.page_strip)
        pageStripInner = findViewById(R.id.page_strip_inner)
        filterRow = findViewById(R.id.filter_row)
        progress = findViewById(R.id.progress)
        updatePageCount()
    }

    private fun setupListeners() {
        findViewById<LinearLayout>(R.id.item_scan).setOnClickListener { enterCameraMode() }
        findViewById<LinearLayout>(R.id.item_ocr).setOnClickListener { runOcr() }
        findViewById<LinearLayout>(R.id.item_save).setOnClickListener { saveCurrent() }
        findViewById<LinearLayout>(R.id.item_pdf).setOnClickListener { exportPdf() }
        findViewById<LinearLayout>(R.id.item_share).setOnClickListener { shareCurrent() }
        captureBtn.setOnClickListener { takePhoto() }
    }

    // ==================== 相机模式 ====================

    private fun enterCameraMode() {
        cameraActive = true
        previewView.visibility = View.VISIBLE
        docGuide.visibility = View.VISIBLE
        captureBtn.visibility = View.VISIBLE
        mainImage.visibility = View.GONE
        hint.visibility = View.GONE
        if (pages.isEmpty()) {
            pageStrip.visibility = View.GONE
            filterRow.visibility = View.GONE
        }
        startCamera()
    }

    private fun showResultMode() {
        cameraActive = false
        previewView.visibility = View.GONE
        docGuide.visibility = View.GONE
        captureBtn.visibility = View.GONE
        mainImage.visibility = View.VISIBLE
        hint.visibility = View.GONE
        pageStrip.visibility = View.VISIBLE
        filterRow.visibility = View.VISIBLE
        stopCamera()
    }

    private fun startCamera() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                cameraProvider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        progress.visibility = View.VISIBLE
        captureBtn.isEnabled = false
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees.toFloat()
                    image.close()
                    val rotated = if (rotation != 0f) rotateBitmap(bitmap, rotation) else bitmap
                    processCaptured(rotated)
                }

                override fun onError(exception: ImageCaptureException) {
                    progress.visibility = View.GONE
                    captureBtn.isEnabled = true
                    Toast.makeText(this@MainActivity, "拍摄失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        return try {
            val m = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun processCaptured(bitmap: Bitmap) {
        val scaled = downscale(bitmap)
        Thread {
            val corrected = ScanProcessor.correctDocument(scaled)
            runOnUiThread {
                progress.visibility = View.GONE
                captureBtn.isEnabled = true
                pages.add(Page(corrected))
                selectedIndex = pages.size - 1
                refreshPageStrip()
                updatePageCount()
                renderSelected()
                showResultMode()
            }
        }.start()
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val maxDim = 2200
        val scale = minOf(1.0, maxDim.toDouble() / maxOf(bitmap.width, bitmap.height))
        return if (scale < 1.0) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    }

    // ==================== 滤镜 ====================

    private fun buildFilterChips() {
        filterRow.removeAllViews()
        Filter.values().forEachIndexed { index, filter ->
            val chip = TextView(this).apply {
                text = filter.label
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.chip_bg)
                setOnClickListener {
                    if (selectedIndex in pages.indices) {
                        pages[selectedIndex].filter = filter
                        pages[selectedIndex].invalidate()
                        renderSelected()
                        refreshFilterChips()
                    }
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            chip.layoutParams = lp
            filterRow.addView(chip)
        }
    }

    private fun refreshFilterChips() {
        val current = if (selectedIndex in pages.indices) pages[selectedIndex].filter else null
        for (i in 0 until filterRow.childCount) {
            val chip = filterRow.getChildAt(i) as TextView
            val isSelected = current != null && Filter.values()[i] == current
            chip.background = ContextCompat.getDrawable(this, if (isSelected) R.drawable.chip_bg_selected else R.drawable.chip_bg)
            chip.setTextColor(ContextCompat.getColor(this, if (isSelected) R.color.white else R.color.text_primary))
        }
    }

    private fun renderSelected() {
        if (selectedIndex in pages.indices) {
            mainImage.setImageBitmap(pages[selectedIndex].ensureDisplay())
            refreshFilterChips()
        }
    }

    private fun refreshPageStrip() {
        pageStripInner.removeAllViews()
        pages.forEachIndexed { index, page ->
            val lv = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.page_count_bg)
            }
            val img = ImageView(this).apply {
                setImageBitmap(page.corrected)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(68), dp(92))
            }
            val label = TextView(this).apply {
                text = "${index + 1}"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                gravity = Gravity.CENTER
            }
            lv.addView(img)
            lv.addView(label)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            lv.layoutParams = lp
            lv.setOnClickListener { selectedIndex = index; renderSelected() }
            pageStripInner.addView(lv)
        }
    }

    private fun updatePageCount() {
        pageCount.text = "${pages.size} 页"
    }

    // ==================== OCR / 保存 / PDF / 分享 ====================

    private fun runOcr() {
        if (selectedIndex !in pages.indices) { Toast.makeText(this, "请先扫描", Toast.LENGTH_SHORT).show(); return }
        val bitmap = pages[selectedIndex].ensureDisplay()
        progress.visibility = View.VISIBLE
        OcrUtil.recognizeText(this, bitmap) { text, ok ->
            runOnUiThread {
                progress.visibility = View.GONE
                if (ok && !text.isNullOrBlank()) showOcrResult(text)
                else Toast.makeText(this, "未识别到文字", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOcrResult(text: String) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("识别结果")
            .setMessage(text)
            .setPositiveButton("复制", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCurrent() {
        if (selectedIndex !in pages.indices) { Toast.makeText(this, "请先扫描", Toast.LENGTH_SHORT).show(); return }
        val path = ImageUtil.saveBitmapToPictures(this, pages[selectedIndex].ensureDisplay())
        Toast.makeText(this, if (path != null) "已保存到相册" else "保存失败", Toast.LENGTH_LONG).show()
    }

    private fun exportPdf() {
        if (pages.isEmpty()) { Toast.makeText(this, "请先扫描", Toast.LENGTH_SHORT).show(); return }
        progress.visibility = View.VISIBLE
        Thread {
            val path = ImageUtil.exportPdf(this, pages.map { it.ensureDisplay() })
            runOnUiThread {
                progress.visibility = View.GONE
                Toast.makeText(this, if (path != null) "PDF 已导出" else "PDF 导出失败", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun shareCurrent() {
        if (selectedIndex !in pages.indices) { Toast.makeText(this, "请先扫描", Toast.LENGTH_SHORT).show(); return }
        val uri = ImageUtil.saveToCacheAndGetUri(this, pages[selectedIndex].ensureDisplay())
        if (uri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享"))
        } else Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show()
    }

    // ==================== 其他 ====================

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        executor.shutdown()
    }

    private fun showError(e: Throwable) {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        val tv = TextView(this).apply {
            text = "出错: " + e.toString() + "\n\n" + sw.toString()
            textSize = 13f
            setPadding(24, 48, 24, 24)
        }
        setContentView(tv)
    }
}
