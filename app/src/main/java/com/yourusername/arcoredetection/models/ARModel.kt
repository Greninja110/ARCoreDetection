package com.yourusername.arcoredetection.models

// Update imports to match SceneView 2.2.1 structure
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion

/**
 * Represents a 3D model that can be placed in AR
 */
data class ARModel(
    val id: String,
    val name: String,
    val resourceName: String,
    val scale: Float3 = Float3(1.0f, 1.0f, 1.0f)
)

/**
 * Represents an AR model that has been placed in the scene
 */
data class PlacedModel(
    val model: ARModel,
    val position: Float3,
    val rotation: Quaternion = Quaternion(),
    val scale: Float3 = Float3(1.0f, 1.0f, 1.0f),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * List of available AR models
 */
object ARModels {
    val availableModels = listOf(
        ARModel(
            id = "chair",
            name = "Chair",
            resourceName = "chair",
            scale = Float3(0.5f, 0.5f, 0.5f)
        ),
        ARModel(
            id = "table",
            name = "Table",
            resourceName = "table",
            scale = Float3(0.7f, 0.7f, 0.7f)
        ),
        ARModel(
            id = "bed",
            name = "Bed",
            resourceName = "bed",
            scale = Float3(0.9f, 0.9f, 0.9f)
        )
    )

    fun getModelById(id: String): ARModel? {
        return availableModels.find { it.id == id }
    }
}