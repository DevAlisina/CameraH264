package com.camerastreamer.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.camerastreamer.app.ui.AutoFitTextureView
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Manages Camera2 capture and feeds both preview and hardware H.264 video encoder surfaces.
 */
class CameraCaptureManager(
    private val context: Context,
    private val textureView: AutoFitTextureView
) {
    companion object {
        private const val TAG = "CameraCaptureManager"
    }

    interface CameraStateListener {
        fun onCameraOpened(cameraSize: Size)
        fun onCameraClosed()
        fun onCameraError(error: String)
    }

    var stateListener: CameraStateListener? = null

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val cameraOpenCloseLock = Semaphore(1)

    private var lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    private var targetResolution: Size = Size(1280, 720)
    private var encoderSurface: Surface? = null

    var isCameraActive: Boolean = false
        private set

    /**
     * Starts the camera background handler thread.
     */
    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    /**
     * Stops the camera background handler thread.
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
            backgroundThread = null
            backgroundHandler = null
        } catch (_: InterruptedException) {
        }
    }

    /**
     * Sets the encoder surface to which camera frames will be delivered for H.264 compression.
     */
    fun setEncoderSurface(surface: Surface?) {
        this.encoderSurface = surface
    }

    /**
     * Sets target recording resolution (e.g. 1920x1080, 1280x720, 640x480).
     */
    fun setTargetResolution(width: Int, height: Int) {
        this.targetResolution = Size(width, height)
    }

    /**
     * Toggles between Front and Back camera.
     */
    fun toggleCamera() {
        val newFacing = if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        lensFacing = newFacing
        if (isCameraActive) {
            closeCamera()
            openCamera()
        }
    }

    fun isFacingBack(): Boolean = lensFacing == CameraCharacteristics.LENS_FACING_BACK

    /**
     * Opens the camera and starts streaming to preview and encoder surfaces.
     */
    @SuppressLint("MissingPermission")
    fun openCamera() {
        startBackgroundThread()

        val cameraId = getCameraIdForFacing(lensFacing) ?: run {
            stateListener?.onCameraError("No camera found for facing $lensFacing")
            return
        }

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                stateListener?.onCameraError("Time out waiting to lock camera opening.")
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val optimalSize = chooseOptimalSize(map, targetResolution.width, targetResolution.height)
            targetResolution = optimalSize

            // Update aspect ratio of preview view
            textureView.post {
                textureView.setAspectRatio(targetResolution.height, targetResolution.width)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    isCameraActive = true
                    stateListener?.onCameraOpened(targetResolution)
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraActive = false
                    stateListener?.onCameraClosed()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraActive = false
                    stateListener?.onCameraError("Camera open error: $error")
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Cannot access camera", e)
            stateListener?.onCameraError("Cannot access camera: ${e.message}")
        } catch (e: SecurityException) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Camera permission not granted", e)
            stateListener?.onCameraError("Camera permission not granted")
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Error opening camera", e)
            stateListener?.onCameraError("Error: ${e.message}")
        }
    }

    /**
     * Configures the CameraCaptureSession with the preview surface and optional encoder surface.
     */
    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return

        try {
            texture.setDefaultBufferSize(targetResolution.width, targetResolution.height)
            val previewSurface = Surface(texture)

            val surfaces = mutableListOf<Surface>()
            surfaces.add(previewSurface)

            val encSurface = encoderSurface
            if (encSurface != null && encSurface.isValid) {
                surfaces.add(encSurface)
            }

            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                if (encSurface != null && encSurface.isValid) {
                    addTarget(encSurface)
                }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                            Log.i(TAG, "Camera capture session configured successfully with ${surfaces.size} surfaces")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start camera repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera session configuration failed")
                        stateListener?.onCameraError("Failed to configure camera session")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera capture session", e)
        }
    }

    /**
     * Closes the active camera device and stops repeating session.
     */
    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            isCameraActive = false
            stateListener?.onCameraClosed()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }

    private fun getCameraIdForFacing(facing: Int): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun chooseOptimalSize(map: StreamConfigurationMap?, targetW: Int, targetH: Int): Size {
        if (map == null) return Size(targetW, targetH)
        val choices = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(targetW, targetH)

        // Find exact match if possible
        for (size in choices) {
            if (size.width == targetW && size.height == targetH) {
                return size
            }
        }

        // Otherwise find closest size with matching aspect ratio
        val targetAspect = targetW.toDouble() / targetH.toDouble()
        val suitableSizes = choices.filter {
            val aspect = it.width.toDouble() / it.height.toDouble()
            Math.abs(aspect - targetAspect) < 0.05
        }

        if (suitableSizes.isNotEmpty()) {
            return Collections.min(suitableSizes, Comparator { a, b ->
                val diffA = Math.abs(a.width - targetW)
                val diffB = Math.abs(b.width - targetW)
                diffA.compareTo(diffB)
            })
        }

        // Fallback to first available
        return choices[0]
    }
}
