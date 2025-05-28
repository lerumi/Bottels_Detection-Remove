package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.renderscript.*
import android.util.Log
import androidx.lifecycle.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.*

class ObjectRemovalViewModel(private val context: Context) : ViewModel() {
    private val _processedImage = MutableStateFlow<Bitmap?>(null)
    val processedImage = _processedImage.asStateFlow()

    private val _isObjectRemovalEnabled = MutableStateFlow(false)
    val isObjectRemovalEnabled = _isObjectRemovalEnabled.asStateFlow()

    fun toggleObjectRemoval() {
        _isObjectRemovalEnabled.value = !_isObjectRemovalEnabled.value
    }

    private val objectDetector: ObjectDetector
    private var detectionJob: Job? = null
    private var latestBitmap: Bitmap? = null

    init {
        try {
            System.loadLibrary("opencv_java4")
            Log.d("OpenCV", "OpenCV loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("OpenCV", "Error loading OpenCV: ${e.message}")
            // Попробуйте альтернативный способ загрузки
            OpenCVLoader.initDebug()
        }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("model.tflite")
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMaxResults(10)
            .setResultListener(this::processDetectionResult)
            .build()

        objectDetector = ObjectDetector.createFromOptions(context, options)
    }

    fun processImage(bitmap: Bitmap) {
        latestBitmap = bitmap
        detectionJob?.cancel()
        detectionJob = viewModelScope.launch {
            val mpImage = BitmapImageBuilder(bitmap).build()
            objectDetector.detectAsync(mpImage, System.currentTimeMillis())
        }
    }

    private fun processDetectionResult(result: ObjectDetectorResult, inputImage: MPImage) {
        latestBitmap?.let { bitmap ->
            val processedBitmap = if (_isObjectRemovalEnabled.value) {
                removeDetectedObjects(bitmap, result)
            } else {
                drawDetectionBoxes(bitmap, result)
            }
            _processedImage.value = processedBitmap
            Log.d("UpdateImage", "processedImage updated")
        }
    }
    private fun splitRectIntoParts(rect: Rect, parts: Int): List<Rect> {
        val partWidth = rect.width() / parts
        val partHeight = rect.height() / parts

        return (0 until parts).flatMap { x ->
            (0 until parts).map { y ->
                Rect(
                    rect.left + x * partWidth,
                    rect.top + y * partHeight,
                    rect.left + (x + 1) * partWidth,
                    rect.top + (y + 1) * partHeight
                )
            }
        }
    }

    private fun removeDetectedObjects(bitmap: Bitmap, detectionResult: ObjectDetectorResult): Bitmap {
        return try {
            // 1. Создаем гарантированно совместимый Bitmap
            val srcBitmap = createCompatibleBitmap(bitmap)

            // 2. Создаем маску (используем RGB_565 для лучшей совместимости)
            val mask = Bitmap.createBitmap(srcBitmap.width, srcBitmap.height, Bitmap.Config.RGB_565)
            val canvas = Canvas(mask)
            val paint = Paint().apply {
                color = Color.WHITE // Чистый белый цвет
                style = Paint.Style.FILL
                isAntiAlias = true
                alpha = 255
            }

            // 3. Рисуем области для удаления
            detectionResult.detections()
                .filter { it.categories().any { cat -> cat.categoryName().contains("bottle", true) } }
                .forEach { detection ->
                    val box = detection.boundingBox()
                    val rect = Rect(
                        max(0, box.left.toInt() - 10),
                        max(0, box.top.toInt() - 10),
                        min(srcBitmap.width, box.right.toInt() + 10),
                        min(srcBitmap.height, box.bottom.toInt() + 10)
                    )
                    // Заменяем drawRect на кистевую маску
                    canvas.drawRect(rect, paint)


                    Log.d("MaskDraw", "Drawing rect: $rect with color: ${paint.color}")
                }

            // 4. Проверяем маску перед конвертацией
            Log.d("MaskDebug", "Mask has non-zero pixels: ${hasNonZeroPixels(mask)}")

            // 5. Конвертируем в Mat
            val srcMat = Mat()
            val maskMat = Mat()
            Utils.bitmapToMat(srcBitmap, srcMat)
            Utils.bitmapToMat(mask, maskMat)

            // 6. Конвертируем цветовые пространства
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(maskMat, maskMat, Imgproc.COLOR_RGB2GRAY)

            // 7. Проверяем маску после конвертации
            Log.d("InpaintDebug", "maskMat non-zero pixels: ${Core.countNonZero(maskMat)}")
            if (Core.countNonZero(maskMat) == 0) {
                Log.e("InpaintDebug", "Mask is empty after conversion!")
                return removeObjectsWithFallback(bitmap, detectionResult)
            }

            // 8. Inpainting
            val resultMat = Mat()
            Photo.inpaint(srcMat, maskMat, resultMat, 10.0, Photo.INPAINT_TELEA)

            // 9. Конвертируем обратно в Bitmap
            val resultBitmap = matToBitmap(resultMat)

            // 10. Освобождаем ресурсы
            srcMat.release()
            maskMat.release()
            resultMat.release()

            resultBitmap
        } catch (e: Exception) {
            Log.e("ObjectRemoval", "Error in OpenCV processing", e)
            removeObjectsWithFallback(bitmap, detectionResult)
        }
    }

    private fun hasNonZeroPixels(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { it != 0 }
    }

    private fun createCompatibleBitmap(bitmap: Bitmap): Bitmap {
        // Создаем новый Bitmap с гарантированно правильным форматом
        val compatibleBitmap = Bitmap.createBitmap(
            bitmap.width,
            bitmap.height,
            Bitmap.Config.ARGB_8888
        )

        // Копируем пиксели через Canvas
        val canvas = Canvas(compatibleBitmap)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return compatibleBitmap
    }


    private fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }



    private fun removeObjectsWithFallback(bitmap: Bitmap, detectionResult: ObjectDetectorResult): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }

        detectionResult.detections()
            .filter { it.categories().any { cat -> cat.categoryName().contains("bottle", true) && cat.score() > 0.5} }
            .forEach { detection ->
                val box = detection.boundingBox()
                val rect = Rect(
                    max(0, box.left.toInt() - 10),
                    max(0, box.top.toInt() - 10),
                    min(bitmap.width, box.right.toInt() + 10),
                    min(bitmap.height, box.bottom.toInt() + 10)
                )

                // Простая замена на соседние пиксели
                val patch = getBestNeighborPatch(bitmap, rect)
                patch?.let {
                    canvas.drawBitmap(it, rect.left.toFloat(), rect.top.toFloat(), paint)
                }
            }

        return result
    }

    private fun getBestNeighborPatch(bitmap: Bitmap, area: Rect): Bitmap? {
        val width = area.width()
        val height = area.height()

        // Пробуем получить патчи со всех сторон
        val candidates = listOf(
            Rect(area.right, area.top, min(bitmap.width, area.right + width), area.bottom), // справа
            Rect(max(0, area.left - width), area.top, area.left, area.bottom), // слева
            Rect(area.left, max(0, area.top - height), area.right, area.top), // сверху
            Rect(area.left, area.bottom, area.right, min(bitmap.height, area.bottom + height)) // снизу
        )

        return candidates.firstOrNull { rect ->
            rect.width() == width && rect.height() == height
        }?.let {
            Bitmap.createBitmap(bitmap, it.left, it.top, width, height)
        }
    }


    private fun drawDetectionBoxes(bitmap: Bitmap, detectionResult: ObjectDetectorResult): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val boxPaint = Paint().apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 36f
        }

        detectionResult.detections().filter{ it.categories().any { cat -> cat.categoryName().contains("bottle", true) && cat.score() > 0.5 } }
            .forEach { detection ->
            val box = detection.boundingBox()
            canvas.drawRect(box, boxPaint)
            detection.categories().firstOrNull()?.let {
                canvas.drawText("${it.categoryName()} (${(it.score() * 100).toInt()}%)", box.left, box.top - 10, textPaint)
            }
        }
        return mutable
    }

    override fun onCleared() {
        objectDetector.close()
    }
}

class ObjectRemovalViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ObjectRemovalViewModel::class.java)) {
            return ObjectRemovalViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
