package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream

/**
 * 풍경 모드에서 카메라 프레임의 구도를 분류하는 추론기.
 *
 * ## 모델 사양
 * - 파일: final_model_mobile.pt (TorchScript JIT 포맷 — Module.load() 사용)
 *   ※ Lite Interpreter 포맷(.ptl)이 아니므로 pytorch_android_lite 가 아닌
 *      pytorch_android 라이브러리와 Module.load()를 사용해야 한다.
 * - 입력: 224×224 RGB Float32 텐서, ImageNet 정규화 적용
 * - 출력: 9개 클래스 로짓(logit) — softmax 후 확률로 변환
 *
 * ## 라벨 순서 (모델 출력 인덱스 기준)
 * 0: 삼등분 법칙, 1: 수직, 2: 수평, 3: 대각선, 4: 곡선,
 * 5: 삼각형, 6: 중심, 7: 대칭, 8: 패턴
 *
 * ## 사용법
 * ```kotlin
 * val classifier = LandscapeClassifier(context)
 * val result = classifier.classify(bitmap)   // 백그라운드 스레드에서 호출
 * classifier.close()                          // onDestroy 시 해제
 * ```
 */
class LandscapeClassifier(context: Context) {

    // ── 모델 라벨 ───────────────────────────────────────────────────────────────

    /** 모델 출력 인덱스와 1:1 매핑되는 구도 라벨 목록 */
    val labels = listOf(
        "삼등분 법칙", "수직", "수평", "대각선",
        "곡선", "삼각형", "중심", "대칭", "패턴"
    )

    // ── ImageNet 정규화 파라미터 ───────────────────────────────────────────────

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD  = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** 모델 입력 크기 */
    private val INPUT_SIZE = 224

    // ── PyTorch 모듈 ──────────────────────────────────────────────────────────

    /**
     * PyTorch TorchScript 모듈 (.pt 포맷).
     * assets의 .pt 파일은 내부 저장소로 복사한 뒤 로드해야 한다.
     * Lite Interpreter 포맷(.ptl)이 아니므로 Module.load()를 사용한다.
     */
    private val module: Module = Module.load(
        assetFilePath(context, "final_model_mobile.pt")
    )

    // ── 공개 API ────────────────────────────────────────────────────────────────

    /**
     * 비트맵을 받아 구도를 분류하고 상위 1개 결과를 반환한다.
     *
     * **백그라운드 스레드에서 호출해야 한다.** (추론은 수십 ms 소요)
     *
     * @param bitmap 카메라 프리뷰 비트맵 (임의 크기 — 내부에서 224×224로 리사이즈됨)
     * @return [LandscapeResult] 구도 라벨과 0~100 점수. 오류 시 null.
     */
    fun classify(bitmap: Bitmap): LandscapeResult? {
        return try {
            // ① 224×224 리사이즈
            // 모델 입력 크기가 224×224로 고정되어 있으므로 임의 크기의 프리뷰 비트맵을 줄인다.
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

            // ② 비트맵 → Float32 NCHW 텐서 + ImageNet 정규화
            // TensorImageUtils가 픽셀을 [0,1]로 스케일한 뒤 MEAN/STD로 정규화한다.
            // 학습 때 사용한 전처리와 동일해야 추론 정확도가 유지된다.
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resized, MEAN, STD)

            // ③ 추론 — forward()는 동기 블로킹 호출이다 (백그라운드 스레드에서 호출할 것)
            val outputTensor = module.forward(IValue.from(inputTensor)).toTensor()
            // dataAsFloatArray: shape [1, 9] 텐서를 flat float[9] 배열로 꺼낸다
            val logits = outputTensor.dataAsFloatArray

            // ④ Softmax: 로짓을 0~1 확률 분포로 변환
            // exp(logit_i) / Σ exp(logit_j) 수식을 그대로 구현한다.
            // 수치 안정성(overflow)보다 단순성을 우선한다 — 모바일 모델 로짓 범위가 크지 않다.
            val expValues = logits.map { Math.exp(it.toDouble()) }
            val sumExp    = expValues.sum()
            val probs     = expValues.map { (it / sumExp).toFloat() }

            // ⑤ 최고 확률 클래스 선택 및 0~100 점수 변환
            val topIdx   = probs.indices.maxByOrNull { probs[it] } ?: return null
            val topLabel = labels[topIdx]
            // topProb(0.0~1.0) × 100 → 정수 점수. coerceIn으로 부동소수점 오차 방어.
            val score    = (probs[topIdx] * 100).toInt().coerceIn(0, 100)

            LandscapeResult(label = topLabel, score = score)
        } catch (e: Exception) {
            null  // 추론 오류 시 조용히 무시 — 호출부(MainActivity)에서 null 처리
        }
    }

    /** 모듈 리소스를 해제한다. Activity.onDestroy()에서 호출할 것. */
    fun close() {
        module.destroy()
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * assets 폴더의 파일을 앱 내부 저장소로 복사하고 절대 경로를 반환한다.
     *
     * PyTorch Mobile은 파일 시스템 경로를 요구하므로 assets에서 직접 로드할 수 없다.
     * 이미 복사된 경우(파일이 존재하고 크기가 0보다 큼) 재복사를 건너뛴다.
     */
    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) return file.absolutePath

        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
            }
        }
        return file.absolutePath
    }
}

/**
 * 풍경 모드 구도 분류 결과.
 *
 * @param label 구도 이름 (예: "삼등분 법칙")
 * @param score 해당 구도의 확률을 0~100 점수로 환산한 값
 */
data class LandscapeResult(val label: String, val score: Int)
