package com.yourusername.arcoredetection.models

import android.graphics.RectF
import java.util.UUID

/**
 * Represents an object detected by the model
 */
data class DetectedObject(
    val id: String = UUID.randomUUID().toString(),
    val className: String,
    val confidence: Float,
    val boundingBox: RectF,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromJson(jsonObject: Map<String, Any>): DetectedObject {
            val name = jsonObject["name"] as String
            val confidence = (jsonObject["confidence"] as Double).toFloat()
            val bbox = jsonObject["bbox"] as List<Double>

            val boundingBox = RectF(
                bbox[0].toFloat(),
                bbox[1].toFloat(),
                bbox[2].toFloat(),
                bbox[3].toFloat()
            )

            return DetectedObject(
                className = name,
                confidence = confidence,
                boundingBox = boundingBox
            )
        }
    }
}

/**
 * A list of detected objects with timestamp
 */
data class DetectionResult(
    val objects: List<DetectedObject>,
    val timestamp: Long = System.currentTimeMillis()
)