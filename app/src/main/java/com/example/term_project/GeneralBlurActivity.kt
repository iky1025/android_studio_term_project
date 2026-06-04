package com.example.term_project

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min

class GeneralBlurActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var imageView: ImageView
    private lateinit var imageContainer: FrameLayout
    private lateinit var btnOpenDrawer: Button
    private lateinit var btnDownload: Button
    private lateinit var txtStatus: TextView
    private lateinit var rvBlurList: RecyclerView

    private var originalBitmap: Bitmap? = null
    private val blurItemsList = ArrayList<BlurItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_blur)

        drawerLayout = findViewById(R.id.drawerLayout)
        imageView = findViewById(R.id.imageView)
        imageContainer = findViewById(R.id.imageContainer)
        btnOpenDrawer = findViewById(R.id.btnOpenDrawer)
        btnDownload = findViewById(R.id.btnDownload)
        txtStatus = findViewById(R.id.txtStatus)
        rvBlurList = findViewById(R.id.rvBlurList)

        rvBlurList.layoutManager = LinearLayoutManager(this)

        btnOpenDrawer.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }

        btnDownload.setOnClickListener {
            saveFinalImage()
        }

        val uriString = intent.getStringExtra(MainActivity.EXTRA_IMAGE_URI)
        if (uriString == null) {
            Toast.makeText(this, "전달된 이미지가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        processReceivedImage(Uri.parse(uriString))
    }

    private fun processReceivedImage(uri: Uri) {
        try {
            originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri).copy(Bitmap.Config.ARGB_8888, true)
            }

            imageView.setImageBitmap(originalBitmap)
            txtStatus.text = "사람과 자동차 번호판을 감지하는 중입니다..."
            detectFacesAndPlates(originalBitmap!!)
        } catch (e: Exception) {
            Toast.makeText(this, "이미지 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectFacesAndPlates(bitmap: Bitmap) {
        val faceImage = InputImage.fromBitmap(bitmap, 0)
        val textImageNormal = InputImage.fromBitmap(bitmap, 0)
        val textImageContrast = InputImage.fromBitmap(preProcessBitmap(bitmap, 1.5f, 0f), 0)
        val textImageShadow = InputImage.fromBitmap(preProcessBitmap(bitmap, 1.1f, 40f), 0)
        val zoomedBitmap = createZoomedRoiBitmap(bitmap)
        val textImageZoomed = InputImage.fromBitmap(zoomedBitmap, 0)

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        )
        val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val tempItems = ArrayList<BlurItem>()
        val plateRegex = Regex("""(?:\d{2,3}\s*[가-힣]\s*\d{4}|[가-힣]{2}\s*\d{1,2}\s*[가-힣]\s*\d{4})""")
        var count = 1

        imageView.post {
            val mapper = ImageCoordinateMapper(imageView, bitmap)

            faceDetector.process(faceImage)
                .addOnSuccessListener { faces ->
                    faces.forEach { face ->
                        val mapped = mapper.map(face.boundingBox)
                        if (mapped.width() > 0 && mapped.height() > 0) {
                            val layer = addMaskAndNumberBadge(count, mapped.left, mapped.top, mapped.width(), mapped.height())
                            tempItems.add(BlurItem(count, "사람 얼굴 $count", layer))
                            count++
                        }
                    }

                    textRecognizer.process(textImageNormal)
                        .addOnSuccessListener { text1 ->
                            count = processVisionTextPass(text1, plateRegex, mapper, tempItems, count, "원본")
                            textRecognizer.process(textImageContrast)
                                .addOnSuccessListener { text2 ->
                                    count = processVisionTextPass(text2, plateRegex, mapper, tempItems, count, "대비 보정")
                                    textRecognizer.process(textImageShadow)
                                        .addOnSuccessListener { text3 ->
                                            count = processVisionTextPass(text3, plateRegex, mapper, tempItems, count, "밝기 보정")
                                            textRecognizer.process(textImageZoomed)
                                                .addOnSuccessListener { text4 ->
                                                    processZoomedVisionTextPass(text4, plateRegex, mapper, bitmap, tempItems, count)
                                                    finishDetection(tempItems)
                                                }
                                                .addOnFailureListener { finishDetection(tempItems) }
                                        }
                                        .addOnFailureListener { finishDetection(tempItems) }
                                }
                                .addOnFailureListener { finishDetection(tempItems) }
                        }
                        .addOnFailureListener { finishDetection(tempItems) }
                }
                .addOnFailureListener {
                    txtStatus.text = "ML Kit 감지 실패: ${it.message}"
                }
        }
    }

    private fun finishDetection(items: ArrayList<BlurItem>) {
        blurItemsList.clear()
        blurItemsList.addAll(items)
        rvBlurList.adapter = BlurListAdapter(blurItemsList)

        val hasItems = blurItemsList.isNotEmpty()
        btnOpenDrawer.isEnabled = hasItems
        btnDownload.isEnabled = hasItems
        txtStatus.text = if (hasItems) {
            "감지된 항목 ${blurItemsList.size}개를 마스킹했습니다."
        } else {
            "사람 또는 자동차 번호판이 감지되지 않았습니다."
        }

        if (!hasItems) {
            Toast.makeText(this, "사람 또는 자동차 번호판이 감지되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processVisionTextPass(
        visionText: com.google.mlkit.vision.text.Text,
        regex: Regex,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        startCount: Int,
        labelPrefix: String
    ): Int {
        var count = startCount
        for (block in visionText.textBlocks) {
            val combinedText = block.lines.joinToString(" ") { it.text.trim() }
            if (!regex.containsMatchIn(combinedText)) continue

            val bounds = block.boundingBox ?: continue
            val mapped = mapper.map(bounds)
            if (mapped.width() <= 0 || mapped.height() <= 0) continue
            if (isDuplicate(targetList, mapped.left, mapped.top)) continue

            val layer = addMaskAndNumberBadge(count, mapped.left, mapped.top, mapped.width(), mapped.height())
            targetList.add(BlurItem(count, "번호판 [$labelPrefix] $combinedText", layer))
            count++
        }
        return count
    }

    private fun processZoomedVisionTextPass(
        visionText: com.google.mlkit.vision.text.Text,
        regex: Regex,
        mapper: ImageCoordinateMapper,
        original: Bitmap,
        targetList: ArrayList<BlurItem>,
        startCount: Int
    ): Int {
        var count = startCount
        val startX = (original.width * 0.05f).toInt()
        val startY = (original.height * 0.30f).toInt()

        for (block in visionText.textBlocks) {
            val combinedText = block.lines.joinToString(" ") { it.text.trim() }
            if (!regex.containsMatchIn(combinedText)) continue

            val bounds = block.boundingBox ?: continue
            val originalRect = Rect(
                bounds.left / 3 + startX,
                bounds.top / 3 + startY,
                bounds.right / 3 + startX,
                bounds.bottom / 3 + startY
            )
            val mapped = mapper.map(originalRect)
            if (mapped.width() <= 0 || mapped.height() <= 0) continue
            if (isDuplicate(targetList, mapped.left, mapped.top)) continue

            val layer = addMaskAndNumberBadge(count, mapped.left, mapped.top, mapped.width(), mapped.height())
            targetList.add(BlurItem(count, "번호판 [확대] $combinedText", layer))
            count++
        }
        return count
    }

    private fun isDuplicate(targetList: List<BlurItem>, left: Int, top: Int): Boolean {
        return targetList.any {
            abs(it.viewRef.left - left) < 50 && abs(it.viewRef.top - top) < 50
        }
    }

    private fun addMaskAndNumberBadge(id: Int, left: Int, top: Int, width: Int, height: Int): TextView {
        val blackFrame = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(width, height).apply {
                setMargins(left, top, 0, 0)
            }
            setBackgroundColor(Color.BLACK)
        }
        imageContainer.addView(blackFrame)

        val badgeSize = 44
        val numberBadge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                setMargins(left + (width - badgeSize) / 2, top + (height - badgeSize) / 2, 0, 0)
            }
            setBackgroundColor(Color.parseColor("#FF5722"))
            text = id.toString()
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        imageContainer.addView(numberBadge)

        blackFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            numberBadge.visibility = blackFrame.visibility
        }

        return blackFrame
    }

    private fun saveFinalImage() {
        val bitmap = originalBitmap ?: return
        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val mapper = ImageCoordinateMapper(imageView, bitmap)

        blurItemsList.forEach { item ->
            if (item.viewRef.visibility == View.VISIBLE) {
                val params = item.viewRef.layoutParams as FrameLayout.LayoutParams
                val sourceRect = mapper.unmap(
                    Rect(
                        params.leftMargin,
                        params.topMargin,
                        params.leftMargin + item.viewRef.width,
                        params.topMargin + item.viewRef.height
                    )
                )
                canvas.drawRect(sourceRect, paint)
            }
        }

        val filename = "PrivacyBlur_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PrivacyBlur")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let { targetUri ->
            val outputStream: OutputStream? = contentResolver.openOutputStream(targetUri)
            outputStream?.use { stream ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                Toast.makeText(this, "갤러리에 저장했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun preProcessBitmap(src: Bitmap, contrast: Float, brightness: Float): Bitmap {
        val bmpProcessed = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmpProcessed)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val translate = (-128f * contrast + 128f) + brightness
        val custom = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(custom)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(grayscale)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmpProcessed
    }

    private fun createZoomedRoiBitmap(src: Bitmap): Bitmap {
        val startX = (src.width * 0.05f).toInt()
        val startY = (src.height * 0.30f).toInt()
        val cropWidth = min((src.width * 0.90f).toInt(), src.width - startX)
        val cropHeight = min((src.height * 0.65f).toInt(), src.height - startY)
        val cropped = Bitmap.createBitmap(src, startX, startY, cropWidth, cropHeight)
        return Bitmap.createScaledBitmap(cropped, cropWidth * 3, cropHeight * 3, true)
    }

    private class ImageCoordinateMapper(
        imageView: ImageView,
        bitmap: Bitmap
    ) {
        private val scale: Float
        private val dx: Float
        private val dy: Float

        init {
            val viewWidth = imageView.width.toFloat()
            val viewHeight = imageView.height.toFloat()
            val bitmapWidth = bitmap.width.toFloat()
            val bitmapHeight = bitmap.height.toFloat()
            scale = min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
            dx = (viewWidth - bitmapWidth * scale) / 2f
            dy = (viewHeight - bitmapHeight * scale) / 2f
        }

        fun map(rect: Rect): Rect {
            return Rect(
                (rect.left * scale + dx).toInt(),
                (rect.top * scale + dy).toInt(),
                (rect.right * scale + dx).toInt(),
                (rect.bottom * scale + dy).toInt()
            )
        }

        fun unmap(rect: Rect): Rect {
            return Rect(
                ((rect.left - dx) / scale).toInt().coerceAtLeast(0),
                ((rect.top - dy) / scale).toInt().coerceAtLeast(0),
                ((rect.right - dx) / scale).toInt().coerceAtLeast(0),
                ((rect.bottom - dy) / scale).toInt().coerceAtLeast(0)
            )
        }
    }
}
