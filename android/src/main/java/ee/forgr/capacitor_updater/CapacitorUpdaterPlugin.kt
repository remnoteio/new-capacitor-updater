package ee.forgr.capacitor_updater

import com.getcapacitor.annotation.CapacitorPlugin
import android.app.Application.ActivityLifecycleCallbacks
import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import kotlin.jvm.Volatile
import java.lang.Thread
import android.app.Activity
import com.android.volley.toolbox.Volley
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import java.lang.Exception
import android.app.Application
import android.os.Build
import java.lang.Runnable
import java.io.IOException
import android.os.Bundle
import android.provider.Settings
import com.getcapacitor.*
import com.getcapacitor.plugin.WebView
import io.github.g00fy2.versioncompare.Version
import org.json.JSONException
import org.json.JSONObject
import java.lang.InterruptedException

@CapacitorPlugin(name = "CapacitorUpdater")
class CapacitorUpdaterPlugin : Plugin(), ActivityLifecycleCallbacks {
    private lateinit var implementation: CapacitorUpdater
    private lateinit var prefs: SharedPreferences
    private var editor: Editor? = null
    private var appReadyTimeout = 10000
    private var autoUpdateUrl = ""
    private var currentVersionNative: Version? = null
    private var autoDeleteFailed = true
    private var autoDeletePrevious = true
    private var autoUpdate = false
    private var resetWhenUpdate = true

    @Volatile
    private var appReadyCheck: Thread? = null
    override fun load() {
        super.load()
        prefs = this.context.getSharedPreferences(WebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE)
        editor = prefs.edit()
        try {
            implementation = object : CapacitorUpdater() {
                override fun notifyDownload(percent: Int) {
                    this.notifyDownload(percent)
                }
            }
            val pInfo = this.context.packageManager.getPackageInfo(this.context.packageName, 0)
            implementation.versionBuild = pInfo.versionName
            implementation.versionCode = Integer.toString(pInfo.versionCode)
            implementation.requestQueue = Volley.newRequestQueue(this.context)
            currentVersionNative = Version(pInfo.versionName)
        } catch (e: NameNotFoundException) {
            Log.e(CapacitorUpdater.TAG, "Error instantiating implementation", e)
            return
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Error getting current native app version", e)
            return
        }
        val config = CapConfig.loadDefault(this.activity)
        implementation.appId = config.getString("appId", "")
        implementation.statsUrl = config.getString("statsUrl", statsUrlDefault)
        implementation.prefs =
            this.context.getSharedPreferences(WebView.WEBVIEW_PREFS_NAME, Activity.MODE_PRIVATE)
        implementation.editor = prefs.edit()
        implementation.versionOs = Build.VERSION.RELEASE
        implementation.deviceID =
            Settings.Secure.getString(this.context.contentResolver, Settings.Secure.ANDROID_ID)
        autoDeleteFailed = config.getBoolean("autoDeleteFailed", true)
        autoDeletePrevious = config.getBoolean("autoDeletePrevious", true)
        autoUpdateUrl = config.getString("autoUpdateUrl", autoUpdateUrlDefault)
        autoUpdate = config.getBoolean("autoUpdate", false)
        appReadyTimeout = config.getInt("appReadyTimeout", 10000)
        resetWhenUpdate = config.getBoolean("resetWhenUpdate", true)
        cleanupObsoleteVersions()
        if (!autoUpdate || autoUpdateUrl == "") return
        val application = this.context.applicationContext as Application
        application.registerActivityLifecycleCallbacks(this)
        onActivityStarted(this.activity)
    }

    private fun cleanupObsoleteVersions() {
        if (resetWhenUpdate) {
            try {
                val previous = Version(
                    prefs!!.getString("LatestVersionNative", "")
                )
                try {
                    if ("" != previous.originalString && currentVersionNative!!.major > previous.major) {
                        implementation!!.reset(true)
                        val installed = implementation!!.list()
                        for (version in installed) {
                            try {
                                Log.i(CapacitorUpdater.TAG, "Deleting obsolete version: $version")
                                implementation!!.delete(version.version)
                            } catch (e: Exception) {
                                Log.e(CapacitorUpdater.TAG, "Failed to delete: $version", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(CapacitorUpdater.TAG, "Could not determine the current version", e)
                }
            } catch (e: Exception) {
                Log.e(CapacitorUpdater.TAG, "Error calculating previous native version", e)
            }
        }
        editor!!.putString("LatestVersionNative", currentVersionNative.toString())
        editor!!.commit()
    }

    fun notifyDownload(percent: Int) {
        try {
            val ret = JSObject()
            ret.put("percent", percent)
            this.notifyListeners("download", ret)
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not notify listeners", e)
        }
    }

    @PluginMethod
    fun getId(call: PluginCall) {
        try {
            val ret = JSObject()
            ret.put("id", implementation!!.deviceID)
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not get device id", e)
            call.reject("Could not get device id", e)
        }
    }

    @PluginMethod
    fun getPluginVersion(call: PluginCall) {
        val ret = JSObject()
        ret.put("version", CapacitorUpdater.pluginVersion)
        call.resolve(ret)
    }

    @PluginMethod
    fun download(call: PluginCall) {
        val url = call.getString("url")
        try {
            Log.i(CapacitorUpdater.TAG, "Downloading $url")
            Thread {
                try {
                    val versionName = call.getString("versionName")
                    val downloaded = implementation!!.download(
                        url!!, versionName
                    )
                    call.resolve(downloaded.toJSON())
                } catch (e: IOException) {
                    Log.e(CapacitorUpdater.TAG, "download failed", e)
                    call.reject("download failed", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Failed to download $url", e)
            call.reject("Failed to download $url", e)
        }
    }

    private fun _reload(): Boolean {
        val path = implementation!!.currentBundlePath
        Log.i(CapacitorUpdater.TAG, "Reloading: $path")
        if (implementation!!.isUsingBuiltin) {
            bridge.setServerAssetPath(path)
        } else {
            bridge.serverBasePath = path
        }
        checkAppReady()
        return true
    }

    @PluginMethod
    fun reload(call: PluginCall) {
        try {
            if (_reload()) {
                call.resolve()
            } else {
                call.reject("reload failed")
            }
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not reload", e)
            call.reject("Could not reload", e)
        }
    }

    @PluginMethod
    fun next(call: PluginCall) {
        val version = call.getString("version")
        val versionName = call.getString("versionName", "")
        try {
            Log.i(CapacitorUpdater.TAG, "Setting next active version $version")
            if (!implementation!!.setNextVersion(version)) {
                call.reject("Set next version failed. Version $version does not exist.")
            } else {
                if ("" != versionName) {
                    implementation!!.setVersionName(version, versionName)
                }
                call.resolve(implementation!!.getVersionInfo(version).toJSON())
            }
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not set next version $version", e)
            call.reject("Could not set next version $version", e)
        }
    }

    @PluginMethod
    fun set(call: PluginCall) {
        val version = call.getString("version")
        val versionName = call.getString("versionName", "")
        try {
            Log.i(CapacitorUpdater.TAG, "Setting active bundle $version")
            if (!implementation!!.set(version!!)) {
                Log.i(CapacitorUpdater.TAG, "No such bundle $version")
                call.reject("Update failed, version $version does not exist.")
            } else {
                Log.i(CapacitorUpdater.TAG, "Bundle successfully set to$version")
                if ("" != versionName) {
                    implementation!!.setVersionName(version, versionName)
                }
                reload(call)
            }
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not set version $version", e)
            call.reject("Could not set version $version", e)
        }
    }

    @PluginMethod
    fun delete(call: PluginCall) {
        val version = call.getString("version")
        Log.i(CapacitorUpdater.TAG, "Deleting version: $version")
        try {
            val res = implementation!!.delete(version!!)
            if (res) {
                call.resolve()
            } else {
                call.reject("Delete failed, version $version does not exist")
            }
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not delete version $version", e)
            call.reject("Could not delete version $version", e)
        }
    }

    @PluginMethod
    fun list(call: PluginCall) {
        try {
            val res = implementation!!.list()
            val ret = JSObject()
            val values = JSArray()
            for (version in res) {
                values.put(version.toJSON())
            }
            ret.put("versions", values)
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not list versions", e)
            call.reject("Could not list versions", e)
        }
    }

    private fun _reset(toLastSuccessful: Boolean?): Boolean {
        val fallback = implementation!!.fallbackVersion
        implementation!!.reset()
        if (toLastSuccessful!! && !fallback!!.isBuiltin) {
            Log.i(CapacitorUpdater.TAG, "Resetting to: $fallback")
            return implementation!!.set(fallback) && _reload()
        }
        Log.i(CapacitorUpdater.TAG, "Resetting to native.")
        return _reload()
    }

    @PluginMethod
    fun reset(call: PluginCall) {
        try {
            val toLastSuccessful = call.getBoolean("toLastSuccessful", false)
            if (_reset(toLastSuccessful)) {
                call.resolve()
                return
            }
            call.reject("Reset failed")
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Reset failed", e)
            call.reject("Reset failed", e)
        }
    }

    @PluginMethod
    fun current(call: PluginCall) {
        try {
            val ret = JSObject()
            val bundle = implementation!!.currentBundle
            ret.put("bundle", bundle.toJSON())
            ret.put("native", currentVersionNative)
            call.resolve(ret)
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Could not get current bundle version", e)
            call.reject("Could not get current bundle version", e)
        }
    }

    @PluginMethod
    fun notifyAppReady(call: PluginCall) {
        try {
            Log.i(CapacitorUpdater.TAG, "Current bundle loaded successfully.")
            val version = implementation!!.currentBundle
            implementation!!.commit(version)
            call.resolve()
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Failed to notify app ready state", e)
            call.reject("Failed to notify app ready state", e)
        }
    }

    @PluginMethod
    fun delayUpdate(call: PluginCall) {
        try {
            Log.i(CapacitorUpdater.TAG, "Delay update.")
            editor!!.putBoolean(DELAY_UPDATE, true)
            editor!!.commit()
            call.resolve()
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Failed to delay update", e)
            call.reject("Failed to delay update", e)
        }
    }

    @PluginMethod
    fun cancelDelay(call: PluginCall) {
        try {
            Log.i(CapacitorUpdater.TAG, "Cancel update delay.")
            editor!!.putBoolean(DELAY_UPDATE, false)
            editor!!.commit()
            call.resolve()
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Failed to cancel update delay", e)
            call.reject("Failed to cancel update delay", e)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (isAutoUpdateEnabled) {
            Thread {
                Log.i(CapacitorUpdater.TAG, "Check for update via: " + autoUpdateUrl)
                implementation!!.getLatest(autoUpdateUrl) { res: JSONObject ->
                    try {
                        if (res.has("message")) {
                            Log.i(CapacitorUpdater.TAG, "message: " + res["message"])
                            if (res.has("major") && res.getBoolean("major") && res.has("version")) {
                                val majorAvailable = JSObject()
                                majorAvailable.put("version", res["version"] as String)
                                this@CapacitorUpdaterPlugin.notifyListeners(
                                    "majorAvailable",
                                    majorAvailable
                                )
                            }
                            return@getLatest
                        }
                        val current = implementation!!.currentBundle
                        val newVersion = res["version"] as String

                        // FIXME: What is failingVersion actually doing? Seems redundant with VersionStatus
                        val failingVersion = prefs!!.getString("failingVersion", "")
                        if ("" != newVersion && newVersion != current.version && newVersion != failingVersion) {
                            Thread(object : Runnable {
                                override fun run() {
                                    try {
                                        val url = res["url"] as String
                                        val next = implementation!!.download(url, newVersion)
                                        Log.i(
                                            CapacitorUpdater.TAG,
                                            "New version: " + newVersion + " found. Current is " + (if (current.name == "") "builtin" else current.name) + ", next backgrounding will trigger update"
                                        )
                                        implementation!!.setNextVersion(next.version)
                                        notifyUpdateAvailable(next.version)
                                    } catch (e: Exception) {
                                        Log.e(CapacitorUpdater.TAG, "error downloading file", e)
                                    }
                                }

                                private fun notifyUpdateAvailable(version: String) {
                                    val updateAvailable = JSObject()
                                    updateAvailable.put("version", version)
                                    this@CapacitorUpdaterPlugin.notifyListeners(
                                        "updateAvailable",
                                        updateAvailable
                                    )
                                }
                            }).start()
                        } else {
                            Log.i(
                                CapacitorUpdater.TAG,
                                "No need to update, $current is the latest version."
                            )
                        }
                    } catch (e: JSONException) {
                        Log.e(CapacitorUpdater.TAG, "error parsing JSON", e)
                    }
                }
            }.start()
        }
        checkAppReady()
    }

    override fun onActivityStopped(activity: Activity) {
        Log.i(CapacitorUpdater.TAG, "Checking for pending update")
        try {
            val delayUpdate = prefs!!.getBoolean(DELAY_UPDATE, false)
            editor!!.putBoolean(DELAY_UPDATE, false)
            editor!!.commit()
            if (delayUpdate) {
                Log.i(CapacitorUpdater.TAG, "Update delayed to next backgrounding")
                return
            }
            val fallback = implementation!!.fallbackVersion
            val current = implementation!!.currentBundle
            val next = implementation!!.nextVersion
            val success = current.status == VersionStatus.SUCCESS
            if (next != null && !next.isErrorStatus && next.version !== current.version) {
                // There is a next version waiting for activation
                if (implementation!!.set(next) && _reload()) {
                    Log.i(CapacitorUpdater.TAG, "Updated to version: $next")
                    implementation!!.setNextVersion(null)
                } else {
                    Log.e(CapacitorUpdater.TAG, "Update to version: $next Failed!")
                }
            } else if (!success) {
                // There is a no next version, and the current version has failed
                if (!current.isBuiltin) {
                    // Don't try to roll back the builtin version. Nothing we can do.
                    implementation!!.rollback(current)
                    Log.i(
                        CapacitorUpdater.TAG,
                        "Update failed: 'notifyAppReady()' was never called."
                    )
                    Log.i(CapacitorUpdater.TAG, "Version: $current, is in error state.")
                    Log.i(
                        CapacitorUpdater.TAG,
                        "Will fallback to: $fallback on application restart."
                    )
                    Log.i(
                        CapacitorUpdater.TAG,
                        "Did you forget to call 'notifyAppReady()' in your Capacitor App code?"
                    )
                    if (!fallback!!.isBuiltin && fallback != current) {
                        val res = implementation!!.set(fallback)
                        if (res && _reload()) {
                            Log.i(CapacitorUpdater.TAG, "Revert to version: $fallback")
                        } else {
                            Log.e(CapacitorUpdater.TAG, "Revert to version: $fallback Failed!")
                        }
                    } else {
                        if (_reset(false)) {
                            Log.i(CapacitorUpdater.TAG, "Reverted to 'builtin' bundle.")
                        }
                    }
                    if (autoDeleteFailed) {
                        Log.i(CapacitorUpdater.TAG, "Deleting failing version: $current")
                        try {
                            val res = implementation!!.delete(current.version)
                            if (res) {
                                Log.i(CapacitorUpdater.TAG, "Failed version deleted: $current")
                            }
                        } catch (e: IOException) {
                            Log.e(
                                CapacitorUpdater.TAG,
                                "Failed to delete failed version: $current",
                                e
                            )
                        }
                    }
                } else {
                    // Nothing we can/should do by default if the 'builtin' bundle fails to call 'notifyAppReady()'.
                }
            } else if (!fallback!!.isBuiltin) {
                // There is a no next version, and the current version has succeeded
                implementation!!.commit(current)
                if (autoDeletePrevious) {
                    Log.i(CapacitorUpdater.TAG, "Version successfully loaded: $current")
                    try {
                        val res = implementation!!.delete(fallback.version)
                        if (res) {
                            Log.i(CapacitorUpdater.TAG, "Deleted previous version: $fallback")
                        }
                    } catch (e: IOException) {
                        Log.e(
                            CapacitorUpdater.TAG,
                            "Failed to delete previous version: $fallback",
                            e
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(CapacitorUpdater.TAG, "Error during onActivityStopped", e)
        }
    }

    private val isAutoUpdateEnabled: Boolean
        private get() = "" != autoUpdateUrl

    // not use but necessary here to remove warnings
    override fun onActivityResumed(activity: Activity) {
        // TODO: Implement background updating based on `backgroundUpdate` and `backgroundUpdateDelay` capacitor.config.ts settings
    }

    override fun onActivityPaused(activity: Activity) {
        // TODO: Implement background updating based on `backgroundUpdate` and `backgroundUpdateDelay` capacitor.config.ts settings
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
    private fun checkAppReady() {
        try {
            if (appReadyCheck != null) {
                appReadyCheck!!.interrupt()
            }
            appReadyCheck = Thread(DeferredNotifyAppReadyCheck())
            appReadyCheck!!.start()
        } catch (e: Exception) {
            Log.e(
                CapacitorUpdater.TAG,
                "Failed to start " + DeferredNotifyAppReadyCheck::class.java.name,
                e
            )
        }
    }

    private inner class DeferredNotifyAppReadyCheck : Runnable {
        override fun run() {
            try {
                Log.i(
                    CapacitorUpdater.TAG,
                    "Wait for " + appReadyTimeout + "ms, then check for notifyAppReady"
                )
                Thread.sleep(appReadyTimeout.toLong())
                // Automatically roll back to fallback version if notifyAppReady has not been called yet
                val current = implementation!!.currentBundle
                if (current.isBuiltin) {
                    Log.i(CapacitorUpdater.TAG, "Built-in bundle is active. Nothing to do.")
                    return
                }
                if (VersionStatus.SUCCESS != current.status) {
                    Log.e(
                        CapacitorUpdater.TAG,
                        "notifyAppReady was not called, roll back current version: $current"
                    )
                    implementation!!.rollback(current)
                    _reset(true)
                } else {
                    Log.i(CapacitorUpdater.TAG, "notifyAppReady was called. This is fine: $current")
                }
                appReadyCheck = null
            } catch (e: InterruptedException) {
                Log.e(
                    CapacitorUpdater.TAG,
                    DeferredNotifyAppReadyCheck::class.java.name + " was interrupted."
                )
            }
        }
    }

    companion object {
        private const val autoUpdateUrlDefault = "https://capgo.app/api/auto_update"
        private const val statsUrlDefault = "https://capgo.app/api/stats"
        private const val DELAY_UPDATE = "delayUpdate"
    }
}