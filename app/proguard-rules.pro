# Add project specific ProGuard rules here.
# Keep OpenCV classes
-keep class org.opencv.** { *; }

# Keep Tesseract classes
-keep class com.googlecode.tesseract.** { *; }
-keep class com.googlecode.leptonica.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }

# Keep native methods
-keepclasseswithmembers class * {
    native <methods>;
}
