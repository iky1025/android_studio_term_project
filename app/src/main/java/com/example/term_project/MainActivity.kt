package com.example.term_project

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var btnSelectImage: Button
    private lateinit var btnCaptureImage: Button
    private lateinit var btnClassify: Button
    private lateinit var txtResult: TextView
    private lateinit var classifier: StudentIdClassifier

    private var selectedBitmap: Bitmap? = null
    private var selectedImageUri: Uri? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            Toast.makeText(this, "이미지를 선택하지 않았습니다.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        setSelectedImage(uri)
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }

        val uriString = result.data?.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString.isNullOrBlank()) {
            Toast.makeText(this, "촬영한 이미지를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        setSelectedImage(Uri.parse(uriString))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imagePreview = findViewById(R.id.imagePreview)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        btnCaptureImage = findViewById(R.id.btnCaptureImage)
        btnClassify = findViewById(R.id.btnClassify)
        txtResult = findViewById(R.id.txtResult)

        classifier = StudentIdClassifier(this)

        btnSelectImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        btnCaptureImage.setOnClickListener {
            cameraLauncher.launch(Intent(this, CameraActivity::class.java))
        }

        btnClassify.setOnClickListener {
            classifySelectedImage()
        }
    }

    private fun setSelectedImage(uri: Uri) {
        selectedImageUri = uri
        selectedBitmap = uriToBitmap(uri)
        imagePreview.setImageBitmap(selectedBitmap)
        txtResult.text = "이미지가 준비되었습니다. 분류 시작 버튼을 누르세요."
    }

    private fun classifySelectedImage() {
        val bitmap = selectedBitmap
        val uri = selectedImageUri

        if (bitmap == null || uri == null) {
            Toast.makeText(this, "먼저 이미지를 선택하거나 촬영하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        btnClassify.isEnabled = false
        txtResult.text = "학생증 여부를 판단하는 중입니다..."

        val result = classifier.classify(bitmap)
        val threshold = 0.80f

        val summary = buildString {
            append("TFLite 분류 결과\n")
            append("예측: ${toKoreanLabel(result.label)}\n")
            append("확신도: ${String.format("%.2f", result.confidence * 100)}%\n\n")
            result.allProbabilities.forEach { (label, probability) ->
                append("${toKoreanLabel(label)}: ${String.format("%.2f", probability * 100)}%\n")
            }
        }

        txtResult.text = summary

        when {
            result.label == "student_id" && result.confidence >= threshold -> {
                openStudentIdActivity(uri, result.label, result.confidence)
            }

            result.label == "non_student_id" && result.confidence >= threshold -> {
                txtResult.text = "$summary\n\n사람 또는 자동차 번호판을 감지하는 중입니다..."
                detectGeneralPrivacyThenOpen(uri, bitmap, summary)
            }

            else -> {
                btnClassify.isEnabled = true
                txtResult.text = "$summary\n\n판단이 불확실합니다. 다른 이미지를 사용해 주세요."
                Toast.makeText(this, "판단이 불확실합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openStudentIdActivity(uri: Uri, label: String, confidence: Float) {
        btnClassify.isEnabled = true
        startActivity(
            Intent(this, StudentIdBlurActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_URI, uri.toString())
                putExtra(EXTRA_CLASSIFY_LABEL, label)
                putExtra(EXTRA_CONFIDENCE, confidence)
            }
        )
    }

    private fun detectGeneralPrivacyThenOpen(uri: Uri, bitmap: Bitmap, previousMessage: String) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
        val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val plateRegex = Regex("""(?:\d{2,3}\s*[\uAC00-\uD7A3]\s*\d{4}|[\uAC00-\uD7A3]{2}\s*\d{1,2}\s*[\uAC00-\uD7A3]\s*\d{4})""")

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    btnClassify.isEnabled = true
                    openGeneralBlurActivity(uri)
                    return@addOnSuccessListener
                }

                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        btnClassify.isEnabled = true
                        if (plateRegex.containsMatchIn(visionText.text.replace("\n", " "))) {
                            openGeneralBlurActivity(uri)
                        } else {
                            txtResult.text = "$previousMessage\n\n사람 또는 자동차 번호판이 감지되지 않았습니다."
                            Toast.makeText(this, "사람 또는 자동차 번호판이 감지되지 않았습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener { error ->
                        btnClassify.isEnabled = true
                        txtResult.text = "$previousMessage\n\nML Kit 문자 감지 실패: ${error.message}"
                    }
            }
            .addOnFailureListener { error ->
                btnClassify.isEnabled = true
                txtResult.text = "$previousMessage\n\nML Kit 얼굴 감지 실패: ${error.message}"
            }
    }

    private fun openGeneralBlurActivity(uri: Uri) {
        startActivity(
            Intent(this, GeneralBlurActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_URI, uri.toString())
                putExtra(EXTRA_CLASSIFY_LABEL, "non_student_id")
            }
        )
    }

    private fun toKoreanLabel(label: String): String {
        return when (label) {
            "student_id" -> "학생증"
            "non_student_id" -> "일반 사진"
            else -> label
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri).copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier.close()
    }

    companion object {
        const val EXTRA_IMAGE_URI = "IMAGE_URI"
        const val EXTRA_CLASSIFY_LABEL = "CLASSIFY_LABEL"
        const val EXTRA_CONFIDENCE = "CONFIDENCE"
    }
}
