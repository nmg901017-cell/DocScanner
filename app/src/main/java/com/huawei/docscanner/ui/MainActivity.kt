package com.huawei.docscanner.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.huawei.docscanner.R
import com.huawei.docscanner.util.ImageUtil
import com.huawei.docscanner.util.OcrUtil
import com.huawei.docscanner.util.ScanProcessor
import com.huawei.docscanner.util.ScanProcessor.Filter
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var mainImage: ImageView
    private lateinit var hint: TextView
    private lateinit var pageCount: TextView
    private lateinit var pageStripInner: LinearLayout
    private lateinit var pageStrip: android.widget.HorizontalScrollView
    private lateinit var filterRow: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var btnScan: Button
    private lateinit var btnOcr: Button
    private lateinit var btnSave: Button
    private lateinit var btnPdf: Button
    private lateinit var btnShare: Button

    /** 一页扫描数据 */
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
    private var pendingPhotoUri: Uri? = null
    private val TAG = "DocScanner"

    private val cameraLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = pendingPhotoUri
            if (uri != null) {
                val bitmap = loadBitmapFromUri(uri)
                if (bitmap != null) {
                    addNewPage(bitmap)
                } else {
                    Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] ?: false) openCamera()
        else Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            initViews()
            buildFilterChips()
            setupListeners()
        } catch (e: Throwable) {
            showError(e)
        }
    }

    private fun initViews() {
        mainImage = findViewById(R.id.main_image)
        hint = findViewById(R.id.hint)
        pageCount = findViewById(R.id.page_count)
        pageStripInner = findViewById(R.id.page_strip_inner)
        pageStrip = findViewById(R.id.page_strip)
        filterRow = findViewById(R.id.filter_row)
        progress = findViewById(R.id.progress)
        btnScan = findViewById(R.id.btn_scan)
        btnOcr = findViewById(R.id.btn_ocr)
        btnSave = findViewById(R.id.btn_save)
        btnPdf = findViewById(R.id.btn_pdf)
        btnShare = findViewById(R.id.btn_share)
        updatePageCount()
    }

    private fun setupListeners() {
        btnScan.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        btnOcr.setOnClickListener { runOcr() }
        btnSave.setOnClickListener { saveCurrent() }
        btnPdf.setOnClickListener { exportPdf() }
        btnShare.setOnClickListener { shareCurrent() }
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
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(8)
                layoutParams = lp
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
            chip.tag = index
            filterRow.addView(chip)
        }
    }

    private fun refreshFilterChips() {
        val current = if (selectedIndex in pages.indices) pages[selectedIndex].filter else null
        for (i in 0 until filterRow.childCount) {
            val chip = filterRow.getChildAt(i) as TextView
            val isSelected = current != null && Filter.values()[i] == current
            chip.background = ContextCompat.getDrawable(
                this,
                if (isSelected) R.drawable.chip_bg_selected else R.drawable.chip_bg
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isSelected) R.color.white else R.color.text_primary
                )
            )
        }
    }

    // ==================== 扫描 ====================

    private fun openCamera() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val photoFile = File(dir, "scan_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "com.huawei.docscanner.fileprovider", photoFile)
            pendingPhotoUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            cameraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未找到相机", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val stream = contentResolver.openInputStream(uri) ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            stream.close()
            var sample = 1
            while (opts.outWidth / sample > 2600 || opts.outHeight / sample > 2600) sample *= 2
            val s2 = contentResolver.openInputStream(uri) ?: return null
            val bmp = BitmapFactory.decodeStream(s2, null, BitmapFactory.Options().apply { inSampleSize = sample })
            s2.close()
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun addNewPage(bitmap: Bitmap) {
        progress.visibility = View.VISIBLE
        hint.visibility = View.GONE
        btnScan.isEnabled = false
        Thread {
            val corrected = ScanProcessor.correctDocument(bitmap)
            runOnUiThread {
                progress.visibility = View.GONE
                btnScan.isEnabled = true
                pages.add(Page(corrected))
                selectedIndex = pages.size - 1
                refreshPageStrip()
                updatePageCount()
                renderSelected()
                pageStrip.visibility = View.VISIBLE
                filterRow.visibility = View.VISIBLE
                mainImage.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun renderSelected() {
        mainImage.visibility = View.VISIBLE
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
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(96))
            }
            val label = TextView(this).apply {
                text = "${index + 1}"
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                gravity = Gravity.CENTER
            }
            lv.addView(img)
            lv.addView(label)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            lv.layoutParams = lp
            lv.setOnClickListener { selectedIndex = index; renderSelected() }
            pageStripInner.addView(lv)
        }
    }

    private fun updatePageCount() {
        pageCount.text = "${pages.size} 页"
    }

    // ==================== OCR ====================

    private fun runOcr() {
        if (selectedIndex !in pages.indices) { Toast.makeText(this, "请先扫描", Toast.LENGTH_SHORT).show(); return }
        val bitmap = pages[selectedIndex].ensureDisplay()
        progress.visibility = View.VISIBLE
        btnOcr.isEnabled = false
        OcrUtil.recognizeText(this, bitmap) { text, ok ->
            runOnUiThread {
                progress.visibility = View.GONE
                btnOcr.isEnabled = true
                if (ok && !text.isNullOrBlank()) {
                    showOcrResult(text)
                } else {
                    Toast.makeText(this, "未识别到文字", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showOcrResult(text: String) {
        // 用系统分享/复制展示 OCR 结果
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("识别结果")
            .setMessage(text)
            .setPositiveButton("复制", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr", text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 保存 / PDF / 分享 ====================

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
                if (path != null) {
                    Toast.makeText(this, "PDF 已导出: $path", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "PDF 导出失败", Toast.LENGTH_SHORT).show()
                }
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
        } else {
            Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 工具 ====================
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showError(e: Throwable) {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        runCatching { File(filesDir, "crash_log.txt").writeText(e.toString() + "\n" + sw.toString()) }
        val tv = TextView(this).apply {
            text = "出错: " + e.toString() + "\n\n" + sw.toString()
            textSize = 13f
            setPadding(24, 48, 24, 24)
        }
        setContentView(tv)
    }
}
