package com.huawei.docscanner.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.huawei.docscanner.R
import com.huawei.docscanner.util.ImageUtil
import com.huawei.docscanner.util.OcrUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var scanButton: Button
    private lateinit var imagePreview: ImageView
    private lateinit var resultText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var ocrButton: Button
    private lateinit var saveButton: Button

    private var currentBitmap: Bitmap? = null
    private var currentPages: ArrayList<Bitmap>? = null

    private val TAG = "DocScanner"

    // Activity Result Launcher for document scanner
    private val scannerLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val resultData = result.data
            if (resultData != null) {
                // Get scanned pages
                val pages = resultData.getParcelableArrayListExtra<Bitmap>("pages")
                if (pages != null && pages.isNotEmpty()) {
                    currentPages = pages
                    currentBitmap = pages[0]
                    imagePreview.setImageBitmap(currentBitmap)
                    ocrButton.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                    resultText.text = "扫描成功，共 ${pages.size} 页\n点击识别文字获取内容"
                    Log.d(TAG, "Scanned ${pages.size} pages")
                }
            }
        } else {
            Log.d(TAG, "Scanner cancelled or failed")
        }
    }

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            launchScanner()
        } else {
            Toast.makeText(this, "需要相机权限才能扫描文档", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        scanButton = findViewById(R.id.btn_scan)
        imagePreview = findViewById(R.id.image_preview)
        resultText = findViewById(R.id.text_result)
        progressBar = findViewById(R.id.progress_bar)
        ocrButton = findViewById(R.id.btn_ocr)
        saveButton = findViewById(R.id.btn_save)

        ocrButton.visibility = View.GONE
        saveButton.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun setupListeners() {
        scanButton.setOnClickListener {
            if (checkCameraPermission()) {
                launchScanner()
            } else {
                requestCameraPermission()
            }
        }

        ocrButton.setOnClickListener {
            currentBitmap?.let { bitmap ->
                performOcr(bitmap)
            }
        }

        saveButton.setOnClickListener {
            currentBitmap?.let { bitmap ->
                saveImage(bitmap)
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
    }

    private fun launchScanner() {
        try {
            // Using HMS ML Kit Document Scanner
            val scanner = com.huawei.hms.mlplugin.documentdecognition.MLDocumentAnalyzerFactory
                .getInstance()
                .documentAnalyzer

            val intent = Intent(this, scanner::class.java)
            scannerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch scanner", e)
            Toast.makeText(this, "扫描启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun performOcr(bitmap: Bitmap) {
        progressBar.visibility = View.VISIBLE
        resultText.text = "正在识别文字..."
        ocrButton.isEnabled = false

        OcrUtil.recognizeText(this, bitmap) { text, success ->
            runOnUiThread {
                progressBar.visibility = View.GONE
                ocrButton.isEnabled = true
                if (success) {
                    resultText.text = text ?: "未识别到文字"
                    Toast.makeText(this, "识别完成", Toast.LENGTH_SHORT).show()
                } else {
                    resultText.text = "识别失败，请重试"
                    Toast.makeText(this, "文字识别失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveImage(bitmap: Bitmap) {
        try {
            val fileName = "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            val savedPath = ImageUtil.saveBitmap(this, bitmap, fileName)
            if (savedPath != null) {
                Toast.makeText(this, "已保存到: $savedPath", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
