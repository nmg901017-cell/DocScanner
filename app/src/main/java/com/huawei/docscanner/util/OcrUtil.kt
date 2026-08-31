package com.huawei.docscanner.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.huawei.hms.mlsdk.MLApplication
import com.huawei.hms.mlsdk.common.MLException
import com.huawei.hms.mlsdk.text.RTRecognizer
import com.huawei.hms.mlsdk.text.Language

object OcrUtil {

    private const val TAG = "OcrUtil"

    fun recognizeText(context: Context, bitmap: Bitmap, callback: (String?, Boolean) -> Unit) {
        try {
            // Initialize ML Application with API key (optional for HMS)
            MLApplication.getInstance().apiKey = ""

            // Create text recognizer
            val recognizer = RTRecognizer.getInstance()

            // Perform OCR
            val task = recognizer.analyseImage(bitmap)
            task.addOnSuccessListener { result ->
                val text = result.string
                Log.d(TAG, "OCR Success: $text")
                callback(text, true)
            }.addOnFailureListener { e ->
                Log.e(TAG, "OCR Failed", e)
                callback(null, false)
            }
        } catch (e: MLException) {
            Log.e(TAG, "MLException during OCR", e)
            callback(null, false)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OCR", e)
            callback(null, false)
        }
    }
}
