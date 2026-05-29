package com.location.sender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.location.sender.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            requestBackgroundPermissionIfNeeded()
        } else {
            Toast.makeText(this, "需要精确位置权限才能运行", Toast.LENGTH_LONG).show()
        }
    }

    private val backgroundPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startLocationService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

            binding.tvChannelId.text = "频道: fixed_channel_A7B3C9D2E1F5"
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            requestBackgroundPermissionIfNeeded()
        } else {
            locationPermLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun requestBackgroundPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!bgGranted) {
                AlertDialog.Builder(this)
                    .setTitle("后台位置权限（重要）")
                    .setMessage("为保证 24 小时持续定位，请在下一页选择「始终允许」位置权限。\n\n否则应用进入后台后将停止上报位置。")
                    .setPositiveButton("去授权") { _, _ ->
                        backgroundPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("跳过") { _, _ -> startLocationService() }
                    .show()
            } else {
                startLocationService()
            }
        } else {
            startLocationService()
        }
    }

    private fun startLocationService() {
        if (LocationService.isRunning) return
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI()
    }

    private fun updateUI() {
        if (LocationService.isRunning) {
            binding.tvStatus.text = "● 待命中"
            binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
            binding.tvLocation.text = "等待接收器发起请求..."
        } else {
            binding.tvStatus.text = "● 初始化中"
            binding.tvStatus.setTextColor(0xFFFFC107.toInt())
            binding.tvLocation.text = "服务启动中，请稍候..."
        }
    }
}
