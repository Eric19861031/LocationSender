package com.location.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors

class LocationService : Service() {

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var mqttClient: MqttClient? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isConnecting = false

    companion object {
        private const val TAG = "LocationService"
        const val CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1
        const val MQTT_BROKER = "tcp://broker.emqx.io:1883"
        const val TOPIC_REQUEST  = "loc/tracker/fixed_channel_A7B3C9D2E1F5/req"
        const val TOPIC_RESPONSE = "loc/tracker/fixed_channel_A7B3C9D2E1F5/res"

        @Volatile var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("待命中，等待接收器请求..."))
        connectMqtt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connectMqtt() {
        if (isConnecting) return
        isConnecting = true
        executor.execute {
            try {
                try { mqttClient?.disconnect() } catch (e: Exception) { }
                val clientId = "sender_${System.currentTimeMillis()}"
                mqttClient = MqttClient(MQTT_BROKER, clientId, MemoryPersistence())
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT 断开: ${cause?.message}")
                        updateNotification("连接断开，重连中...")
                        mainHandler.postDelayed({ connectMqtt() }, 5000)
                    }
                    override fun messageArrived(topic: String, message: MqttMessage) {
                        if (topic == TOPIC_REQUEST) handleLocationRequest()
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val options = MqttConnectOptions().apply {
                    isCleanSession = false
                    connectionTimeout = 30
                    keepAliveInterval = 60
                    isAutomaticReconnect = true
                    maxReconnectDelay = 10000
                }
                mqttClient?.connect(options)
                mqttClient?.subscribe(TOPIC_REQUEST, 1)
                isConnecting = false
                Log.d(TAG, "MQTT 连接并订阅请求频道成功")
                updateNotification("待命中，等待接收器请求...")
            } catch (e: Exception) {
                isConnecting = false
                Log.e(TAG, "MQTT 连接失败: ${e.message}")
                updateNotification("连接失败，重试中...")
                mainHandler.postDelayed({ connectMqtt() }, 5000)
            }
        }
    }

    private fun handleLocationRequest() {
        Log.d(TAG, "收到位置请求，正在定位...")
        updateNotification("收到请求，定位中...")
        val cts = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val payload = JSONObject().apply {
                            put("lat", location.latitude)
                            put("lon", location.longitude)
                            put("accuracy", location.accuracy.toDouble())
                            put("timestamp", System.currentTimeMillis())
                        }.toString()
                        executor.execute {
                            try {
                                mqttClient?.publish(
                                    TOPIC_RESPONSE,
                                    MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
                                )
                                Log.d(TAG, "位置已响应: ${location.latitude}, ${location.longitude}")
                                updateNotification(
                                    "已响应：${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)}"
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "发布响应失败: ${e.message}")
                                updateNotification("响应发送失败，待命中...")
                            }
                        }
                    } else {
                        Log.w(TAG, "定位返回 null")
                        updateNotification("定位失败，待命中...")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "定位失败: ${e.message}")
                    updateNotification("定位失败，待命中...")
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少位置权限")
            updateNotification("缺少位置权限，请重新授权")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "位置待命服务", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持服务待命，响应接收器的位置查询请求"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📍 位置发送器 · 待命中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        executor.execute { try { mqttClient?.disconnect() } catch (e: Exception) { } }
        executor.shutdown()
    }
}
