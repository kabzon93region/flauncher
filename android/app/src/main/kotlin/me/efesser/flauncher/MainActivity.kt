/*
 * FLauncher
 * Copyright (C) 2021  Étienne Fesser
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.efesser.flauncher

import android.app.ActivityManager
import android.content.Intent
import android.content.Intent.*
import android.content.pm.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.Serializable

private const val METHOD_CHANNEL = "me.efesser.flauncher/method"
private const val EVENT_CHANNEL = "me.efesser.flauncher/event"

class MainActivity : FlutterActivity() {
    val launcherAppsCallbacks = ArrayList<LauncherApps.Callback>()

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getApplications" -> result.success(getApplications())
                "applicationExists" -> result.success(applicationExists(call.arguments as String))
                "launchApp" -> result.success(launchApp(call.arguments as String))
                "openSettings" -> result.success(openSettings())
                "openAppInfo" -> result.success(openAppInfo(call.arguments as String))
                "uninstallApp" -> result.success(uninstallApp(call.arguments as String))
                "isDefaultLauncher" -> result.success(isDefaultLauncher())
                "checkForGetContentAvailability" -> result.success(checkForGetContentAvailability())
                "startAmbientMode" -> result.success(startAmbientMode())
                "clearMemory" -> {
                    // Kill loop can take a couple of seconds: run it off the UI
                    // thread and deliver the result back on the main thread.
                    Thread {
                        val stats = clearMemory()
                        runOnUiThread { result.success(stats) }
                    }.start()
                }
                else -> throw IllegalArgumentException()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL).setStreamHandler(object : StreamHandler {
            lateinit var launcherAppsCallback: LauncherApps.Callback
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            override fun onListen(arguments: Any?, events: EventSink) {
                launcherAppsCallback = object : LauncherApps.Callback() {
                    override fun onPackageRemoved(packageName: String, user: UserHandle) {
                        events.success(mapOf("action" to "PACKAGE_REMOVED", "packageName" to packageName))
                    }

                    override fun onPackageAdded(packageName: String, user: UserHandle) {
                        getApplication(packageName)
                            ?.let { events.success(mapOf("action" to "PACKAGE_ADDED", "activitiyInfo" to it)) }
                    }

                    override fun onPackageChanged(packageName: String, user: UserHandle) {
                        getApplication(packageName)
                            ?.let { events.success(mapOf("action" to "PACKAGE_CHANGED", "activitiyInfo" to it)) }
                    }

                    override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
                        val applications = packageNames.map(::getApplication)
                        if (applications.isNotEmpty()) {
                            events.success(mapOf("action" to "PACKAGES_AVAILABLE", "activitiesInfo" to applications))
                        }
                    }

                    override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {}
                }

                launcherAppsCallbacks.add(launcherAppsCallback)
                launcherApps.registerCallback(launcherAppsCallback)
            }

            override fun onCancel(arguments: Any?) {
                launcherApps.unregisterCallback(launcherAppsCallback)
                launcherAppsCallbacks.remove(launcherAppsCallback)
            }
        })
    }

    override fun onDestroy() {
        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        launcherAppsCallbacks.forEach(launcherApps::unregisterCallback)
        super.onDestroy()
    }

    private fun getApplications(): List<Map<String, Serializable?>> {
        val tvActivitiesInfo = queryIntentActivities(false)
        val nonTvActivitiesInfo = queryIntentActivities(true)
                .filter { nonTvActivityInfo -> !tvActivitiesInfo.any { tvActivityInfo -> tvActivityInfo.packageName == nonTvActivityInfo.packageName } }
        return tvActivitiesInfo.map { buildAppMap(it, false) } + nonTvActivitiesInfo.map { buildAppMap(it, true) }
    }

    private fun getApplication(packageName: String): Map<String, Serializable?>? {
        return packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?.resolveActivityInfo(packageManager, 0)
            ?.let { buildAppMap(it, false) }
            ?: return packageManager.getLaunchIntentForPackage(packageName)
                ?.resolveActivityInfo(packageManager, 0)
                ?.let { buildAppMap(it, true) }
    }

    private fun applicationExists(packageName: String) = try {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES
        }
        packageManager.getApplicationInfo(packageName, flag)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun queryIntentActivities(sideloaded: Boolean) = packageManager
            .queryIntentActivities(Intent(ACTION_MAIN, null)
                    .addCategory(if (sideloaded) CATEGORY_LAUNCHER else CATEGORY_LEANBACK_LAUNCHER), 0)
            .map(ResolveInfo::activityInfo)

    private fun buildAppMap(activityInfo: ActivityInfo, sideloaded: Boolean) = mapOf(
            "name" to activityInfo.loadLabel(packageManager).toString(),
            "packageName" to activityInfo.packageName,
            "banner" to activityInfo.loadBanner(packageManager)?.let(::drawableToByteArray),
            "icon" to activityInfo.loadIcon(packageManager)?.let(::drawableToByteArray),
            "version" to packageManager.getPackageInfo(activityInfo.packageName, 0).versionName,
            "sideloaded" to sideloaded,
    )

    private fun launchApp(packageName: String) = try {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun openSettings() = try {
        startActivity(Intent(Settings.ACTION_SETTINGS))
        true
    } catch (e: Exception) {
        false
    }

    private fun openAppInfo(packageName: String) = try {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .let(::startActivity)
        true
    } catch (e: Exception) {
        false
    }

    private fun uninstallApp(packageName: String) = try {
        Intent(ACTION_DELETE)
                .setData(Uri.fromParts("package", packageName, null))
                .let(::startActivity)
        true
    } catch (e: Exception) {
        false
    }

    private fun checkForGetContentAvailability() = try {
        val intentActivities = packageManager.queryIntentActivities(Intent(ACTION_GET_CONTENT, null).setTypeAndNormalize("image/*"), 0)
        intentActivities.isNotEmpty()
    } catch (e: Exception) {
        false
    }

    private fun isDefaultLauncher() = try {
        val defaultLauncher = packageManager.resolveActivity(Intent(ACTION_MAIN).addCategory(CATEGORY_HOME), 0)
        defaultLauncher?.activityInfo?.packageName == packageName
    } catch (e: Exception) {
        false
    }

    private fun startAmbientMode() = try {
        Intent(ACTION_MAIN)
            .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
            .let(::startActivity)
        true
    } catch (e: Exception) {
        false
    }

    // Rocket button: free RAM by killing BACKGROUND processes of THIRD-PARTY
    // apps only. Protection guarantees:
    //  - system apps are never in the target list (pm list packages -3 for the
    //    root path, FLAG_SYSTEM filter for the fallback), so the box stack is
    //    untouched;
    //  - this launcher and Magisk are explicitly excluded (critical set);
    //  - ActivityManager itself refuses to kill foreground / visible /
    //    foreground-service processes, so a running app stays alive.
    // Rocket button result: how many background processes were closed, how
    // much memory the cleanup released and how much is free right now.
    private fun clearMemory(): Map<String, Any> {
        val freeBefore = readMemFreeKb()
        val killed = killThirdPartyBackground()
        val freeAfter = readMemFreeKb()
        val freedKb = if (freeBefore >= 0 && freeAfter >= 0) {
            (freeAfter - freeBefore).coerceAtLeast(0L)
        } else {
            0L
        }
        return mapOf(
            "killed" to killed,
            "freedKb" to freedKb,
            "freeKb" to freeAfter,
        )
    }

    private fun readMemFreeKb(): Long {
        try {
            java.io.BufferedReader(java.io.FileReader("/proc/meminfo")).use { reader ->
                for (line in reader.readLines()) {
                    if (line.startsWith("MemFree:")) {
                        return line.trim().split(Regex("\\s+"))[1].toLong()
                    }
                }
            }
        } catch (e: Exception) {
            return -1L
        }
        return -1L
    }

    // Kill loop: returns the number of closed background processes.
    private fun killThirdPartyBackground(): Int {
        val critical = setOf(packageName, "com.topjohnwu.magisk")

        // Primary path with root: kill background processes of RUNNING
        // third-party packages except the critical ones; report the delta of
        // the total process count. System packages are never targeted.
        try {
            val command = "RUN=\$(ps -A -o NAME | sed 's/:.*\$//' | sort -u); " +
                "B=\$(ps -A -o NAME | wc -l); " +
                "for p in \$(pm list packages -3 | sed 's/^package://'); do " +
                "case \$p in com.topjohnwu.magisk|me.efesser.flauncher) continue;; esac; " +
                "echo \"\$RUN\" | grep -qx \"\$p\" && am kill \$p 2>/dev/null; done; " +
                "sleep 1; A=\$(ps -A -o NAME | wc -l); echo KILLED=\$((B - A))"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val match = Regex("KILLED=(\\d+)").find(output)
            if (match != null) {
                return match.groupValues[1].toInt().coerceAtLeast(0)
            }
        } catch (e: Exception) {
            // su unavailable - fall through to the non-root path.
        }

        // Fallback without root: launchable third-party apps only.
        var killed = 0
        try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val launchIntents = listOf(
                Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER),
                Intent(ACTION_MAIN).addCategory(CATEGORY_LEANBACK_LAUNCHER),
            )
            val packages = LinkedHashSet<String>()
            for (intent in launchIntents) {
                for (resolveInfo in packageManager.queryIntentActivities(intent, 0)) {
                    val info = resolveInfo.activityInfo
                    val target = info.packageName
                    if (critical.contains(target)) {
                        continue
                    }
                    if ((info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                        continue
                    }
                    packages.add(target)
                }
            }
            for (targetPackage in packages) {
                try {
                    activityManager.killBackgroundProcesses(targetPackage)
                    killed++
                } catch (e: Exception) {
                    // Ignore protected packages.
                }
            }
        } catch (e: Exception) {
            return killed
        }
        return killed
    }

    private fun drawableToByteArray(drawable: Drawable): ByteArray? {
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return null
        }

        fun drawableToBitmap(drawable: Drawable): Bitmap {
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        val bitmap = drawableToBitmap(drawable)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
