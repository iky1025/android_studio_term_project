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
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.firebase.firestore.FirebaseFirestore
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

class StudentIdBlurActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var imageView: ImageView
    private lateinit var imageContainer: FrameLayout
    private lateinit var btnOpenDrawer: Button
    private lateinit var btnDownload: Button
    private lateinit var txtStatus: TextView
    private lateinit var rvBlurList: RecyclerView

    private var originalBitmap: Bitmap? = null
    private val blurItemsList = ArrayList<BlurItem>()
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_id_blur)

        drawerLayout = findViewById(R.id.studentDrawerLayout)
        imageView = findViewById(R.id.studentIdImageView)
        imageContainer = findViewById(R.id.studentImageContainer)
        btnOpenDrawer = findViewById(R.id.btnStudentOpenDrawer)
        btnDownload = findViewById(R.id.btnStudentDownload)
        txtStatus = findViewById(R.id.txtStudentIdInfo)
        rvBlurList = findViewById(R.id.rvStudentBlurList)

        rvBlurList.layoutManager = LinearLayoutManager(this)
        findViewById<Button>(R.id.btnStudentBack).setOnClickListener { finish() }
        btnOpenDrawer.setOnClickListener { drawerLayout.openDrawer(GravityCompat.END) }
        btnDownload.setOnClickListener { saveFinalImage() }

        val uriString = intent.getStringExtra(MainActivity.EXTRA_IMAGE_URI)
        val label = intent.getStringExtra(MainActivity.EXTRA_CLASSIFY_LABEL) ?: "student_id"
        val confidence = intent.getFloatExtra(MainActivity.EXTRA_CONFIDENCE, 0f)

        if (uriString == null) {
            Toast.makeText(this, "전달된 이미지가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        txtStatus.text = "분류: ${toKoreanLabel(label)}, 확신도: ${String.format("%.2f", confidence * 100)}%\n학생증 개인정보를 감지하는 중입니다..."
        processStudentIdImage(Uri.parse(uriString))
    }

    private fun toKoreanLabel(label: String): String {
        return when (label) {
            "student_id" -> "학생증"
            "non_student_id" -> "일반 사진"
            else -> label
        }
    }

    private fun processStudentIdImage(uri: Uri) {
        try {
            val loadedBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri).copy(Bitmap.Config.ARGB_8888, true)
            }

            val maxDim = 2048
            val originalWidth = loadedBitmap.width
            val originalHeight = loadedBitmap.height
            val optimizedBitmap =
                if (originalWidth > maxDim || originalHeight > maxDim) {
                    val scale = maxDim.toFloat() / maxOf(originalWidth, originalHeight)
                    val targetWidth = (originalWidth * scale).toInt()
                    val targetHeight = (originalHeight * scale).toInt()
                    Bitmap.createScaledBitmap(
                        loadedBitmap,
                        targetWidth,
                        targetHeight,
                        true
                    )
                } else {
                    loadedBitmap
                }

            val cleanBitmap = Bitmap.createBitmap(
                optimizedBitmap.width,
                optimizedBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            Canvas(cleanBitmap).apply {
                drawColor(Color.WHITE)
                drawBitmap(optimizedBitmap, 0f, 0f, null)
            }

            originalBitmap = cleanBitmap
            imageView.setImageBitmap(originalBitmap)
            detectStudentIdPrivacy(originalBitmap!!)
        } catch (e: Exception) {
            Toast.makeText(this, "이미지 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectStudentIdPrivacy(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        lifecycleScope.launch {
            val ocrBitmap = try {
                withContext(Dispatchers.Default) {
                    createOcrBitmap(bitmap)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                txtStatus.text = "OCR 이미지 전처리 실패: ${error.message}"
                return@launch
            }

            val ocrInputImage = InputImage.fromBitmap(ocrBitmap, 0)
            val faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
            )
            val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            val tempItems = ArrayList<BlurItem>()
            var count = 1

            imageView.post {
            val mapper = ImageCoordinateMapper(imageView, bitmap)
            val ocrMapper = ImageCoordinateMapper(imageView, ocrBitmap)

            fun runOcr() {
                textRecognizer.process(ocrInputImage)
                    .addOnSuccessListener { visionText ->
                        count = addStudentTextMasks(visionText, ocrMapper, tempItems, count)
                        detectStudentIdQr(inputImage, mapper, tempItems, count)
                    }
                    .addOnFailureListener { error ->
                        Log.w("StudentIdDetection", "OCR 감지 실패", error)
                        detectStudentIdQr(inputImage, mapper, tempItems, count)
                    }
                    .addOnCompleteListener {
                        textRecognizer.close()
                    }
            }

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    faces.forEach { face ->
                        val bounds = face.boundingBox
                        val hasBothEyes =
                            face.getLandmark(FaceLandmark.LEFT_EYE) != null &&
                                face.getLandmark(FaceLandmark.RIGHT_EYE) != null
                        val largeEnough =
                            bounds.width() >= bitmap.width * 0.08f &&
                                bounds.height() >= bitmap.height * 0.08f
                        if (!hasBothEyes || !largeEnough) return@forEach

                        val mapped = mapper.map(face.boundingBox)
                        if (mapped.width() > 0 && mapped.height() > 0) {
                            val layer = addMaskAndNumberBadge(count, mapped.left, mapped.top, mapped.width(), mapped.height())
                            tempItems.add(BlurItem(count, "얼굴 사진", layer))
                            count++
                        }
                    }
                    runOcr()
                }
                .addOnFailureListener { error ->
                    Log.w("StudentIdDetection", "얼굴 감지 실패, OCR은 계속 진행합니다.", error)
                    runOcr()
                }
                .addOnCompleteListener {
                    faceDetector.close()
                }
            }
        }
    }

    private fun createOcrBitmap(bitmap: Bitmap): Bitmap {
        val scale = if (bitmap.width < 1200 || bitmap.height < 1200) 2f else 1.5f
        val result = Bitmap.createBitmap(
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            Bitmap.Config.ARGB_8888
        )
        val contrast = 1.7f
        val offset = (-0.5f * contrast + 0.5f) * 255f
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, offset,
                        0f, contrast, 0f, 0f, offset,
                        0f, 0f, contrast, 0f, offset,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        Canvas(result).drawBitmap(
            bitmap,
            null,
            Rect(0, 0, result.width, result.height),
            paint
        )
        return result
    }

    private fun detectStudentIdQr(
        inputImage: InputImage,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        startCount: Int
    ) {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                var count = startCount
                barcodes.forEach { barcode ->
                    val bounds = barcode.boundingBox ?: return@forEach
                    val mapped = mapper.map(bounds)
                    val padding = 8
                    val left = (mapped.left - padding).coerceAtLeast(0)
                    val top = (mapped.top - padding).coerceAtLeast(0)
                    val right = (mapped.right + padding).coerceAtMost(imageView.width)
                    val bottom = (mapped.bottom + padding).coerceAtMost(imageView.height)

                    if (right <= left || bottom <= top || isDuplicate(targetList, left, top)) {
                        return@forEach
                    }

                    val layer = addMaskAndNumberBadge(
                        count,
                        left,
                        top,
                        right - left,
                        bottom - top
                    )
                    targetList.add(BlurItem(count, "QR 코드", layer))
                    count++
                }
            }
            .addOnCompleteListener {
                scanner.close()
                finishDetection(targetList)
            }
    }

    private fun addStudentTextMasks(
        visionText: com.google.mlkit.vision.text.Text,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        startCount: Int
    ): Int {
        var count = startCount
        val lines = visionText.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                StudentOcrLine(line.text.trim(), bounds)
            }
        val elements = visionText.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }
            .mapNotNull { element ->
                val bounds = element.boundingBox ?: return@mapNotNull null
                StudentOcrLine(element.text.trim(), bounds)
            }

        lines.forEach { line ->
            val directLabel = classifyDirectStudentText(line.text) ?: return@forEach
            if (directLabel == "학번" || directLabel == "생년월일") return@forEach
            count = addTextMask(directLabel, line, mapper, targetList, count)
        }

        count = addValueBesideLabel("name", lines, elements, mapper, targetList, count)
        count = addValueBesideLabel("student_number", lines, elements, mapper, targetList, count)
        count = addValueBesideLabel("birth_date", lines, elements, mapper, targetList, count)
        count = addAffiliationValues(lines, elements, mapper, targetList, count)
        return count
    }

    private fun addValueBesideLabel(
        labelType: String,
        lines: List<StudentOcrLine>,
        elements: List<StudentOcrLine>,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        startCount: Int
    ): Int {
        val allItems = (elements + lines).distinctBy {
            "${it.text}:${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}"
        }
        val labelItems = allItems.filter { isLabelLine(labelType, it.text) }
        if (labelItems.isEmpty()) return startCount

        val valueLine = labelItems
            .flatMap { labelLine ->
                val verticalTolerance = maxOf(labelLine.bounds.height() * 2, 60)
                allItems.mapNotNull { candidate ->
                    val value = extractValueForLabel(labelType, candidate.text) ?: return@mapNotNull null
                    if (candidate.bounds == labelLine.bounds) {
                        return@mapNotNull StudentValueCandidate(
                            StudentOcrLine(value, candidate.bounds),
                            0
                        )
                    }

                    val isOnRight = candidate.bounds.left >= labelLine.bounds.right - labelLine.bounds.width() / 3 &&
                        abs(candidate.centerY - labelLine.centerY) <= verticalTolerance
                    val isJustBelow = candidate.bounds.top >= labelLine.bounds.bottom &&
                        candidate.bounds.top - labelLine.bounds.bottom <= verticalTolerance &&
                        abs(candidate.bounds.left - labelLine.bounds.left) <= labelLine.bounds.width() * 3

                    if (!isOnRight && !isJustBelow) return@mapNotNull null

                    val directionPenalty = if (isOnRight) 0 else verticalTolerance
                    StudentValueCandidate(
                        StudentOcrLine(value, candidate.bounds),
                        abs(candidate.centerY - labelLine.centerY) +
                            abs(candidate.bounds.left - labelLine.bounds.right) +
                            directionPenalty
                    )
                }
            }
            .minByOrNull { it.score }
            ?.line
            ?: return startCount

        val label = when (labelType) {
            "name" -> "이름"
            "student_number" -> "학번"
            "birth_date" -> "생년월일"
            else -> "개인정보"
        }
        return addTextMask(label, valueLine, mapper, targetList, startCount)
    }

    private fun addAffiliationValues(
        lines: List<StudentOcrLine>,
        elements: List<StudentOcrLine>,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        startCount: Int
    ): Int {
        val allItems = (lines + elements).distinctBy {
            "${it.text}:${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}"
        }
        val labelLine = allItems.firstOrNull { isLabelLine("affiliation", it.text) }
            ?: return startCount
        var count = startCount
        val verticalRange = maxOf(labelLine.bounds.height() * 8, 240)
        val valueLines = allItems
            .filter { candidate ->
                candidate.bounds.left >= labelLine.bounds.right - labelLine.bounds.width() / 3 &&
                    candidate.centerY >= labelLine.centerY - labelLine.bounds.height() &&
                    candidate.centerY <= labelLine.centerY + verticalRange &&
                    isValueForLabel("affiliation", candidate.text)
            }
            .distinctBy { it.text to it.bounds.top }
            .toMutableList()

        extractInlineAffiliation(labelLine)?.let(valueLines::add)
        valueLines.sortBy { it.bounds.top }

        if (valueLines.isEmpty()) return count

        val detectedRect = valueLines
            .map { it.bounds }
            .reduce { acc, rect ->
                Rect(
                    min(acc.left, rect.left),
                    min(acc.top, rect.top),
                    maxOf(acc.right, rect.right),
                    maxOf(acc.bottom, rect.bottom)
                )
            }
        val firstValueLeft = valueLines.minOf { it.bounds.left }
        val combinedRect = Rect(
            firstValueLeft,
            min(labelLine.bounds.top, detectedRect.top),
            detectedRect.right,
            detectedRect.bottom
        )
        val combinedText = valueLines.joinToString(" ") { it.text }
        return addTextMask("소속", StudentOcrLine(combinedText, combinedRect), mapper, targetList, count)
    }

    private fun extractInlineAffiliation(line: StudentOcrLine): StudentOcrLine? {
        val match = Regex("""(?:소속|학과|학부|전공)\s*[:：]?\s*(.+)""").find(line.text)
            ?: return null
        val value = match.groupValues[1].trim()
        if (value.isBlank() || !isValueForLabel("affiliation", value)) return null

        val labelEndRatio = match.groups[1]?.range?.first
            ?.toFloat()
            ?.div(line.text.length.coerceAtLeast(1))
            ?.coerceIn(0f, 0.9f)
            ?: return null
        val valueLeft = line.bounds.left + (line.bounds.width() * labelEndRatio).toInt()
        if (valueLeft >= line.bounds.right) return null

        return StudentOcrLine(
            value,
            Rect(valueLeft, line.bounds.top, line.bounds.right, line.bounds.bottom)
        )
    }

    private fun addTextMask(
        label: String,
        line: StudentOcrLine,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        count: Int
    ): Int {
        val mapped = mapper.map(line.bounds)
        if (mapped.width() <= 0 || mapped.height() <= 0) return count
        if (isDuplicate(targetList, mapped.left, mapped.top)) return count

        val layer = addMaskAndNumberBadge(count, mapped.left, mapped.top, mapped.width(), mapped.height())
        targetList.add(BlurItem(count, "$label: ${line.text}", layer))
        return count + 1
    }

    private fun classifyDirectStudentText(text: String): String? {
        val normalized = text.replace(" ", "")
        return when {
            Regex("""(?:\uC7AC\uD559|\uD734\uD559|\uC878\uC5C5|\uC218\uB8CC|\uC81C\uC801)""").containsMatchIn(normalized) -> "\uC7AC\uD559\uC0C1\uD0DC"
            Regex("""(?:\uC131\uBA85|\uC774\uB984|NAME|Name|name)[:\uFF1A]?[\uAC00-\uD7A3]{2,5}""").containsMatchIn(normalized) -> "\uC774\uB984"
            Regex("""(?:\uD559\uBC88|StudentID|ID|No\.?|NO\.?)[:\uFF1A]?\d{9}""").containsMatchIn(normalized) -> "\uD559\uBC88"
            Regex("""(?:\uC0DD\uB144\uC6D4\uC77C|\uC0DD\uB144|Birth|BIRTH|DOB)[:\uFF1A]?(?:\d{8}|\d{2,4}[./-]\d{1,2}[./-]\d{1,2})""").containsMatchIn(normalized) -> "\uC0DD\uB144\uC6D4\uC77C"
            else -> null
        }
    }

    private fun isLabelLine(labelType: String, text: String): Boolean {
        val normalized = text.replace(" ", "")
        return when (labelType) {
            "name" -> normalized.contains("\uC774\uB984") || normalized.contains("\uC131\uBA85") || normalized.equals("name", ignoreCase = true)
            "student_number" -> normalized.contains("\uD559\uBC88") || normalized.contains("StudentID", ignoreCase = true)
            "birth_date" -> normalized.contains("\uC0DD\uB144\uC6D4\uC77C") || normalized.contains("\uC0DD\uB144") || normalized.contains("birth", ignoreCase = true)
            "affiliation" -> normalized.contains("\uC18C\uC18D") || normalized.contains("\uD559\uACFC") || normalized.contains("\uD559\uBD80") || normalized.contains("\uC804\uACF5")
            else -> false
        }
    }

    private fun isValueForLabel(labelType: String, text: String): Boolean {
        return extractValueForLabel(labelType, text) != null
    }

    private fun extractValueForLabel(labelType: String, text: String): String? {
        val normalized = text.replace(Regex("""[\s:：]"""), "")
        return when (labelType) {
            "name" -> Regex("""[\uAC00-\uD7A3]{2,5}""")
                .find(normalized.removePrefix("이름").removePrefix("성명"))
                ?.value
                ?.takeIf { !isKnownFieldLabel(it) && !isUniversityOrFooter(it) }
            "student_number" -> normalizeNumericOcr(
                normalized
                    .replace("학번", "")
                    .replace("STUDENTID", "", ignoreCase = true)
                    .replace(Regex("""(?i)(?:ID|NO\.?)"""), "")
            ).takeIf { it.length == 9 }
            "birth_date" -> normalizeNumericOcr(
                normalized
                    .replace("생년월일", "")
                    .replace("생년", "")
                    .replace(Regex("""(?i)(?:BIRTH|DOB)"""), "")
            ).takeIf { it.length == 8 }
            "affiliation" -> Regex("""[\uAC00-\uD7A3A-Za-z]{2,20}""")
                .find(normalized)
                ?.value
                ?.takeIf { !isKnownFieldLabel(it) && !isUniversityOrFooter(it) }
            else -> null
        }
    }

    private fun normalizeNumericOcr(text: String): String {
        return text.uppercase()
            .replace('O', '0')
            .replace('Q', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('|', '1')
            .replace('Z', '2')
            .replace('S', '5')
            .replace('G', '6')
            .replace('B', '8')
            .filter(Char::isDigit)
    }

    private fun isKnownFieldLabel(text: String): Boolean {
        return setOf(
            "\uD559\uBC88",
            "\uC774\uB984",
            "\uC0C1\uD0DC",
            "\uC0DD\uB144\uC6D4\uC77C",
            "\uC18C\uC18D"
        ).contains(text)
    }

    private fun isUniversityOrFooter(text: String): Boolean {
        return text.contains("\uBD80\uC0B0\uB300\uD559\uAD50") ||
            text.contains("\uCD1D\uD559\uC0DD\uD68C") ||
            text.contains("\uB300\uD559\uAD50\uCD1D\uD559\uC0DD\uD68C")
    }

    private fun finishDetection(items: ArrayList<BlurItem>) {
        blurItemsList.clear()
        blurItemsList.addAll(items)
        rvBlurList.adapter = BlurListAdapter(blurItemsList)

        val hasItems = blurItemsList.isNotEmpty()
        btnOpenDrawer.isEnabled = hasItems
        btnDownload.isEnabled = hasItems
        txtStatus.text = if (hasItems) {
            "학생증 개인정보 ${blurItemsList.size}개를 감지했습니다. 블러 목록에서 항목별로 선택할 수 있습니다."
        } else {
            "감지된 학생증 개인정보가 없습니다."
        }
    }

    private fun isDuplicate(targetList: List<BlurItem>, left: Int, top: Int): Boolean {
        return targetList.any {
            abs(it.viewRef.left - left) < 40 && abs(it.viewRef.top - top) < 40
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

        val filename = "StudentIdBlur_${System.currentTimeMillis()}.jpg"
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
                sendDataToFirebase()
            }
        }
    }

    private fun sendDataToFirebase() {
        val user = mutableMapOf<String, Any>(
            "name" to "none",
            "student_number" to "none",
            "birth_date" to "none",
            "affiliation" to "none"
        )

        blurItemsList.forEach { item ->
            val rawText = item.label
            when {
                rawText.startsWith("이름:") ->
                    user["name"] = rawText.substringAfter("이름:").trim()
                rawText.startsWith("학번:") ->
                    user["student_number"] = rawText.substringAfter("학번:").trim()
                rawText.startsWith("생년월일:") ->
                    user["birth_date"] = rawText.substringAfter("생년월일:").trim()
                rawText.startsWith("소속:") ->
                    user["affiliation"] = rawText.substringAfter("소속:").trim()
            }
        }

        db.collection("users")
            .add(user)
            .addOnSuccessListener { documentReference ->
                Log.d("FirebaseDebug", "Success: ${documentReference.id}")
                Toast.makeText(this, "Firebase에 정보를 저장했습니다.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { exception ->
                Log.e("FirebaseDebug", "Failure to insert record", exception)
                Toast.makeText(
                    this,
                    "Firebase 저장 실패: ${exception.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private data class StudentOcrLine(
        val text: String,
        val bounds: Rect
    ) {
        val centerY: Int
            get() = (bounds.top + bounds.bottom) / 2
    }

    private data class StudentValueCandidate(
        val line: StudentOcrLine,
        val score: Int
    )

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
