package dev.shizukumcp.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    DENIED,
    AUTHORIZED,
}

object ShizukuGate {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val DOWNLOAD = "https://shizuku.rikka.app/download/"

    fun status(context: Context): ShizukuStatus {
        if (!isInstalled(context)) return ShizukuStatus.NOT_INSTALLED
        if (!binderAlive()) return ShizukuStatus.NOT_RUNNING
        return if (isGranted()) ShizukuStatus.AUTHORIZED else ShizukuStatus.DENIED
    }

    fun isInstalled(context: Context): Boolean = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isGranted(): Boolean {
        if (!binderAlive()) return false
        return try {
            if (Shizuku.isPreV11()) false
            else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        if (!binderAlive() || Shizuku.isPreV11()) return
        Shizuku.requestPermission(requestCode)
    }

    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
