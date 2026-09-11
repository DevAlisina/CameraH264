package com.camerastreamer.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.camerastreamer.app.camera.CameraCaptureManager
import com.camerastreamer.app.encoder.H264Encoder
import com.camerastreamer.app.network.CloudflareTunnelManager
import com.camerastreamer.app.network.H264ClientSender
import com.camerastreamer.app.network.H264Server
import com.camerastreamer.app.network.NetworkUtils
import com.camerastreamer.app.ui.AutoFitTextureView
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var textureView: AutoFitTextureView
    private lateinit var permissionContainer: LinearLayout
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvCommandInstructions: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rbServerMode: RadioButton
    private lateinit var rbClientMode: RadioButton
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerBitrate: Spinner
    private lateinit var btnToggleStream: MaterialButton

    // Cloudflare Tunnel UI Elements
    private lateinit var btnCloudflareTunnel: MaterialButton
    private lateinit var cardCloudflare: View
    private lateinit var tvCloudflareStatus: TextView
    private lateinit var tvCloudflareUrl: TextView
    private lateinit var btnCopyCloudflareUrl: MaterialButton
    private lateinit var btnShareCloudflareUrl: MaterialButton
    private lateinit var tvCloudflareInstructions: TextView

    private lateinit var cameraManager: CameraCaptureManager
    private var encoder: H264Encoder? = null
    private var server: H264Server? = null
    private var clientSender: H264ClientSender? = null
    private var cloudflareManager: CloudflareTunnelManager? = null

    private var isStreaming = false
    private var localIp = "127.0.0.1"

    // Android 14 Runtime Permission Handler
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG, "Camera permission granted")
                permissionContainer.visibility = View.GONE
                checkAndStartCamera()
            } else {
                Log.w(TAG, "Camera permission denied")
                permissionContainer.visibility = View.VISIBLE
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    btnGrantPermission.text = getString(R.string.settings)
                    btnGrantPermission.setOnClickListener {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                } else {
                    btnGrantPermission.text = getString(R.string.grant_permission)
                    btnGrantPermission.setOnClickListener {
                        requestCameraPermission()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        initViews()
        setupSpinners()
        setupListeners()
        updateNetworkInfo()

        cameraManager = CameraCaptureManager(this, textureView)
        cameraManager.stateListener = object : CameraCaptureManager.CameraStateListener {
            override fun onCameraOpened(cameraSize: Size) {
                runOnUiThread {
                    Log.i(TAG, "Camera opened: ${cameraSize.width}x${cameraSize.height}")
                }
            }

            override fun onCameraClosed() {
                runOnUiThread {
                    Log.i(TAG, "Camera closed")
                }
            }

            override fun onCameraError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                checkAndStartCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun initViews() {
        textureView = findViewById(R.id.textureView)
        permissionContainer = findViewById(R.id.permissionContainer)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        tvDeviceIp = findViewById(R.id.tvDeviceIp)
        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)
        tvCommandInstructions = findViewById(R.id.tvCommandInstructions)
        rgMode = findViewById(R.id.rgMode)
        rbServerMode = findViewById(R.id.rbServerMode)
        rbClientMode = findViewById(R.id.rbClientMode)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        spinnerResolution = findViewById(R.id.spinnerResolution)
        spinnerBitrate = findViewById(R.id.spinnerBitrate)
        btnToggleStream = findViewById(R.id.btnToggleStream)

        // Cloudflare Tunnel elements
        btnCloudflareTunnel = findViewById(R.id.btnCloudflareTunnel)
        cardCloudflare = findViewById(R.id.cardCloudflare)
        tvCloudflareStatus = findViewById(R.id.tvCloudflareStatus)
        tvCloudflareUrl = findViewById(R.id.tvCloudflareUrl)
        btnCopyCloudflareUrl = findViewById(R.id.btnCopyCloudflareUrl)
        btnShareCloudflareUrl = findViewById(R.id.btnShareCloudflareUrl)
        tvCloudflareInstructions = findViewById(R.id.tvCloudflareInstructions)
    }

    private fun setupSpinners() {
        val resolutions = listOf("1280x720 (720p)", "1920x1080 (1080p)", "640x480 (480p)")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerResolution.adapter = resAdapter
        spinnerResolution.setSelection(0)

        val bitrates = listOf("2 Mbps", "4 Mbps", "1 Mbps", "8 Mbps")
        val bitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitrates).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBitrate.adapter = bitAdapter
        spinnerBitrate.setSelection(0)
    }

    private fun setupListeners() {
        btnGrantPermission.setOnClickListener {
            requestCameraPermission()
        }

        btnSwitchCamera.setOnClickListener {
            cameraManager.toggleCamera()
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbClientMode) {
                etHost.visibility = View.VISIBLE
                btnCloudflareTunnel.visibility = View.GONE
                stopCloudflareTunnel()
                updateInstructionsForClientMode()
            } else {
                etHost.visibility = View.GONE
                btnCloudflareTunnel.visibility = View.VISIBLE
                updateInstructionsForServerMode()
            }
        }

        btnToggleStream.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        btnCloudflareTunnel.setOnClickListener {
            toggleCloudflareTunnel()
        }

        btnCopyCloudflareUrl.setOnClickListener {
            val url = tvCloudflareUrl.text.toString()
            if (url.startsWith("http")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Cloudflare Tunnel URL", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "URL copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        btnShareCloudflareUrl.setOnClickListener {
            val url = tvCloudflareUrl.text.toString()
            if (url.startsWith("http")) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, "Watch live camera stream: $url/live.h264")
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, "Share Stream URL"))
            }
        }
    }

    private fun updateNetworkInfo() {
        localIp = NetworkUtils.getLocalIpAddress()
        tvDeviceIp.text = "Device IP: $localIp"
        if (rbServerMode.isChecked) {
            updateInstructionsForServerMode()
        } else {
            updateInstructionsForClientMode()
        }
    }

    private fun updateInstructionsForServerMode() {
        val port = etPort.text.toString().trim().ifEmpty { "8080" }
        val cmd = "Stream Command:\nffplay -fflags nobuffer -flags low_delay -f h264 tcp://$localIp:$port"
        tvCommandInstructions.text = cmd
    }

    private fun updateInstructionsForClientMode() {
        val port = etPort.text.toString().trim().ifEmpty { "8080" }
        val cmd = "Receiver Command (PC):\nnc -l -p $port | ffplay -fflags nobuffer -f h264 -"
        tvCommandInstructions.text = cmd
    }

    private fun checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            permissionContainer.visibility = View.GONE
            if (textureView.isAvailable && !cameraManager.isCameraActive) {
                val (width, height) = getSelectedResolution()
                cameraManager.setTargetResolution(width, height)
                cameraManager.openCamera()
            }
        } else {
            permissionContainer.visibility = View.VISIBLE
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun getSelectedResolution(): Pair<Int, Int> {
        return when (spinnerResolution.selectedItemPosition) {
            1 -> Pair(1920, 1080)
            2 -> Pair(640, 480)
            else -> Pair(1280, 720)
        }
    }

    private fun getSelectedBitrate(): Int {
        return when (spinnerBitrate.selectedItemPosition) {
            1 -> 4_000_000
            2 -> 1_000_000
            3 -> 8_000_000
            else -> 2_000_000
        }
    }

    private fun startStreaming() {
        val portStr = etPort.text.toString().trim()
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, "Please enter a valid port between 1024 and 65535", Toast.LENGTH_SHORT).show()
            return
        }

        val isServerMode = rbServerMode.isChecked
        val host = etHost.text.toString().trim()
        if (!isServerMode && host.isEmpty()) {
            Toast.makeText(this, "Please enter a target host IP", Toast.LENGTH_SHORT).show()
            return
        }

        val (resWidth, resHeight) = getSelectedResolution()
        val bitrate = getSelectedBitrate()

        try {
            // 1. Initialize H.264 hardware encoder
            val enc = H264Encoder(
                width = resWidth,
                height = resHeight,
                bitrate = bitrate,
                fps = 30,
                iFrameInterval = 1
            )
            enc.start()
            this.encoder = enc

            // 2. Attach encoder input surface to Camera2 session
            cameraManager.setEncoderSurface(enc.inputSurface)
            if (cameraManager.isCameraActive) {
                cameraManager.closeCamera()
            }
            cameraManager.setTargetResolution(resWidth, resHeight)
            cameraManager.openCamera()

            if (isServerMode) {
                startServerMode(port, enc)
            } else {
                startClientMode(host, port, enc)
            }

            isStreaming = true
            updateUiForStreaming(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            Toast.makeText(this, "Failed to start streaming: ${e.message}", Toast.LENGTH_LONG).show()
            stopStreaming()
        }
    }

    private fun startServerMode(port: Int, enc: H264Encoder) {
        val srv = H264Server(port) {
            // Callback when a client connects: request an immediate I-Frame
            enc.requestKeyFrame()
        }

        srv.listener = object : H264Server.ServerListener {
            override fun onServerStarted(port: Int) {
                runOnUiThread {
                    tvStatus.text = "Status: Listening on port $port"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_streaming))
                }
            }

            override fun onClientCountChanged(count: Int) {
                runOnUiThread {
                    if (count > 0) {
                        tvStatus.text = "Status: Streaming to $count client(s)"
                    } else {
                        tvStatus.text = "Status: Listening on port $port (0 clients)"
                    }
                }
            }

            override fun onStatsUpdated(fps: Int, bitrateBps: Long, totalBytes: Long) {
                runOnUiThread {
                    val count = srv.getClientCount()
                    val rateStr = NetworkUtils.formatBitrate(bitrateBps)
                    val totalStr = NetworkUtils.formatBytes(totalBytes)
                    tvStats.text = String.format(
                        Locale.US,
                        "Clients: %d | FPS: %d | Rate: %s | Sent: %s",
                        count, fps, rateStr, totalStr
                    )
                }
            }

            override fun onServerError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    tvStatus.text = "Status: Server Error"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                }
            }

            override fun onServerStopped() {
                runOnUiThread {
                    tvStatus.text = getString(R.string.status_idle)
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_idle))
                }
            }
        }

        enc.frameListener = object : H264Encoder.FrameListener {
            override fun onH264Frame(frameData: ByteArray, isKeyFrame: Boolean) {
                srv.sendFrame(frameData)
            }

            override fun onSpsPps(spsPpsData: ByteArray) {
                srv.setSpsPps(spsPpsData)
            }
        }

        srv.start()
        this.server = srv
    }

    private fun startClientMode(host: String, port: Int, enc: H264Encoder) {
        val client = H264ClientSender(host, port) {
            enc.requestKeyFrame()
        }

        client.listener = object : H264ClientSender.ClientListener {
            override fun onConnected(host: String, port: Int) {
                runOnUiThread {
                    tvStatus.text = "Status: Connected to $host:$port"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_streaming))
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    tvStatus.text = "Status: Disconnected"
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_idle))
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onStatsUpdated(fps: Int, bitrateBps: Long, totalBytes: Long) {
                runOnUiThread {
                    val rateStr = NetworkUtils.formatBitrate(bitrateBps)
                    val totalStr = NetworkUtils.formatBytes(totalBytes)
                    tvStats.text = String.format(
                        Locale.US,
                        "FPS: %d | Rate: %s | Sent: %s",
                        fps, rateStr, totalStr
                    )
                }
            }
        }

        enc.frameListener = object : H264Encoder.FrameListener {
            override fun onH264Frame(frameData: ByteArray, isKeyFrame: Boolean) {
                client.sendFrame(frameData)
            }

            override fun onSpsPps(spsPpsData: ByteArray) {
                client.setSpsPps(spsPpsData)
            }
        }

        client.start()
        this.clientSender = client
    }

    private fun toggleCloudflareTunnel() {
        val manager = cloudflareManager
        if (manager != null && manager.isTunnelActive()) {
            stopCloudflareTunnel()
        } else {
            startCloudflareTunnel()
        }
    }

    private fun startCloudflareTunnel() {
        if (!isStreaming) {
            startStreaming()
            if (!isStreaming) return
        }

        val portStr = etPort.text.toString().trim().ifEmpty { "8080" }
        val port = portStr.toIntOrNull() ?: 8080

        cardCloudflare.visibility = View.VISIBLE
        tvCloudflareStatus.text = "Cloudflare Tunnel: Connecting to edge..."
        tvCloudflareUrl.text = "Obtaining public tunnel address..."
        btnCloudflareTunnel.text = getString(R.string.stop_cloudflare)
        btnCloudflareTunnel.setBackgroundColor(Color.parseColor("#E53935"))

        val manager = CloudflareTunnelManager(this)
        manager.listener = object : CloudflareTunnelManager.TunnelListener {
            override fun onTunnelStarting() {
                runOnUiThread {
                    btnCloudflareTunnel.text = getString(R.string.stop_cloudflare)
                    btnCloudflareTunnel.setBackgroundColor(Color.parseColor("#E53935"))
                }
            }

            override fun onTunnelUrlAvailable(publicUrl: String) {
                runOnUiThread {
                    tvCloudflareStatus.text = "Cloudflare Tunnel: Active (Online)"
                    tvCloudflareUrl.text = publicUrl
                    tvCloudflareInstructions.text = "Internet Playback Command:\nffplay -fflags nobuffer -flags low_delay $publicUrl/live.h264\n\nVLC URL: $publicUrl/live.h264"
                }
            }

            override fun onTunnelStatusUpdate(status: String) {
                runOnUiThread {
                    tvCloudflareStatus.text = "Cloudflare Tunnel: $status"
                }
            }

            override fun onTunnelError(error: String) {
                runOnUiThread {
                    tvCloudflareStatus.text = "Cloudflare Error: $error"
                    Toast.makeText(this@MainActivity, "Cloudflare Tunnel error: $error", Toast.LENGTH_LONG).show()
                }
            }

            override fun onTunnelStopped() {
                runOnUiThread {
                    btnCloudflareTunnel.text = getString(R.string.enable_cloudflare)
                    btnCloudflareTunnel.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.cloudflare_orange))
                    cardCloudflare.visibility = View.GONE
                }
            }
        }

        manager.startTunnel(port)
        this.cloudflareManager = manager
    }

    private fun stopCloudflareTunnel() {
        cloudflareManager?.stopTunnel()
        cloudflareManager = null
        btnCloudflareTunnel.text = getString(R.string.enable_cloudflare)
        btnCloudflareTunnel.setBackgroundColor(ContextCompat.getColor(this, R.color.cloudflare_orange))
        cardCloudflare.visibility = View.GONE
    }

    private fun stopStreaming() {
        isStreaming = false

        stopCloudflareTunnel()

        server?.stop()
        server = null

        clientSender?.stop()
        clientSender = null

        encoder?.stop()
        encoder = null

        cameraManager.setEncoderSurface(null)
        if (cameraManager.isCameraActive) {
            cameraManager.closeCamera()
            val (w, h) = getSelectedResolution()
            cameraManager.setTargetResolution(w, h)
            cameraManager.openCamera()
        }

        updateUiForStreaming(false)
    }

    private fun updateUiForStreaming(streaming: Boolean) {
        if (streaming) {
            btnToggleStream.text = getString(R.string.stop_stream)
            btnToggleStream.setIconResource(R.drawable.ic_stop)
            btnToggleStream.setBackgroundColor(Color.parseColor("#E53935"))
            etPort.isEnabled = false
            etHost.isEnabled = false
            rbServerMode.isEnabled = false
            rbClientMode.isEnabled = false
            spinnerResolution.isEnabled = false
            spinnerBitrate.isEnabled = false
        } else {
            btnToggleStream.text = getString(R.string.start_stream)
            btnToggleStream.setIconResource(R.drawable.ic_play)
            btnToggleStream.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            etPort.isEnabled = true
            etHost.isEnabled = true
            rbServerMode.isEnabled = true
            rbClientMode.isEnabled = true
            spinnerResolution.isEnabled = true
            spinnerBitrate.isEnabled = true
            tvStatus.text = getString(R.string.status_idle)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_idle))
            tvStats.text = "Clients: 0 | FPS: 0 | Bitrate: 0 bps"
        }
    }

    override fun onResume() {
        super.onResume()
        updateNetworkInfo()
        checkAndStartCamera()
    }

    override fun onPause() {
        super.onPause()
        if (isStreaming) {
            stopStreaming()
        }
        cameraManager.closeCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
