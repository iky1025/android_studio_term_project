package com.example.term_project

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import java.io.OutputStream
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

    val db: FirebaseFirestore = FirebaseFirestore.getInstance()

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
            Toast.makeText(this, "\uC804\uB2EC\uB41C \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        txtStatus.text = "\uBD84\uB958: ${toKoreanLabel(label)}, \uD655\uC2E0\uB3C4: ${String.format("%.2f", confidence * 100)}%\n\uD559\uC0DD\uC99D \uAC1C\uC778\uC815\uBCF4\uB97C \uAC10\uC9C0\uD558\uB294 \uC911\uC785\uB2C8\uB2E4..."
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
            originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri).copy(Bitmap.Config.ARGB_8888, true)
            }

            imageView.setImageBitmap(originalBitmap)
            detectStudentIdPrivacy(originalBitmap!!)
        } catch (e: Exception) {
            Toast.makeText(this, "\uC774\uBBF8\uC9C0 \uB85C\uB4DC \uC2E4\uD328: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectStudentIdPrivacy(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        )
        val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val tempItems = ArrayList<BlurItem>()
        var count = 1

        imageView.post {
            val mapper = ImageCoordinateMapper(imageView, bitmap)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    faces.forEach { face ->
                        val mapped = mapper.map(face.boundingBox)
                        if (mapped.width() > 0 && mapped.height() > 0) {
                            val layer = addMaskAndNumberBadge(count, mapped.left, mapped.top, mapped.width(), mapped.height())
                            tempItems.add(BlurItem(count, "\uC5BC\uAD74 \uC0AC\uC9C4", layer))
                            count++
                        }
                    }

                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            count = addStudentTextMasks(visionText, mapper, tempItems, count)
                            detectStudentIdQr(inputImage, mapper, tempItems, count)
                        }
                        .addOnFailureListener {
                            detectStudentIdQr(inputImage, mapper, tempItems, count)
                        }
                }
                .addOnFailureListener { error ->
                    txtStatus.text = "\uC5BC\uAD74 \uAC10\uC9C0 \uC2E4\uD328: ${error.message}"
                }
        }
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

        lines.forEach { line ->
            val directLabel = classifyDirectStudentText(line.text) ?: return@forEach
            count = addTextMask(directLabel, line, mapper, targetList, count)
        }

        count = addValueBesideLabel("name", lines, mapper, targetList, count)
        count = addValueBesideLabel("student_number", lines, mapper, targetList, count)
        count = addValueBesideLabel("birth_date", lines, mapper, targetList, count)
        count = addAffiliationValues(lines, mapper, targetList, count)
        return count
    }

    private fun addValueBesideLabel(
        labelType: String,
        lines: List<StudentOcrLine>,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        startCount: Int
    ): Int {
        val labelLine = lines.firstOrNull { isLabelLine(labelType, it.text) } ?: return startCount
        val valueLine = lines
            .filter { candidate ->
                candidate.bounds.left > labelLine.bounds.right &&
                    abs(candidate.centerY - labelLine.centerY) < 50 &&
                    isValueForLabel(labelType, candidate.text)
            }
            .minByOrNull { abs(it.centerY - labelLine.centerY) + abs(it.bounds.left - labelLine.bounds.right) }
            ?: return startCount

        val label = when (labelType) {
            "name" -> "\uC774\uB984"
            "student_number" -> "\uD559\uBC88"
            "birth_date" -> "\uC0DD\uB144\uC6D4\uC77C"
            else -> "\uAC1C\uC778\uC815\uBCF4"
        }
        return addTextMask(label, valueLine, mapper, targetList, startCount)
    }

    private fun addAffiliationValues(
        lines: List<StudentOcrLine>,
        mapper: ImageCoordinateMapper,
        targetList: ArrayList<BlurItem>,
        startCount: Int
    ): Int {
        val labelLine = lines.firstOrNull { isLabelLine("affiliation", it.text) } ?: return startCount
        var count = startCount
        val valueLines = lines
            .filter { candidate ->
                candidate.bounds.left > labelLine.bounds.right &&
                    candidate.centerY >= labelLine.centerY - 40 &&
                    candidate.centerY <= labelLine.centerY + 190 &&
                    isValueForLabel("affiliation", candidate.text)
            }
            .sortedBy { it.bounds.top }

        if (valueLines.isEmpty()) return count

        val combinedRect = valueLines
            .map { it.bounds }
            .reduce { acc, rect ->
                Rect(
                    min(acc.left, rect.left),
                    min(acc.top, rect.top),
                    maxOf(acc.right, rect.right),
                    maxOf(acc.bottom, rect.bottom)
                )
            }
        val combinedText = valueLines.joinToString(" ") { it.text }
        return addTextMask("\uC18C\uC18D", StudentOcrLine(combinedText, combinedRect), mapper, targetList, count)
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
            Regex("""(?:\uD559\uBC88|StudentID|ID|No\.?|NO\.?)[:\uFF1A]?\d{6,10}""").containsMatchIn(normalized) -> "\uD559\uBC88"
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
        val normalized = text.replace(" ", "")
        return when (labelType) {
            "name" -> Regex("""^[\uAC00-\uD7A3]{2,5}$""").matches(normalized) &&
                !isKnownFieldLabel(normalized) &&
                !isUniversityOrFooter(normalized)
            "student_number" -> Regex("""^\d{6,10}$""").matches(normalized)
            "birth_date" -> Regex("""^(?:\d{8}|\d{2,4}[./-]\d{1,2}[./-]\d{1,2})$""").matches(normalized)
            "affiliation" -> Regex("""^[\uAC00-\uD7A3A-Za-z]{2,20}$""").matches(normalized) &&
                !isKnownFieldLabel(normalized) &&
                !isUniversityOrFooter(normalized)
            else -> false
        }
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
            "\uD559\uC0DD\uC99D \uAC1C\uC778\uC815\uBCF4 ${blurItemsList.size}\uAC1C\uB97C \uAC10\uC9C0\uD588\uC2B5\uB2C8\uB2E4. \uBE14\uB7EC \uBAA9\uB85D\uC5D0\uC11C \uD56D\uBAA9\uBCC4\uB85C \uC120\uD0DD\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4."
        } else {
            "\uAC10\uC9C0\uB41C \uD559\uC0DD\uC99D \uAC1C\uC778\uC815\uBCF4\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4."
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

    // 파이어 베이스 전송 수정
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

                // 이미지 로컬 파일 저장이 끝난 후 Firestore로 데이터 누적 전송
                sendDataToFirebase()
            }
        }
    }

    private data class StudentOcrLine(
        val text: String,
        val bounds: Rect
    ) {
        val centerY: Int
            get() = (bounds.top + bounds.bottom) / 2
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

    private fun sendDataToFirebase() {
        // 전송할 데이터 맵 생성 (스위치가 꺼진 원본 데이터만 선별)
        val user = mutableMapOf<String, Any>(
            "name" to "none",
            "student_number" to "none",
            "birth_date" to "none",
            "affiliation" to "none"
        )

        blurItemsList.forEach { item ->
            if (!item.isChecked) {
                val rawText = item.label
                when {
                    rawText.startsWith("이름:") -> user["name"] = rawText.substringAfter("이름:").trim()
                    rawText.startsWith("학번:") -> user["student_number"] = rawText.substringAfter("학번:").trim()
                    rawText.startsWith("생년월일:") -> user["birth_date"] = rawText.substringAfter("생년월일:").trim()
                    rawText.startsWith("소속:") -> user["affiliation"] = rawText.substringAfter("소속:").trim()
                }
            }
        }

        // users 컬렉션 폴더에 데이터 누적
        val colRef: CollectionReference = db.collection("users")
        val docRef: Task<DocumentReference> = colRef.add(user)

        docRef.addOnSuccessListener { documentReference ->
            // 성공 시 로그
            Log.d("FirebaseDebug", "Success : ${documentReference.id}")
        }

        docRef.addOnFailureListener {
            Log.e("FirebaseDebug", "Failure to insert record", it)
        }
    }

    
}
