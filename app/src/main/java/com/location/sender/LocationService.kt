package com.location.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors

class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var mqttClient: MqttClient? = null
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "LocationService"
        const val CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1
        const val MQTT_BROKER = "tcp://broker.emqx.io:1883"
        const val MQTT_TOPIC = "loc/tracker/fixed_channel_A7B3C9D2E1F5"

        @Volatile
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化定位..."))
        connectMqtt()
        startLocationUpdates()
    }

    private fun connectMqtt() {
        executor.execute {
            try {
                try { mqttClient?.disconnect() } catch (e: Exception) { /* ignore */ }
                val clientId = "sender_${System.currentTimeMillis()}"
                mqttClient = MqttClient(MQTT_BROKER, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    maxReconnectDelay = 10000
                }
                mqttClient?.connect(options)
                Log.d(TAG, "MQTT 连接成功")
            } catch (e: Exception) {
                Log.e(TAG, "MQTT 连接失败: ${e.message}")
            }
        }
    }

    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { publishLocation(it) }
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少位置权限: ${e.message}")
        }
    }

    private fun publishLocation(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = location.accuracy
        val timestamp = System.currentTimeMillis()

        updateNotification("%.6f, %.6f  精度:%.1fm".format(lat, lon, accuracy))

        executor.execute {
            try {
                if (mqttClient?.isConnected == false) {
                    connectMqtt()
                    Thread.sleep(2000)
                }
                val payload = JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                    put("accuracy", accuracy)
                    put("timestamp", timestamp)
                }.toString()

                val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                    qos = 1
                    isRetained = true
                }
                mqttClient?.publish(MQTT_TOPIC, message)
                Log.d(TAG, "已发布位置: $lat, $lon")
            } catch (e: Exception) {
                Log.e(TAG, "发布失败: ${e.message}")
                connectMqtt()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "位置发送服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持位置发送服务在后台持续运行"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 位置发送器运行中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(content))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        executor.execute {
            try { mqttClient?.disconnect() } catch (e: Exception) { /* ignore */ }
        }
        executor.shutdown()
    }
}
