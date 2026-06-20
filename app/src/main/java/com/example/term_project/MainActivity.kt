package com.example.term_project

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.URLUtil
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var etImageUrl: EditText
    private lateinit var btnLoadUrlImage: Button
    private lateinit var downloadManager: DownloadManager

    private var selectedBitmap: Bitmap? = null
    private var selectedImageUri: Uri? = null
    private var downloadId = -1L
    private var isDownloadReceiverRegistered = false

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (completedId != downloadId) return

            btnLoadUrlImage.isEnabled = true

            downloadManager.query(
                DownloadManager.Query().setFilterById(completedId)
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    showDownloadError("다운로드 결과를 확인할 수 없습니다.")
                    return
                }

                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    showDownloadError("이미지 다운로드에 실패했습니다. URL을 확인해 주세요.")
                    return
                }

                val localUri = cursor.getString(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                )?.let(Uri::parse)

                if (localUri == null) {
                    showDownloadError("다운로드한 파일을 찾을 수 없습니다.")
                    return
                }

                try {
                    setSelectedImage(localUri)
                    txtResult.text = "이미지를 다운로드했습니다. 개인정보 감지 시작 버튼을 누르세요."
                    Toast.makeText(context, "이미지를 성공적으로 가져왔습니다.", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    showDownloadError("다운로드한 파일이 올바른 이미지가 아닙니다.")
                }
            }
        }
    }

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
        etImageUrl = findViewById(R.id.etImageUrl)
        btnLoadUrlImage = findViewById(R.id.btnLoadUrlImage)
        downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

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

        btnLoadUrlImage.setOnClickListener {
            downloadImage(etImageUrl.text.toString().trim())
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isDownloadReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                onDownloadComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
            isDownloadReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (isDownloadReceiverRegistered) {
            unregisterReceiver(onDownloadComplete)
            isDownloadReceiverRegistered = false
        }
        super.onStop()
    }

    private fun downloadImage(urlString: String) {
        val uri = Uri.parse(urlString)
        if (!URLUtil.isNetworkUrl(urlString) ||
            (uri.scheme != "http" && uri.scheme != "https")
        ) {
            Toast.makeText(this, "올바른 HTTP 또는 HTTPS 이미지 URL을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = "downloaded_input_${System.currentTimeMillis()}.jpg"
        val request = DownloadManager.Request(uri).apply {
            setTitle("Privacy Blur 이미지")
            setDescription("분석할 이미지를 다운로드하는 중입니다.")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                this@MainActivity,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        }

        try {
            btnLoadUrlImage.isEnabled = false
            txtResult.text = "네트워크에서 이미지를 다운로드하는 중입니다..."
            downloadId = downloadManager.enqueue(request)
        } catch (error: Exception) {
            btnLoadUrlImage.isEnabled = true
            showDownloadError("다운로드를 시작하지 못했습니다: ${error.message}")
        }
    }

    private fun showDownloadError(message: String) {
        txtResult.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setSelectedImage(uri: Uri) {
        selectedImageUri = uri
        selectedBitmap = uriToBitmap(uri)
        imagePreview.setImageBitmap(selectedBitmap)
        findViewById<TextView>(R.id.txtImagePlaceholder).visibility = View.GONE
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
                            val errorMessage =
                                "학생증, 사람 얼굴 또는 자동차 번호판이 감지되지 않았습니다. 다른 이미지를 선택해주세요."
                            txtResult.text = "$previousMessage\n\n$errorMessage"
                            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
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
