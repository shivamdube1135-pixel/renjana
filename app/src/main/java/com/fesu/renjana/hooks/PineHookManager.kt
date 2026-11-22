package com.fesu.renjana.hooks

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.virtual.GuestInfoCache
import dalvik.system.DexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.lsposed.hiddenapibypass.HiddenApiBypass
import top.canyie.pine.PineConfig
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Method

object PineHookManager {

    private const val TAG = "PineHookManager"
    private const val CONTAINER_PACKAGE = "com.fesu.renjana"

    private val hookedPackages = mutableSetOf<Pair<String, String>>()

    @Volatile
    private var initialized = false

    @Volatile
    private var pineAvailable = false

    private var hooksInstalled = 0

    fun initialize(): Boolean {
        if (initialized) {
            return pineAvailable
        }

        try {
            unsealHiddenApi()

            // On Android 14/15 (API 34+), keep Pine disabled by default for guest hooks
            // to prevent ART 15 trampoline/WebView/Resource crashes on heavy apps (X, ChatGPT).
            // VirtualContext + StubActivity handles isolation safely.
            if (Build.VERSION.SDK_INT >= 34) {
                pineAvailable = false
                initialized = true
                RenjanaLog.i(TAG, "Android 14+ detected: running in Safe VirtualContext mode (Pine guest hooks bypassed)")
                return false
            }

            pineAvailable = try {
                val pineClass = Class.forName("top.canyie.pine.Pine")
                try {
                    pineClass.getMethod("is64Bit").invoke(null)
                } catch (e: UnsatisfiedLinkError) {
                    RenjanaLog.w(TAG, "Pine native lib unavailable: ${e.message}")
                    pineAvailable = false
                    return false
                }
                true
            } catch (e: Throwable) {
                RenjanaLog.w(TAG, "Pine library not available: ${e.message}")
                false
            }

            if (!pineAvailable) {
                initialized = true
                return false
            }

            PineConfig.debug = false
            PineConfig.debuggable = false
            installSystemHooks()
            initialized = true
            RenjanaLog.i(TAG, "Pine hook manager initialized successfully")
            return true
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Pine initialization failed: ${e.message}")
            initialized = true
            return false
        }
    }

    fun isAvailable(): Boolean = pineAvailable

    private fun unsealHiddenApi() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
                RenjanaLog.i(TAG, "Hidden API restrictions unsealed via HiddenApiBypass")
                return
            }
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "HiddenApiBypass failed, trying fallback: ${e.message}")
        }

        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf(arrayOf<String>()::class.java)
            ) as Method
            val runtime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
        } catch (_: Throwable) {}
    }

    fun installGuestHooks(
        packageName: String,
        classLoader: ClassLoader,
        instanceId: String,
        dataPath: String,
        apkPath: String? = null,
        instance: Instance? = null
    ): Boolean {
        if (!pineAvailable || Build.VERSION.SDK_INT >= 34) {
            return false
        }

        CoreHooks.classLoaderToInstanceId[classLoader] = instanceId

        synchronized(hookedPackages) {
            if (hookedPackages.contains(instanceId to packageName)) {
                CoreHooks.classLoaderToInstanceId[classLoader] = instanceId
                return true
            }

            try {
                val appDataPath = "$dataPath/$packageName"
                File(appDataPath).apply {
                    listOf("files", "databases", "shared_prefs", "cache", "code_cache", "dex_opt")
                        .forEach { File(this, it).mkdirs() }
                }
                CoreHooks.registerPackage(packageName, appDataPath, instanceId)
                CoreHooks.registerClassLoader(classLoader, instanceId)
                CoreHooks.currentInstanceId.set(instanceId)

                if (instance != null) {
                    CoreHooks.registerInstanceConfig(instanceId, instance.config)
                }

                hooksInstalled = 0

                val resolvedApkPath = apkPath ?: resolveApkPath(dataPath, classLoader)
                if (resolvedApkPath != null) {
                    GuestInfoCache.cache(instanceId, packageName, resolvedApkPath)
                    SignatureSpoof.storeOriginalSignatures(packageName, resolvedApkPath)
                }

                hookActivityThread()
                hookActivityPerformCreate()
                hookPackageManager(classLoader)
                hookGoogleSignIn(classLoader)
                hookSharedPreferences()
                hookFileConstructors()
                hookFileExists()
                hookSystemGetProperty()
                hookSystemPropertiesGet()
                hookGetStackTrace()
                hookClassForName()
                hookProcMaps()

                if (instance != null && instance.config.enableFingerprint) {
                    hookFingerprintHooks()
                }

                hookNotifications()

                if (GoogleSignInHook.installHooks(classLoader, instanceId)) {
                    hooksInstalled++
                }

                if (instance == null || !instance.config.enableGms) {
                    if (installGmsUnavailableHooks(classLoader)) {
                        hooksInstalled++
                    }
                }

                hookedPackages.add(instanceId to packageName)
                return hooksInstalled > 0
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to install Pine guest hooks: ${e.message}")
                return false
            }
        }
    }

    fun uninstallGuestHooks(packageName: String, instanceId: String) {
        synchronized(hookedPackages) {
            if (!hookedPackages.contains(instanceId to packageName)) return
            try {
                CoreHooks.unregisterPackage(packageName)
                CoreHooks.currentInstanceId.remove()
                GuestInfoCache.clear(instanceId, packageName)
                AntiDetection.cleanup(instanceId)
                DeviceFingerprint.cleanup(instanceId)
                CoreHooks.instanceConfigs.remove(instanceId)
                hookedPackages.remove(instanceId to packageName)
            } catch (_: Throwable) {}
        }
    }

    private fun installGmsUnavailableHooks(classLoader: ClassLoader): Boolean {
        val availabilityClasses = listOf(
            "com.google.android.gms.common.GoogleApiAvailability",
            "com.google.android.gms.common.GoogleApiAvailabilityLight",
            "com.google.android.gms.common.GooglePlayServicesUtil"
        )
        var installed = 0
        val replacement = object : de.robv.android.xposed.XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any = 1
        }
        for (className in availabilityClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, classLoader)
                for (method in clazz.declaredMethods) {
                    if (method.returnType != Int::class.javaPrimitiveType) continue
                    if (!method.name.contains("GooglePlayServicesAvailable", ignoreCase = true)) continue
                    if (safeHook(method, replacement, "$className.${method.name}")) {
                        installed++
                    }
                }
            } catch (_: Throwable) {}
        }
        return installed > 0
    }

    private fun safeHook(method: Member?, hook: XC_MethodHook, label: String): Boolean {
        if (method == null) return false
        return try {
            XposedBridge.hookMethod(method, hook)
            hooksInstalled++
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun installSystemHooks() {
        try {
            hookBuildField("SERIAL", "unknown")
            hookBuildField("TAGS", "release-keys")
        } catch (_: Throwable) {}
    }

    private fun hookBuildField(fieldName: String, spoofedValue: String) {
        try {
            val buildClass = Class.forName("android.os.Build")
            val field = buildClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, spoofedValue)
        } catch (_: Throwable) {}
    }

    private fun hookActivityThread() {
        val method = findActivityThreadMethod("handleLaunchActivity")
        safeHook(method, CoreHooks.createActivityThreadHook(), "ActivityThread.handleLaunchActivity")
    }

    private fun hookActivityPerformCreate() {
        try {
            val method = Activity::class.java.getDeclaredMethod("performCreate", Bundle::class.java)
            safeHook(method, CoreHooks.createActivityPerformCreateHook(), "Activity.performCreate")
        } catch (_: Throwable) {}
    }

    private fun hookPackageManager(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        try {
            val appPmClass = Class.forName("android.app.ApplicationPackageManager")
            val getPackageInfo = appPmClass.getDeclaredMethod(
                "getPackageInfo", String::class.java, Int::class.javaPrimitiveType
            )
            safeHook(getPackageInfo, CoreHooks.createGetPackageInfoHook(), "ApplicationPackageManager.getPackageInfo")

            val getInstalledPackages = appPmClass.getDeclaredMethod(
                "getInstalledPackages", Int::class.javaPrimitiveType
            )
            safeHook(getInstalledPackages, CoreHooks.createGetInstalledPackagesHook(), "ApplicationPackageManager.getInstalledPackages")
        } catch (_: Throwable) {}
    }

    private fun hookGoogleSignIn(classLoader: ClassLoader) {
        try {
            val googleSignInClass = classLoader.loadClass("com.google.android.gms.auth.api.signin.GoogleSignIn")
            val getSignedInAccountFromIntent = googleSignInClass.getDeclaredMethod(
                "getSignedInAccountFromIntent", Intent::class.java
            )
            safeHook(getSignedInAccountFromIntent, CoreHooks.createGoogleSignInHook(), "GoogleSignIn.getSignedInAccountFromIntent")
        } catch (_: Throwable) {}
    }

    private fun hookSharedPreferences() {
        try {
            val contextImplClass = Class.forName("android.app.ContextImpl")
            val method = contextImplClass.getDeclaredMethod(
                "getSharedPreferences", String::class.java, Int::class.javaPrimitiveType
            )
            safeHook(method, CoreHooks.createSharedPreferencesHook(), "ContextImpl.getSharedPreferences")
        } catch (_: Throwable) {}
    }

    private fun hookFileConstructors() {
        try {
            val ctor1 = File::class.java.getDeclaredConstructor(String::class.java)
            safeHook(ctor1, CoreHooks.createFileConstructorHook(), "File(String)")
        } catch (_: Throwable) {}
    }

    private fun hookFileExists() {
        try {
            val method = File::class.java.getDeclaredMethod("exists")
            safeHook(method, CoreHooks.createFileExistsHook(), "File.exists")
        } catch (_: Throwable) {}
    }

    private fun hookSystemGetProperty() {
        try {
            val method = System::class.java.getDeclaredMethod("getProperty", String::class.java)
            safeHook(method, CoreHooks.createSystemGetPropertyHook(), "System.getProperty")
        } catch (_: Throwable) {}
    }

    private fun hookSystemPropertiesGet() {
        try {
            val sysPropsClass = Class.forName("android.os.SystemProperties")
            val method = sysPropsClass.getDeclaredMethod("get", String::class.java, String::class.java)
            safeHook(method, CoreHooks.createSystemPropertiesGetHook(), "SystemProperties.get")
        } catch (_: Throwable) {}
    }

    private fun hookGetStackTrace() {
        try {
            val method = Thread::class.java.getDeclaredMethod("getStackTrace")
            safeHook(method, CoreHooks.createGetStackTraceHook(), "Thread.getStackTrace")
        } catch (_: Throwable) {}
    }

    private fun hookClassForName() {
        try {
            val method = Class::class.java.getDeclaredMethod(
                "forName", String::class.java, Boolean::class.javaPrimitiveType, ClassLoader::class.java
            )
            safeHook(method, CoreHooks.createClassForNameHook(), "Class.forName")
        } catch (_: Throwable) {}
    }

    private fun hookProcMaps() {
        try {
            val method = java.io.FileInputStream::class.java.getDeclaredConstructor(String::class.java)
            safeHook(method, CoreHooks.createProcMapsHook(), "FileInputStream(String)")
        } catch (_: Throwable) {}
    }

    private fun hookFingerprintHooks() {
        try {
            val method = android.provider.Settings.Secure::class.java.getDeclaredMethod(
                "getString", android.content.ContentResolver::class.java, String::class.java
            )
            safeHook(method, CoreHooks.createAndroidIdHook(), "Settings.Secure.getString")
        } catch (_: Throwable) {}

        try {
            val tmClass = TelephonyManager::class.java
            val getDeviceId = tmClass.getDeclaredMethod("getDeviceId")
            safeHook(getDeviceId, CoreHooks.createTelephonyHook(), "TelephonyManager.getDeviceId")
        } catch (_: Throwable) {}
    }

    private fun hookNotifications() {
        try {
            val nmClass = android.app.NotificationManager::class.java
            val hook = CoreHooks.createNotificationHook()
            val notifyById = nmClass.getDeclaredMethod(
                "notify", Int::class.javaPrimitiveType, android.app.Notification::class.java
            )
            safeHook(notifyById, hook, "NotificationManager.notify(int,Notification)")
        } catch (_: Throwable) {}
    }

    private fun findActivityThreadMethod(name: String): Method? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            activityThreadClass.declaredMethods.firstOrNull { it.name == name }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveApkPath(dataPath: String, classLoader: ClassLoader): String? {
        val candidates = listOf(
            File(dataPath, "base.apk"),
            File(dataPath, "source.apk"),
            File(dataPath, "app.apk")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }?.absolutePath
    }

    fun reset() {
        CoreHooks.reset()
        hookedPackages.clear()
        initialized = false
        pineAvailable = false
    }
}
