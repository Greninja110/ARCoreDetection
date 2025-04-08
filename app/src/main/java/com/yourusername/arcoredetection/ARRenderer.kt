package com.yourusername.arcoredetection

import android.content.Context
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.yourusername.arcoredetection.models.ARModel
import com.yourusername.arcoredetection.models.ARModels
import com.yourusername.arcoredetection.models.DetectedObject
import com.yourusername.arcoredetection.models.PlacedModel
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArNode
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.CompletableFuture

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

    // Store model nodes by id
    private val modelNodes = mutableMapOf<String, CompletableFuture<ModelNode>>()

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
            arSceneView.onSessionCreated = {
                Timber.d("AR Session created")
                isARActive = true
            }

            // Set up tap listener
            arSceneView.onTapArPlane = { hitResult, _, _ ->
                onTapPlane(hitResult)
                true
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
                    val modelFuture = ModelNode.loadModel(
                        context = context,
                        glbFileLocation = "models/${model.resourceName}.glb",
                        autoAnimate = true
                    )

                    modelNodes[model.id] = modelFuture
                    Timber.d("Model loading initiated: ${model.name}")
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

        try {
            // Get the model future from our preloaded models
            val modelFuture = modelNodes[model.id]
            if (modelFuture == null) {
                Timber.e("Model not found in preloaded models: ${model.id}")
                onARErrorListener?.invoke("Model not ready yet")
                return
            }

            // Create anchor from hit result
            val anchor = hitResult.createAnchor()

            // Create a node for the anchor
            val anchorNode = ArNode(anchor)
            arSceneView.addChild(anchorNode)

            // When the model is loaded, add it as a child of the anchor node
            modelFuture.thenAccept { modelNode ->
                // Clone the model node
                val placedNode = ModelNode()
                placedNode.apply {
                    loadModelAsync(
                        context = context,
                        glbFileLocation = "models/${model.resourceName}.glb",
                        autoAnimate = true
                    )

                    // Apply scale
                    setScale(
                        x = model.scale.x,
                        y = model.scale.y,
                        z = model.scale.z
                    )
                }

                // Add to anchor node
                anchorNode.addChild(placedNode)

                // Create placed model object
                val placedModel = PlacedModel(
                    model = model,
                    position = Float3(
                        placedNode.position.x,
                        placedNode.position.y,
                        placedNode.position.z
                    ),
                    rotation = Quaternion()
                )

                // Add to placed models list
                placedModels.add(placedModel)

                // Notify listener
                onModelPlacedListener?.invoke(placedModel)

                Timber.d("Model placed successfully: ${model.name}")
            }.exceptionally { throwable ->
                Timber.e(throwable, "Error loading model: ${model.name}")
                onARErrorListener?.invoke("Error loading model: ${throwable.message}")
                null
            }

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
    }

    /**
     * Clear all placed models from the scene
     */
    fun clearModels() {
        Timber.d("Clearing all placed models")

        try {
            // Get all AR nodes
            val nodesToRemove = arSceneView.children.filterIsInstance<ArNode>()

            // Remove each node
            nodesToRemove.forEach { node ->
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