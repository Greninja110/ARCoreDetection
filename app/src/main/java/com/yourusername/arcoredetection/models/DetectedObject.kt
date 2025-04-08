package com.yourusername.arcoredetection.models

import android.graphics.RectF

data class DetectedObject(
    val id: String,
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
                id = java.util.UUID.randomUUID().toString(),
                className = name,
                confidence = confidence,
                boundingBox = boundingBox
            )
        }
    }
}

data class DetectionResult(
    val objects: List<DetectedObject>,
    val timestamp: Long = System.currentTimeMillis()
)