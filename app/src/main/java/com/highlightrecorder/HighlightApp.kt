package com.highlightrecorder

import android.app.Application
import com.highlightrecorder.data.FileLogger
import com.highlightrecorder.upload.UploadCoordinator

class HighlightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        // 恢复上传队列:清理僵死状态,把进程被杀期间未完成的任务重新入队
        UploadCoordinator.reconcile(this)
    }
}
