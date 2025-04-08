package com.yourusername.arcoredetection.utils

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.ar.core.ArCoreApk
import timber.log.Timber

/**
 * Helper class to handle permissions required for the app
 */
class PermissionHelper(private val activity: FragmentActivity) {

    // Permission request launcher
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    // Callback for when permissions are granted or denied
    private var permissionCallback: ((Boolean) -> Unit)? = null

    // Required permissions
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.INTERNET
    )

    /**
     * Initialize the permission helper
     */
    fun initialize() {
        // Register permission launcher
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Timber.i("All required permissions granted")
                checkARCoreAvailability()
            } else {
                Timber.w("Some permissions denied: ${permissions.entries.filter { !it.value }.map { it.key }}")
                showPermissionExplanationDialog()
            }
            permissionCallback?.invoke(allGranted)
        }
    }

    /**
     * Check if all required permissions are granted
     */
    fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request all required permissions
     */
    fun requestPermissions(callback: (Boolean) -> Unit) {
        permissionCallback = callback

        if (hasRequiredPermissions()) {
            Timber.i("All permissions already granted")
            checkARCoreAvailability()
            callback(true)
            return
        }

        Timber.d("Requesting permissions: ${requiredPermissions.joinToString()}")
        permissionLauncher?.launch(requiredPermissions)
    }

    /**
     * Show dialog explaining why permissions are needed
     */
    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Permissions Required")
            .setMessage("This app requires camera and internet permissions to provide AR functionality and connect to the object detection server.")
            .setPositiveButton("Settings") { _, _ ->
                // Navigate to app settings
                openAppSettings()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                permissionCallback?.invoke(false)
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Open app settings to allow user to grant permissions
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * Check if ARCore is available on the device
     */
    fun checkARCoreAvailability() {
        val availability = ArCoreApk.getInstance().checkAvailability(activity)
        if (availability.isTransient) {
            // Re-check after a delay
            Timber.d("ARCore is installing, checking again in 200ms")
            activity.window.decorView.postDelayed({ checkARCoreAvailability() }, 200)
            return
        }

        if (!availability.isSupported) {
            showARCoreNotSupportedDialog()
            Timber.w("ARCore not supported on this device")
            return
        }

        Timber.i("ARCore is available and supported")
    }

    /**
     * Show dialog when ARCore is not supported
     */
    private fun showARCoreNotSupportedDialog() {
        AlertDialog.Builder(activity)
            .setTitle("ARCore Not Supported")
            .setMessage("ARCore is not supported on this device. Some features of the app may not work properly.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}