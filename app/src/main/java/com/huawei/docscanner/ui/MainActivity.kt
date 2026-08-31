package com.huawei.docscanner.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.core.content.FileProvider
import com.huawei.docscanner.R
import com.huawei.docscanner.util.ImageUtil
import com.huawei.docscanner.util.OcrUtil
import com.huawei.docscanner.util.ScanProcessor
import org.opencv.android.OpenCVLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var scanButton: Button
    private lateinit var imagePreview: ImageView
    private lateinit var resultText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var ocrButton: Button
    private lateinit var saveButton: Button

    private var currentBitmap: Bitmap? = null
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
                    processImage(bitmap)
                } else {
                    Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "未获取到图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            openCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能扫描文档", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV 初始化失败")
            Toast.makeText(this, "OpenCV 初始化失败", Toast.LENGTH_LONG).show()
        }
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
            if (hasCameraPermission()) {
                openCamera()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
        }
        ocrButton.setOnClickListener {
            currentBitmap?.let { bitmap -> performOcr(bitmap) }
        }
        saveButton.setOnClickListener {
            currentBitmap?.let { bitmap -> saveImage(bitmap) }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun openCamera() {
        try {
            // 创建临时文件接收全分辨率照片
            val imagesDir = File(cacheDir, "images").apply { mkdirs() }
            val photoFile = File(imagesDir, "scan_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "com.huawei.docscanner.fileprovider", photoFile)
            pendingPhotoUri = uri

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            cameraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未找到相机应用", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "相机启动失败", e)
            Toast.makeText(this, "相机启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        // 读取全分辨率图片并按 8 倍缩放防止 OOM
        val imageStream = contentResolver.openInputStream(uri) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageStream, null, opts)
        imageStream.close()

        val maxDim = 3000
        var sampleSize = 1
        while (opts.outWidth / sampleSize > maxDim || opts.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val finalOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val stream2 = contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(stream2, null, finalOpts)
        stream2.close()
        return bitmap
    }

    private fun processImage(bitmap: Bitmap) {
        resultText.text = "正在校正文档..."
        progressBar.visibility = View.VISIBLE
        Thread {
            val corrected = ScanProcessor.correctDocument(bitmap)
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (corrected != null) {
                    currentBitmap = corrected
                    imagePreview.setImageBitmap(corrected)
                    imagePreview.visibility = View.VISIBLE
                    ocrButton.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                    resultText.text = "扫描完成 ✓\n点击\u201C识别文字\u201D提取内容"
                } else {
                    currentBitmap = bitmap
                    imagePreview.setImageBitmap(bitmap)
                    imagePreview.visibility = View.VISIBLE
                    ocrButton.visibility = View.VISIBLE
                    saveButton.visibility = View.VISIBLE
                    resultText.text = "未检测到文档边缘，已保留原图"
                }
            }
        }.start()
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
                } else {
                    resultText.text = "识别失败，请重试"
                }
            }
        }
    }

    private fun saveImage(bitmap: Bitmap) {
        val savedPath = ImageUtil.saveBitmapToPictures(this, bitmap)
        if (savedPath != null) {
            Toast.makeText(this, "已保存到相册", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
}
