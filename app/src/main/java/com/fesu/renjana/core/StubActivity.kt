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

            pineAvailable = try {
                val pineClass = Class.forName("top.canyie.pine.Pine")
                try {
                    pineClass.getMethod("is64Bit").invoke(null)
                } catch (e: UnsatisfiedLinkError) {
                    RenjanaLog.w(TAG, "Pine native lib unavailable (x86?): ${e.message}")
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

            RenjanaLog.i(TAG, "Initializing Pine hook manager (non-root mode)")

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
            RenjanaLog.i(TAG, "Hidden API restrictions unsealed")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Hidden API unseal failed: ${e.message}")
        }
    }

    fun installGuestHooks(
        packageName: String,
        classLoader: ClassLoader,
        instanceId: String,
        dataPath: String,
        apkPath: String? = null,
        instance: Instance? = null
    ): Boolean {
        if (!pineAvailable) {
            RenjanaLog.w(TAG, "Pine not available, cannot install guest hooks")
            return false
        }

        CoreHooks.classLoaderToInstanceId[classLoader] = instanceId

        synchronized(hookedPackages) {
            if (hookedPackages.contains(instanceId to packageName)) {
                CoreHooks.classLoaderToInstanceId[classLoader] = instanceId
                RenjanaLog.d(TAG, "Package $packageName already hooked; registered new classloader for instance $instanceId")
                return true
            }

            RenjanaLog.i(TAG, "Installing Pine guest hooks for: $packageName (instance=$instanceId)")

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

                try {
                    DeviceFingerprint.initialize(RenjanaApplication.get())
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "DeviceFingerprint.initialize failed (non-fatal): ${e.message}")
                }

                val config = instance?.config
                if (config == null || config.enableAntiDetection) {
                    AntiDetection.initialize(instanceId, dataPath)
                    FridaDetectionEvasion.initialize(instanceId)
                }
                if (config != null && config.enableSafetyNetBypass) {
                    SafetyNetBypass.initialize(instanceId)
                    PlayIntegrityBypass.initialize(instanceId, packageName)
                }

                hooksInstalled = 0

                val resolvedApkPath = apkPath ?: resolveApkPath(dataPath, classLoader)
                if (resolvedApkPath != null) {
                    GuestInfoCache.cache(instanceId, packageName, resolvedApkPath)
                    SignatureSpoof.storeOriginalSignatures(packageName, resolvedApkPath)
                } else {
                    RenjanaLog.w(TAG, "Could not resolve APK path for $packageName; GuestInfoCache skipped")
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

                // Avoid hooking ART core reflection intrinsics on Android 14/15 (causes SIGSEGV)
                if (Build.VERSION.SDK_INT < 34) {
                    hookGetStackTrace()
                    hookClassForName()
                }
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
                    try {
                        val contextImplClass = Class.forName("android.app.ContextImpl")
                        for (m in contextImplClass.declaredMethods) {
                            if (!m.name.startsWith("bindService")) continue
                            if (!m.parameterTypes.contains(Intent::class.java)) continue
                            if (m.returnType != Boolean::class.javaPrimitiveType) continue
                            if (safeHook(m, CoreHooks.createBindServiceGmsBlockHook(), "ContextImpl.${m.name}")) {
                                hooksInstalled++
                            }
                        }
                    } catch (e: Throwable) {
                        RenjanaLog.w(TAG, "Failed to hook bindService: ${e.message}")
                    }
                }

                if (hooksInstalled == 0) {
                    RenjanaLog.w(TAG, "Zero hooks installed despite Pine available")
                    return false
                }

                hookedPackages.add(instanceId to packageName)
                RenjanaLog.i(TAG, "Pine guest hooks installed for $packageName")
                return hooksInstalled > 0
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to install Pine guest hooks for $packageName: ${e.message}")
                return false
            }
        }
    }

    fun uninstallGuestHooks(packageName: String, instanceId: String) {
        synchronized(hookedPackages) {
            if (!hookedPackages.contains(instanceId to packageName)) return

            RenjanaLog.i(TAG, "Uninstalling Pine guest hooks for: $packageName")

            try {
                CoreHooks.unregisterPackage(packageName)
                CoreHooks.currentInstanceId.remove()
                GuestInfoCache.clear(instanceId, packageName)
                AntiDetection.cleanup(instanceId)
                DeviceFingerprint.cleanup(instanceId)
                CoreHooks.instanceConfigs.remove(instanceId)
                hookedPackages.remove(instanceId to packageName)
                RenjanaLog.i(TAG, "Pine guest hooks uninstalled for $packageName")
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to uninstall Pine guest hooks: ${e.message}")
            }
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
        RenjanaLog.i(TAG, "GMS marked unavailable for guest ($installed availability hooks)")
        return installed > 0
    }

    private fun safeHook(method: Member?, hook: XC_MethodHook, label: String): Boolean {
        if (method == null) {
            RenjanaLog.w(TAG, "Target method not found, skipping hook: $label")
            return false
        }
        return try {
            XposedBridge.hookMethod(method, hook)
            hooksInstalled++
            RenjanaLog.d(TAG, "Hook installed: $label")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook $label: ${e.message}")
            false
        }
    }

    private fun installSystemHooks() {
        RenjanaLog.d(TAG, "Installing Pine system hooks")
        try {
            hookBuildField("SERIAL", "unknown")
            hookBuildField("TAGS", "release-keys")
            RenjanaLog.i(TAG, "Pine system hooks installed successfully")
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to install Pine system hooks: ${e.message}")
        }
    }

    private fun hookBuildField(fieldName: String, spoofedValue: String) {
        try {
            val buildClass = Class.forName("android.os.Build")
            val field = buildClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, spoofedValue)
            RenjanaLog.d(TAG, "Spoofed Build.$fieldName = $spoofedValue via Pine")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to spoof Build.$fieldName: ${e.message}")
        }
    }

    private fun hookActivityThread() {
        val method = findActivityThreadMethod("handleLaunchActivity")
        safeHook(method, CoreHooks.createActivityThreadHook(), "ActivityThread.handleLaunchActivity")
    }

    private fun hookActivityPerformCreate() {
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "performCreate", Bundle::class.java
            )
            safeHook(method, CoreHooks.createActivityPerformCreateHook(), "Activity.performCreate")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Activity.performCreate not found: ${e.message}")
        }
    }

    private fun hookPackageManager(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        try {
            val appPmClass = Class.forName("android.app.ApplicationPackageManager")

            val getPackageInfo = appPmClass.getDeclaredMethod(
                "getPackageInfo", String::class.java, Int::class.javaPrimitiveType
            )
            safeHook(getPackageInfo, CoreHooks.createGetPackageInfoHook(),
                "ApplicationPackageManager.getPackageInfo")

            try {
                val versionedPackageClass = Class.forName("android.content.pm.VersionedPackage")
                val getPackageInfoVersioned = appPmClass.getDeclaredMethod(
                    "getPackageInfo", versionedPackageClass, Int::class.javaPrimitiveType
                )
                safeHook(getPackageInfoVersioned, CoreHooks.createGetPackageInfoVersionedHook(),
                    "ApplicationPackageManager.getPackageInfo(VersionedPackage)")
            } catch (_: Throwable) {}

            try {
                val packageInfoFlagsClass = Class.forName("android.content.pm.PackageManager\$PackageInfoFlags")
                val getPackageInfoFlags = appPmClass.getDeclaredMethod(
                    "getPackageInfo", String::class.java, packageInfoFlagsClass
                )
                safeHook(getPackageInfoFlags, CoreHooks.createGetPackageInfoFlagsHook(),
                    "ApplicationPackageManager.getPackageInfo(PackageInfoFlags)")
            } catch (_: Throwable) {}

            val getInstalledPackages = appPmClass.getDeclaredMethod(
                "getInstalledPackages", Int::class.javaPrimitiveType
            )
            safeHook(getInstalledPackages, CoreHooks.createGetInstalledPackagesHook(),
                "ApplicationPackageManager.getInstalledPackages")

            for (m in appPmClass.declaredMethods) {
                when (m.name) {
                    "getApplicationInfo", "getApplicationInfoAsUser" ->
                        safeHook(m, CoreHooks.createGetApplicationInfoHook(),
                            "ApplicationPackageManager.${m.name}")
                    "getPackagesForUid" ->
                        safeHook(m, CoreHooks.createGetPackagesForUidHook(),
                            "ApplicationPackageManager.getPackagesForUid")
                }
            }
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook PackageManager: ${e.message}")
        }
    }

    private fun hookGoogleSignIn(classLoader: ClassLoader) {
        try {
            val googleSignInClass = classLoader.loadClass(
                "com.google.android.gms.auth.api.signin.GoogleSignIn"
            )
            val getSignedInAccountFromIntent = googleSignInClass.getDeclaredMethod(
                "getSignedInAccountFromIntent", Intent::class.java
            )
            safeHook(getSignedInAccountFromIntent, CoreHooks.createGoogleSignInHook(),
                "GoogleSignIn.getSignedInAccountFromIntent")
        } catch (_: Throwable) {}

        try {
            val accountClass = classLoader.loadClass(
                "com.google.android.gms.auth.api.signin.GoogleSignInAccount"
            )
            val constructors = accountClass.declaredConstructors
            if (constructors.isNotEmpty()) {
                safeHook(constructors[0], CoreHooks.createGoogleSignInAccountHook(),
                    "GoogleSignInAccount.<init>")
            }
        } catch (_: Throwable) {}
    }

    private fun hookSharedPreferences() {
        try {
            val contextImplClass = Class.forName("android.app.ContextImpl")
            val method = contextImplClass.getDeclaredMethod(
                "getSharedPreferences", String::class.java, Int::class.javaPrimitiveType
            )
            safeHook(method, CoreHooks.createSharedPreferencesHook(),
                "ContextImpl.getSharedPreferences")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook getSharedPreferences: ${e.message}")
        }
    }

    private fun hookFileConstructors() {
        try {
            val ctor1 = File::class.java.getDeclaredConstructor(String::class.java)
            safeHook(ctor1, CoreHooks.createFileConstructorHook(), "File(String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook File(String): ${e.message}")
        }

        try {
            val ctor2 = File::class.java.getDeclaredConstructor(
                String::class.java, String::class.java
            )
            safeHook(ctor2, CoreHooks.createFileConstructor2Hook(), "File(String, String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook File(String, String): ${e.message}")
        }
    }

    private fun hookFileExists() {
        try {
            val method = File::class.java.getDeclaredMethod("exists")
            safeHook(method, CoreHooks.createFileExistsHook(), "File.exists")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook File.exists: ${e.message}")
        }
    }

    private fun hookSystemGetProperty() {
        try {
            val method = System::class.java.getDeclaredMethod(
                "getProperty", String::class.java
            )
            safeHook(method, CoreHooks.createSystemGetPropertyHook(), "System.getProperty")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook System.getProperty: ${e.message}")
        }
    }

    private fun hookSystemPropertiesGet() {
        try {
            val sysPropsClass = Class.forName("android.os.SystemProperties")
            val method = sysPropsClass.getDeclaredMethod(
                "get", String::class.java, String::class.java
            )
            safeHook(method, CoreHooks.createSystemPropertiesGetHook(),
                "SystemProperties.get")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook SystemProperties.get: ${e.message}")
        }
    }

    private fun hookGetStackTrace() {
        try {
            val method = Thread::class.java.getDeclaredMethod("getStackTrace")
            safeHook(method, CoreHooks.createGetStackTraceHook(), "Thread.getStackTrace")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook Thread.getStackTrace: ${e.message}")
        }
    }

    private fun hookClassForName() {
        try {
            val method = Class::class.java.getDeclaredMethod(
                "forName", String::class.java,
                Boolean::class.javaPrimitiveType, ClassLoader::class.java
            )
            safeHook(method, CoreHooks.createClassForNameHook(), "Class.forName")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook Class.forName: ${e.message}")
        }
    }

    private fun hookProcMaps() {
        try {
            val method = java.io.FileInputStream::class.java.getDeclaredConstructor(
                String::class.java
            )
            safeHook(method, CoreHooks.createProcMapsHook(), "FileInputStream(String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook FileInputStream(String) for /proc/maps: ${e.message}")
        }

        try {
            val method = java.io.FileReader::class.java.getDeclaredConstructor(
                String::class.java
            )
            safeHook(method, CoreHooks.createProcMapsHook(), "FileReader(String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook FileReader(String) for /proc/maps: ${e.message}")
        }
    }

    private fun hookFingerprintHooks() {
        try {
            val method = android.provider.Settings.Secure::class.java.getDeclaredMethod(
                "getString",
                android.content.ContentResolver::class.java,
                String::class.java
            )
            safeHook(method, CoreHooks.createAndroidIdHook(), "Settings.Secure.getString")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook Settings.Secure.getString: ${e.message}")
        }

        try {
            val tmClass = TelephonyManager::class.java

            val getDeviceId = tmClass.getDeclaredMethod("getDeviceId")
            safeHook(getDeviceId, CoreHooks.createTelephonyHook(), "TelephonyManager.getDeviceId")

            val getImei = tmClass.getDeclaredMethod("getImei", Int::class.javaPrimitiveType)
            safeHook(getImei, CoreHooks.createTelephonyHook(), "TelephonyManager.getImei")

            val getSubscriberId = tmClass.getDeclaredMethod("getSubscriberId")
            safeHook(getSubscriberId, CoreHooks.createTelephonyHook(), "TelephonyManager.getSubscriberId")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook TelephonyManager: ${e.message}")
        }

        // Skip Field.get hook on Android 14/15 because hooking java.lang.reflect.Field.get
        // crashes ART 15; DeviceFingerprint.applyDeviceSpoofToProcess already mutates Build fields.
        if (Build.VERSION.SDK_INT < 34) {
            try {
                val method = java.lang.reflect.Field::class.java.getDeclaredMethod(
                    "get", Any::class.java
                )
                safeHook(method, CoreHooks.createBuildFieldHook(), "Field.get")
            } catch (e: Throwable) {
                RenjanaLog.w(TAG, "Failed to hook Field.get for Build fields: ${e.message}")
            }
        }
    }

    private fun hookNotifications() {
        try {
            val nmClass = android.app.NotificationManager::class.java
            val hook = CoreHooks.createNotificationHook()

            val notifyById = nmClass.getDeclaredMethod(
                "notify",
                Int::class.javaPrimitiveType,
                android.app.Notification::class.java
            )
            safeHook(notifyById, hook, "NotificationManager.notify(int,Notification)")

            val notifyByTag = nmClass.getDeclaredMethod(
                "notify",
                String::class.java,
                Int::class.javaPrimitiveType,
                android.app.Notification::class.java
            )
            safeHook(notifyByTag, hook, "NotificationManager.notify(String,int,Notification)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook NotificationManager.notify: ${e.message}")
        }
    }

    private fun findActivityThreadMethod(name: String): Method? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            for (method in activityThreadClass.declaredMethods) {
                if (method.name == name) {
                    return method
                }
            }
            null
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "ActivityThread.$name not found: ${e.message}")
            null
        }
    }

    private fun resolveApkPath(dataPath: String, classLoader: ClassLoader): String? {
        val candidates = listOf(
            File(dataPath, "base.apk"),
            File(dataPath, "source.apk"),
            File(dataPath, "app.apk")
        )
        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile) {
                return candidate.absolutePath
            }
        }

        val dataFile = File(dataPath)
        if (dataFile.isFile && dataFile.name.endsWith(".apk")) {
            return dataFile.absolutePath
        }

        try {
            val dexClField = classLoader.javaClass.getDeclaredField("dexClassLoader")
            dexClField.isAccessible = true
            val dexCl = dexClField.get(classLoader) as? DexClassLoader
            if (dexCl != null) {
                val baseClClass = Class.forName("dalvik.system.BaseDexClassLoader")
                val pathListField = baseClClass.getDeclaredField("pathList")
                pathListField.isAccessible = true
                val pathList = pathListField.get(dexCl) ?: return null

                val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
                dexElementsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val dexElements = dexElementsField.get(pathList) as? Array<Any> ?: return null

                for (element in dexElements) {
                    try {
                        val dexFileField = element.javaClass.getDeclaredField("dexFile")
                        dexFileField.isAccessible = true
                        val dexFile = dexFileField.get(element) ?: continue
                        val fileNameField = dexFile.javaClass.getDeclaredField("mFileName")
                        fileNameField.isAccessible = true
                        val fileName = fileNameField.get(dexFile) as? String ?: continue
                        if (fileName.endsWith(".apk")) {
                            return fileName
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    @Deprecated("Use the classLoader passed to installGuestHooks() instead.")
    fun createGuestClassLoader(packageName: String, dataPath: String): ClassLoader? {
        return try {
            val dexPath = File(dataPath, "dex")
            if (!dexPath.exists() || !dexPath.isDirectory) return null

            val dexFiles = dexPath.listFiles { file -> file.extension == "dex" }
            if (dexFiles == null || dexFiles.isEmpty()) return null

            dexFiles.forEach { try { it.setReadOnly() } catch (_: Throwable) {} }
            val dexPathStr = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
            val optimizedDir = File(dataPath, "dex_opt").apply { if (!exists()) mkdirs() }

            DexClassLoader(
                dexPathStr,
                optimizedDir.absolutePath,
                null,
                ClassLoader.getSystemClassLoader()
            )
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to create guest ClassLoader for $packageName: ${e.message}")
            null
        }
    }

    fun reset() {
        CoreHooks.reset()
        hookedPackages.clear()
        initialized = false
        pineAvailable = false
        RenjanaLog.i(TAG, "PineHookManager state reset")
    }
}
