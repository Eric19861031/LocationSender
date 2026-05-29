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

        binding.tvChannelId.text = "通信频道: ${LocationService.MQTT_TOPIC}"

        binding.btnToggleService.setOnClickListener {
            if (LocationService.isRunning) {
                stopLocationService()
            } else {
                checkAndRequestPermissions()
            }
        }
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
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI()
        Toast.makeText(this, "位置发送服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationService() {
        stopService(Intent(this, LocationService::class.java))
        updateUI()
        Toast.makeText(this, "位置发送服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (LocationService.isRunning) {
            binding.btnToggleService.text = "停止发送"
            binding.btnToggleService.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            binding.tvStatus.text = "● 运行中"
            binding.tvStatus.setTextColor(0xFF4CAF50.toInt())
            binding.tvLocation.text = "正在发送位置数据..."
        } else {
            binding.btnToggleService.text = "开始发送位置"
            binding.btnToggleService.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            binding.tvStatus.text = "● 已停止"
            binding.tvStatus.setTextColor(0xFFE53935.toInt())
            binding.tvLocation.text = "等待启动..."
        }
    }
}
