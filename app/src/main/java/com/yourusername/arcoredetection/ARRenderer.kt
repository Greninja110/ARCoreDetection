package com.yourusername.arcoredetection

import android.content.Context
import android.view.MotionEvent
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import com.yourusername.arcoredetection.models.ARModel
import com.yourusername.arcoredetection.models.ARModels
import com.yourusername.arcoredetection.models.DetectedObject
import com.yourusername.arcoredetection.models.PlacedModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Handles AR rendering and object placement using ARCore and SceneView
 */
class ARRenderer(
    private val context: Context,
    private val arSceneView: ArSceneView
) {
    // Track if AR is active
    private var isARActive = false

    // Track placed models
    private val placedModels = mutableListOf<PlacedModel>()

    // Currently selected model
    private var selectedModel: ARModel? = null

    // Model nodes
    private val modelNodes = mutableMapOf<String, ArModelNode>()

    // Callbacks
    private var onModelPlacedListener: ((PlacedModel) -> Unit)? = null
    private var onARErrorListener: ((String) -> Unit)? = null

    /**
     * Initialize the AR renderer and preload models
     */
    fun initialize(coroutineScope: CoroutineScope) {
        Timber.d("Initializing AR Renderer")

        try {
            // Set up AR session callbacks
            arSceneView.onArSessionCreated = {
                Timber.d("AR Session created")
                isARActive = true
            }

            arSceneView.onArFrame = { arFrame ->
                // Handle AR frame updates if needed
            }

            // Set up plane tap detection
            arSceneView.onTapAr = { hitResult, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_UP) {
                    onTapPlane(hitResult)
                    true
                } else {
                    false
                }
            }

            // Preload all models
            coroutineScope.launch {
                preloadModels()
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AR renderer")
            onARErrorListener?.invoke("Failed to initialize AR: ${e.message}")
        }
    }

    /**
     * Preload all AR models to avoid delays when placing them
     */
    private suspend fun preloadModels() {
        withContext(Dispatchers.IO) {
            ARModels.availableModels.forEach { model ->
                try {
                    Timber.d("Preloading model: ${model.name}")

                    // Create a model node
                    val modelNode = ArModelNode(
                        placementMode = PlacementMode.INSTANT
                    ).apply {
                        // Load model from assets
                        loadModelAsync(
                            context = context,
                            glbFileLocation = "models/${model.resourceName}.glb",
                            autoAnimate = true,
                            scaleToUnits = 1.0f,
                            centerOrigin = Position(x = 0.0f, y = 0.0f, z = 0.0f)
                        ) { modelInstance ->
                            Timber.d("Model loaded successfully: ${model.name}")
                        }

                        // Set scale from model data
                        modelScale = Scale(
                            model.scale.x,
                            model.scale.y,
                            model.scale.z
                        )
                    }

                    // Store the model node
                    modelNodes[model.id] = modelNode
                    Timber.d("Successfully preloaded model: ${model.name}")

                } catch (e: Exception) {
                    Timber.e(e, "Failed to preload model: ${model.name}")
                }
            }
        }
    }

    /**
     * Select an AR model to place
     */
    fun selectModel(modelId: String): Boolean {
        val model = ARModels.getModelById(modelId)
        if (model != null) {
            Timber.d("Selected model: ${model.name}")
            selectedModel = model
            return true
        }
        Timber.w("Model not found with ID: $modelId")
        return false
    }

    /**
     * Clear the selected model
     */
    fun clearSelectedModel() {
        Timber.d("Cleared selected model")
        selectedModel = null
    }

    /**
     * Handle tap on an AR plane to place an object
     */
    private fun onTapPlane(hitResult: HitResult) {
        val model = selectedModel ?: run {
            Timber.w("No model selected")
            return
        }

        Timber.d("Tap on plane detected, placing model: ${model.name}")

        // Get the preloaded model node
        val preloadedNode = modelNodes[model.id]
        if (preloadedNode == null) {
            Timber.e("Preloaded model not found for: ${model.id}")
            onARErrorListener?.invoke("Model not loaded yet")
            return
        }

        try {
            // Create a new model node for placement
            val placedNode = ArModelNode(
                placementMode = PlacementMode.BEST_AVAILABLE
            ).apply {
                // Load the same model as the preloaded one
                loadModelAsync(
                    context = context,
                    glbFileLocation = "models/${model.resourceName}.glb",
                    autoAnimate = true,
                    scaleToUnits = 1.0f,
                    centerOrigin = Position(x = 0.0f, y = 0.0f, z = 0.0f)
                )

                // Use the same scale
                modelScale = Scale(
                    model.scale.x,
                    model.scale.y,
                    model.scale.z
                )

                // Anchor at the hit position
                anchor(hitResult.createAnchor())
            }

            // Add to scene
            arSceneView.addChild(placedNode)

            // Create a model reference object
            val placedModel = PlacedModel(
                model = model,
                position = Position(
                    placedNode.position.x,
                    placedNode.position.y,
                    placedNode.position.z
                ),
                rotation = Rotation(
                    placedNode.rotation.x,
                    placedNode.rotation.y,
                    placedNode.rotation.z
                )
            )

            // Add to list of placed models
            placedModels.add(placedModel)

            // Notify callback
            onModelPlacedListener?.invoke(placedModel)

            Timber.d("Model placed successfully: ${model.name} at ${placedNode.position}")
        } catch (e: Exception) {
            Timber.e(e, "Error placing model: ${e.message}")
            onARErrorListener?.invoke("Error placing model: ${e.message}")
        }
    }

    /**
     * Overlay detection results on AR view
     */
    fun overlayDetections(detectedObjects: List<DetectedObject>) {
        // Implementation depends on your specific requirements
        Timber.d("Overlaying ${detectedObjects.size} detected objects")

        // This could involve creating visual indicators for detected objects
        // in the AR view, but implementation depends on specific requirements
    }

    /**
     * Clear all placed models from the scene
     */
    fun clearModels() {
        Timber.d("Clearing all placed models")

        try {
            // Remove all model nodes from scene
            val nodesToRemove = arSceneView.children.filterIsInstance<ArModelNode>()
            nodesToRemove.forEach { node ->
                node.detachAnchor()
                arSceneView.removeChild(node)
            }

            placedModels.clear()
            Timber.d("All models cleared")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing models: ${e.message}")
        }
    }

    /**
     * Check if AR is currently active
     */
    fun isActive(): Boolean = isARActive

    /**
     * Set a callback for when a model is placed
     */
    fun setOnModelPlacedListener(listener: (PlacedModel) -> Unit) {
        this.onModelPlacedListener = listener
    }

    /**
     * Set a callback for AR errors
     */
    fun setOnARErrorListener(listener: (String) -> Unit) {
        this.onARErrorListener = listener
    }

    /**
     * Clean up resources when done
     */
    fun cleanup() {
        Timber.d("Cleaning up AR Renderer")
        clearModels()
        modelNodes.clear()
        isARActive = false
    }
}