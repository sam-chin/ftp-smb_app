package com.lanmedia.player.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lanmedia.player.R

/**
 * DLNA投屏前台服务
 * 
 * 作用：
 * 1. 保持App在后台运行时不被系统杀死
 * 2. 保持HTTP代理服务器持续运行
 * 3. 显示通知让用户知道投屏正在进行
 */
class DlnaCastingService : Service() {
    
    companion object {
        const val CHANNEL_ID = "dlna_casting_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CASTING = "start_casting"
        const val ACTION_STOP_CASTING = "stop_casting"
        
        // 用于存储服务的单例引用（可选，方便从Activity访问）
        @Volatile
        private var instance: DlnaCastingService? = null
        
        fun getInstance(): DlnaCastingService? = instance
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CASTING -> {
                startForeground(NOTIFICATION_ID, createNotification("DLNA投屏进行中"))
            }
            ACTION_STOP_CASTING -> {
                stopSelf()
            }
        }
        
        // START_STICKY: 如果服务被杀死，系统会尝试重启它
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
    
    /**
     * 创建通知渠道（Android 8.0+必需）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DLNA投屏服务",
                NotificationManager.IMPORTANCE_LOW  // 低优先级，不打扰用户
            ).apply {
                description = "保持DLNA投屏在后台运行"
                setShowBadge(false)  // 不显示角标
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建前台通知
     */
    private fun createNotification(contentText: String): Notification {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DLNA投屏")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)  // 使用系统图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // 不可滑动删除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    /**
     * 更新通知内容
     */
    fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }
}
