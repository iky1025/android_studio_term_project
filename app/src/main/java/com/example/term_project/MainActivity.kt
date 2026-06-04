package com.example.term_project

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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class MainActivity : ComponentActivity() {

    private lateinit var imagePreview: ImageView
    private lateinit var btnSelectImage: Button
    private lateinit var btnClassify: Button
    private lateinit var txtResult: TextView

    private lateinit var classifier: StudentIdClassifier

    private var selectedBitmap: Bitmap? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->

        if (uri == null) {
            Toast.makeText(this, "이미지를 선택하지 않았습니다.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        selectedBitmap = uriToBitmap(uri)

        imagePreview.setImageBitmap(selectedBitmap)
        txtResult.text = "이미지가 선택되었습니다. 학생증 여부 판단 버튼을 누르세요."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        imagePreview = findViewById(R.id.imagePreview)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        btnClassify = findViewById(R.id.btnClassify)
        txtResult = findViewById(R.id.txtResult)

        classifier = StudentIdClassifier(this)

        btnSelectImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        btnClassify.setOnClickListener {
            classifySelectedImage()
        }
    }

    private fun classifySelectedImage() {
        val bitmap = selectedBitmap

        if (bitmap == null) {
            Toast.makeText(this, "먼저 이미지를 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val result = classifier.classify(bitmap)

        val threshold = 0.80f

        val message = buildString {
            append("TFLite 학생증 분류 결과\n\n")
            append("예측 결과: ${result.label}\n")
            append("확신도: ${String.format("%.2f", result.confidence * 100)}%\n\n")
            append("전체 확률:\n")

            for ((label, prob) in result.allProbabilities) {
                append("$label : ${String.format("%.2f", prob * 100)}%\n")
            }
        }

        txtResult.text = message

        when {
            result.label == "student_id" && result.confidence >= threshold -> {
                Toast.makeText(this, "학생증으로 판단됨", Toast.LENGTH_SHORT).show()

                txtResult.text = buildString {
                    append(message)
                    append("\n\n")
                    append("다음 단계: 학생증 OCR 처리 예정")
                }

                // 다음 단계에서 여기에 학생증 OCR 넣으면 됨
                // runStudentIdTextRecognition(bitmap)
            }

            result.label == "non_student_id" && result.confidence >= threshold -> {
                Toast.makeText(this, "학생증이 아닌 일반 이미지로 판단됨", Toast.LENGTH_SHORT).show()

                // 학생증이 아니면 ML Kit으로 얼굴 감지 실행
                detectFacesWithMlKit(bitmap, message)
            }

            else -> {
                Toast.makeText(this, "판단이 애매합니다. 다시 선택해주세요.", Toast.LENGTH_SHORT).show()

                txtResult.text = buildString {
                    append(message)
                    append("\n\n")
                    append("판단이 애매합니다.\n")
                    append("다른 이미지를 다시 선택해주세요.")
                }
            }
        }
    }

    private fun detectFacesWithMlKit(bitmap: Bitmap, previousMessage: String) {
        val image = InputImage.fromBitmap(bitmap, 0)

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()

        val detector = FaceDetection.getClient(options)

        detector.process(image)
            .addOnSuccessListener { faces ->

                if (faces.isNotEmpty()) {
                    val faceResultText = buildString {
                        append(previousMessage)
                        append("\n\n")
                        append("ML Kit 얼굴 감지 결과\n\n")
                        append("사람 있음으로 판단\n")
                        append("감지된 얼굴 수: ${faces.size}개\n\n")

                        faces.forEachIndexed { index, face ->
                            val box = face.boundingBox

                            append("${index + 1}번 얼굴 좌표\n")
                            append("left: ${box.left}\n")
                            append("top: ${box.top}\n")
                            append("right: ${box.right}\n")
                            append("bottom: ${box.bottom}\n\n")
                        }
                    }

                    txtResult.text = faceResultText

                    Toast.makeText(
                        this,
                        "사람 얼굴이 감지되었습니다.",
                        Toast.LENGTH_SHORT
                    ).show()

                    // 나중에 여기서 2번 액티비티로 얼굴 좌표 전달 가능
                    // face.boundingBox 값을 넘기면 됨

                } else {
                    val noFaceResultText = buildString {
                        append(previousMessage)
                        append("\n\n")
                        append("ML Kit 얼굴 감지 결과\n\n")
                        append("얼굴이 감지되지 않았습니다.\n")
                        append("사람 없음 또는 얼굴 미검출로 판단합니다.")
                    }

                    txtResult.text = noFaceResultText

                    Toast.makeText(
                        this,
                        "얼굴이 감지되지 않았습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .addOnFailureListener { e ->

                txtResult.text = buildString {
                    append(previousMessage)
                    append("\n")
                    append("ML Kit 얼굴 감지 실패\n")
                    append(e.message)
                }

                Toast.makeText(
                    this,
                    "ML Kit 얼굴 감지 실패",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun uriToBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)

            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        classifier.close()
    }
}