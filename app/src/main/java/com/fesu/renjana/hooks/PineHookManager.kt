package com.fesu.renjana.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Toast
import com.fesu.renjana.hooks.CoreHooks
import com.fesu.renjana.hooks.PineHookManager
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.virtual.VirtualContext
import kotlinx.coroutines.launch
import java.lang.reflect.Method

abstract class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"

        const val EXTRA_GUEST_ACTIVITY_CLASS = "stub_guest_class"
        const val EXTRA_INSTANCE_ID = "stub_instance_id"
        const val EXTRA_APK_PATH = "stub_apk_path"
        const val EXTRA_DATA_PATH = "stub_data_path"
        const val EXTRA_PACKAGE_NAME = "stub_package_name"
        const val EXTRA_APP_NAME = "stub_app_name"
        const val EXTRA_ACCOUNT_ID = "stub_account_id"
        const val EXTRA_ENABLE_GMS = "stub_enable_gms"
        const val EXTRA_ENABLE_FINGERPRINT = "stub_enable_fingerprint"
        const val EXTRA_SPOOF_SIGNATURE = "stub_spoof_signature"
        const val EXTRA_ENABLE_ANTI_DETECTION = "stub_enable_anti_detection"
        const val EXTRA_GUEST_ORIGINAL_INTENT = "stub_guest_intent"
        const val EXTRA_LAUNCH_MODE = "stub_launch_mode"
        const val EXTRA_REQUEST_CODE = "stub_request_code"
        const val EXTRA_STUB_INDEX = "stub_index"
        const val ACTION_FINISH_GUEST = "com.fesu.renjana.action.FINISH_GUEST"
    }

    abstract fun getStubIndex(): Int

    private var guestActivity: Activity? = null
    private var virtualClassLoader: VirtualClassLoader? = null
    private var guestClassName: String = ""
    private var instanceId: String = ""
    private var guestPackageName: String = ""
    private var guestApplication: Application? = null
    private var guestActivityInfo: android.content.pm.ActivityInfo? = null
    private var effectiveGuestTheme: Int = 0
    private var virtualContext: VirtualContext? = null

    private var onCreateMethod: Method? = null
    private var onStartMethod: Method? = null
    private var onResumeMethod: Method? = null
    private var onPauseMethod: Method? = null
    private var onStopMethod: Method? = null
    private var onDestroyMethod: Method? = null
    private var onRestartMethod: Method? = null
    private var onNewIntentMethod: Method? = null
    private var onActivityResultMethod: Method? = null
    private var onSaveInstanceStateMethod: Method? = null
    private var onRestoreInstanceStateMethod: Method? = null
    private var onBackPressedMethod: Method? = null
    private var onKeyDownMethod: Method? = null
    private var onKeyUpMethod: Method? = null
    private var onTouchEventMethod: Method? = null
    private var onCreateOptionsMenuMethod: Method? = null
    private var onOptionsItemSelectedMethod: Method? = null
    private var onRequestPermissionsResultMethod: Method? = null
    private var onConfigurationChangedMethod: Method? = null
    private var onWindowFocusChangedMethod: Method? = null

    override fun attachBaseContext(newBase: Context?) {
        try {
            androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        } catch (_: Throwable) {}

        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID)
        val dataPath = intent?.getStringExtra(EXTRA_DATA_PATH)
            ?: (instanceId?.let { ActivityStubManager.getDataPathForInstance(it) })
        if (instanceId != null && dataPath != null && newBase != null) {
            try {
                super.attachBaseContext(VirtualContext(newBase, dataPath))
                RenjanaLog.d(TAG, "attachBaseContext: wrapped with VirtualContext for instance $instanceId")
                return
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "attachBaseContext: failed to wrap context: ${e.message}")
            }
        }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_FINISH_GUEST) {
            RenjanaLog.i(TAG, "StubActivity[${getStubIndex()}] received FINISH_GUEST — finishing")
            finish()
            return
        }

        try {
            androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(false)
        } catch (_: Throwable) {}

        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
        guestPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        guestClassName = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS).orEmpty()
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        val dataPath = intent.getStringExtra(EXTRA_DATA_PATH).orEmpty().ifEmpty {
            ActivityStubManager.getDataPathForInstance(instanceId).orEmpty()
        }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty().ifEmpty {
            ActivityStubManager.getCachedInstance(instanceId)?.packageName.orEmpty()
        }
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val enableGms = intent.getBooleanExtra(EXTRA_ENABLE_GMS, false)
        val enableFingerprint = intent.getBooleanExtra(EXTRA_ENABLE_FINGERPRINT, true)
        val spoofSignature = intent.getBooleanExtra(EXTRA_SPOOF_SIGNATURE, true)
        val enableAntiDetection = intent.getBooleanExtra(EXTRA_ENABLE_ANTI_DETECTION, true)

        if (instanceId.isEmpty() || guestClassName.isEmpty() || apkPath.isEmpty()) {
            RenjanaLog.e(TAG, "Missing required extras: instanceId=$instanceId, guest=$guestClassName, apk=$apkPath")
            finish()
            return
        }

        try {
            androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        } catch (_: Throwable) {}

        RenjanaLog.i(TAG, "StubActivity[${getStubIndex()}] onCreate (pid=${android.os.Process.myPid()}) → launching guest $guestClassName for instance $instanceId")

        ActivityStubManager.onStubOccupied(instanceId, getStubIndex(), guestClassName)

        try {
            CoreHooks.currentInstanceId.set(instanceId)

            val baseDataPath = dataPath.ifEmpty {
                "${filesDir.parent}/instances/$instanceId"
            }
            val effectiveDataPath = if (packageName.isNotBlank()) {
                "$baseDataPath/packages/$packageName"
            } else {
                baseDataPath
            }

            val resolvedPackageName = packageName.ifEmpty { null }
            virtualContext = VirtualContext(this, effectiveDataPath, resolvedPackageName)

            val optimizedDir = java.io.File(effectiveDataPath, "dex_opt").apply { mkdirs() }
            val guestNativeLibDir = resolveGuestNativeLibDir(apkPath, packageName, effectiveDataPath)
            virtualClassLoader = VirtualClassLoader(
                apkPath = apkPath,
                instanceId = instanceId,
                optimizedDir = optimizedDir,
                guestPackageName = packageName,
                nativeLibDir = guestNativeLibDir,
                parent = classLoader
            )

            try {
                virtualContext?.guestResources = virtualClassLoader!!.getResources(this)
            } catch (_: Throwable) {}

            val launchInfo = ApkLoader(this).getGuestLaunchInfo(apkPath, guestClassName)
            guestActivityInfo = launchInfo.activityInfo
            effectiveGuestTheme = launchInfo.activityInfo?.theme?.takeIf { it != 0 }
                ?: launchInfo.applicationTheme.takeIf { it != 0 }
                ?: 0

            val baseInstance = ActivityStubManager.getCachedInstance(instanceId)
            val effectivePackage = packageName.ifEmpty { launchInfo.packageName ?: baseInstance?.packageName ?: "com.example.guest" }
            val effectiveAppName = appName.ifEmpty { baseInstance?.appName ?: "Guest App" }
            val instance = baseInstance?.copy(
                packageName = effectivePackage,
                appName = effectiveAppName,
                dataPath = effectiveDataPath
            ) ?: Instance(
                id = instanceId,
                packageName = effectivePackage,
                appName = effectiveAppName,
                versionName = "1.0",
                versionCode = 1,
                apkPath = apkPath,
                iconPath = null,
                accountId = accountId,
                dataPath = effectiveDataPath,
                createdAt = System.currentTimeMillis(),
                lastUsed = System.currentTimeMillis(),
                isActive = true,
                config = com.fesu.renjana.models.InstanceConfig(
                    enableGms = enableGms,
                    enableFingerprint = enableFingerprint,
                    spoofSignature = spoofSignature,
                    enableAntiDetection = enableAntiDetection
                )
            )

            if (PineHookManager.isAvailable()) {
                PineHookManager.installGuestHooks(
                    effectivePackage,
                    virtualClassLoader!!,
                    instanceId,
                    effectiveDataPath,
                    apkPath,
                    instance = instance
                )
            }

            val assignedAccountId = accountId
            if (instance.config.enableGms && assignedAccountId != null) {
                try {
                    com.fesu.renjana.RenjanaApplication.get().applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.fesu.renjana.RenjanaApplication.get().googleSignInVirtualizer
                            .assignAccountToInstance(instanceId, assignedAccountId)
                    }
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "GMS account assignment failed: ${e.message}")
                }
            }

            if (instance.config.enableFingerprint) {
                com.fesu.renjana.hooks.DeviceFingerprint.applyDeviceSpoofToProcess(instance.config, instanceId)
            }

            bypassVectorDrawableCheck()

            guestApplication = createAndAttachGuestApplication(launchInfo.applicationClass)
            virtualContext?.guestApplication = guestApplication
            runGuestProviders(launchInfo.providers)
            guestApplication?.let { app ->
                try {
                    app.onCreate()
                    RenjanaLog.i(TAG, "Guest Application started: ${launchInfo.applicationClass}")
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "Guest Application.onCreate failed (non-fatal): ${e.message}")
                }
            }

            val guestClass = virtualClassLoader!!.loadGuestClass(guestClassName)
            guestActivity = guestClass.getDeclaredConstructor().newInstance() as Activity

            cacheLifecycleMethods(guestClass)
            attachGuestToHost()

            guestActivity?.let { guest ->
                try {
                    com.fesu.renjana.hooks.ActivityStarterHook.cacheInstanceInfo(
                        instanceId, apkPath,
                        packageName.ifEmpty { launchInfo.packageName ?: "com.example.guest" }
                    )
                    com.fesu.renjana.hooks.ActivityStarterHook.installForActivity(this, guest, instanceId)
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "ActivityStarterHook install failed (non-fatal): ${e.message}")
                }
            }

            val guestIntent = intent.getParcelableExtra<Intent>(EXTRA_GUEST_ORIGINAL_INTENT)
            if (guestIntent != null) {
                setGuestIntent(guestIntent)
            }

            reportStateToService(InstanceNotificationManager.ACTION_OPEN_INSTANCE)

            if (onCreateMethod != null) {
                onCreateMethod!!.invoke(guestActivity, savedInstanceState)
            } else {
                RenjanaLog.w(TAG, "Guest $guestClassName has no onCreate method")
            }

        } catch (e: Throwable) {
            val cause = e.cause ?: e
            RenjanaLog.e(TAG, "Failed to launch guest $guestClassName: ${cause.message}", cause)
            try {
                Toast.makeText(this, "Launch error: ${cause.javaClass.simpleName}: ${cause.message}", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            finish()
        }
    }

    private fun resolveGuestNativeLibDir(apkPath: String, packageName: String, dataPath: String): String? {
        try {
            if (packageName.isNotBlank()) {
                val installed = packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
                if (!installed.isNullOrBlank()) {
                    val dir = java.io.File(installed)
                    if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
                        return installed
                    }
                }
            }
        } catch (_: Throwable) {}

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libDir = java.io.File(dataPath, "lib").apply { mkdirs() }
        var sawAny = false
        for (apk in VirtualClassLoader.resolveAllApkPaths(apkPath)) {
            try {
                java.util.zip.ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if (!entry.name.startsWith("lib/$abi/") || !entry.name.endsWith(".so")) continue
                        sawAny = true
                        val out = java.io.File(libDir, java.io.File(entry.name).name)
                        if (out.exists() && out.length() == entry.size) continue
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(out).use { output -> input.copyTo(output) }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        RenjanaLog.i(TAG, if (sawAny) "Guest native lib dir: ${libDir.absolutePath}" else "No guest native libs found for abi=$abi")
        return if (sawAny) libDir.absolutePath else null
    }

    private fun createAndAttachGuestApplication(applicationClass: String?): Application? {
        val className = applicationClass?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val clazz = virtualClassLoader!!.loadGuestClass(className)
            val app = clazz.getDeclaredConstructor().newInstance() as Application

            val attachMethod = Application::class.java.declaredMethods.firstOrNull {
                it.name == "attach" && it.parameterCount == 1 &&
                    Context::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: throw NoSuchMethodException("Application.attach(Context) not found")
            attachMethod.isAccessible = true
            attachMethod.invoke(app, virtualContext)
            RenjanaLog.i(TAG, "Guest Application attached: $className")
            app
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Guest Application could not start (continuing): ${e.message}", e)
            null
        }
    }

    private fun runGuestProviders(providers: List<android.content.pm.ProviderInfo>) {
        val context = virtualContext ?: return
        for (providerInfo in providers) {
            try {
                val clazz = virtualClassLoader!!.loadGuestClass(providerInfo.name)
                val provider = clazz.getDeclaredConstructor().newInstance() as android.content.ContentProvider
                provider.attachInfo(context, providerInfo)
                RenjanaLog.d(TAG, "Guest provider started: ${providerInfo.name}")
            } catch (e: Throwable) {
                RenjanaLog.w(TAG, "Guest provider failed (non-fatal): ${providerInfo.name}: ${e.message}", e)
            }
        }
    }

    private fun attachGuestToHost() {
        val guest = guestActivity ?: return
        try {
            attachGuestToHost(guest, guestClassName)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest attach failed (non-fatal): ${e.message}")
            finish()
            return
        }
    }

    private fun attachGuestToHost(guest: Activity, guestClassName: String) {
        try {
            val resolvedInfo = guestActivityInfo ?: try {
                val actInfoField = Activity::class.java.getDeclaredField("mActivityInfo")
                actInfoField.isAccessible = true
                (actInfoField.get(this) as? android.content.pm.ActivityInfo)?.let { original ->
                    android.content.pm.ActivityInfo(original).apply { name = guestClassName }
                } ?: android.content.pm.ActivityInfo().apply {
                    packageName = this@StubActivity.packageName
                    name = guestClassName
                }
            } catch (_: Throwable) {
                android.content.pm.ActivityInfo().apply {
                    packageName = this@StubActivity.packageName
                    name = guestClassName
                }
            }

            val attachMethod = Activity::class.java.declaredMethods.firstOrNull { it.name == "attach" }
            if (attachMethod != null) {
                attachMethod.isAccessible = true
                val args = arrayOfNulls<Any>(attachMethod.parameterCount)
                for (i in attachMethod.parameterTypes.indices) {
                    val type = attachMethod.parameterTypes[i]
                    args[i] = when {
                        type == Application::class.java || type.name.contains("Application") ->
                            guestApplication ?: application
                        type.name.contains("ActivityThread") -> try {
                            val f = Activity::class.java.getDeclaredField("mMainThread")
                            f.isAccessible = true
                            f.get(this)
                        } catch (_: Throwable) { null }
                        type.name.contains("Instrumentation") -> try {
                            val f = Activity::class.java.getDeclaredField("mInstrumentation")
                            f.isAccessible = true
                            f.get(this)
                        } catch (_: Throwable) { null }
                        type == android.os.IBinder::class.java -> try {
                            val f = Activity::class.java.getDeclaredField("mToken")
                            f.isAccessible = true
                            f.get(this)
                        } catch (_: Throwable) { null }
                        type == Int::class.javaPrimitiveType -> 0
                        type == Long::class.javaPrimitiveType -> 0L
                        type == Boolean::class.javaPrimitiveType -> false
                        type == Intent::class.java -> intent
                        type == android.content.pm.ActivityInfo::class.java -> resolvedInfo
                        type == Activity::class.java -> null
                        type == CharSequence::class.java -> title
                        type == android.content.res.Configuration::class.java -> resources.configuration
                        type == android.view.Window::class.java -> null
                        Context::class.java.isAssignableFrom(type) -> virtualContext ?: this
                        else -> null
                    }
                }
                attachMethod.invoke(guest, *args)
                RenjanaLog.i(TAG, "Successfully invoked Activity.attach on $guestClassName")
            }

            val effectiveApp = guestApplication ?: application
            var curClass: Class<*>? = guest.javaClass
            while (curClass != null && curClass != Any::class.java) {
                val fieldsToSet = listOf(
                    "mBase" to (virtualContext ?: this),
                    "mActivityInfo" to resolvedInfo,
                    "mApplication" to effectiveApp,
                    "mIntent" to intent,
                    "mWindowManager" to windowManager,
                    "mWindow" to window,
                    "mUiThread" to Thread.currentThread()
                )
                for ((fieldName, value) in fieldsToSet) {
                    try {
                        val f = curClass.getDeclaredField(fieldName)
                        f.isAccessible = true
                        f.set(guest, value)
                    } catch (_: Throwable) {}
                }
                curClass = curClass.superclass
            }

            if (PineHookManager.isAvailable()) {
                try {
                    val getAppMethod = Activity::class.java.getDeclaredMethod("getApplication")
                    top.canyie.pine.Pine.hook(getAppMethod, object : top.canyie.pine.callback.MethodReplacement() {
                        override fun replaceCall(callFrame: top.canyie.pine.Pine.CallFrame?): Any? = effectiveApp
                    })
                    RenjanaLog.i(TAG, "Pine hooked Activity.getApplication()")
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "Failed to Pine hook getApplication: ${e.message}")
                }

                try {
                    val pmClass = packageManager.javaClass
                    val safeInfo = resolvedInfo
                    val targetGuestClass = guestClassName
                    for (m in pmClass.declaredMethods.filter { it.name == "getActivityInfo" }) {
                        try {
                            top.canyie.pine.Pine.hook(m, object : top.canyie.pine.callback.MethodHook() {
                                override fun beforeCall(callFrame: top.canyie.pine.Pine.CallFrame?) {
                                    val comp = callFrame?.args?.getOrNull(0) as? android.content.ComponentName
                                    if (comp != null && (comp.className == targetGuestClass || comp.className.contains(targetGuestClass))) {
                                        callFrame.result = safeInfo
                                    }
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                    RenjanaLog.i(TAG, "Pine hooked PackageManager.getActivityInfo for $guestClassName")
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "Failed to Pine hook getActivityInfo: ${e.message}")
                }
            }

            try {
                val compField = Activity::class.java.getDeclaredField("mComponent")
                compField.isAccessible = true
                compField.set(guest, this.componentName)
            } catch (_: Throwable) {}

            try {
                val guestRes = virtualClassLoader!!.getResources(this)
                val resField = android.view.ContextThemeWrapper::class.java.getDeclaredField("mResources")
                resField.isAccessible = true
                resField.set(guest, guestRes)
            } catch (_: Throwable) {}

            try {
                val themeField = android.view.ContextThemeWrapper::class.java.getDeclaredField("mTheme")
                themeField.isAccessible = true
                themeField.set(guest, this.theme)
            } catch (_: Throwable) {}

            if (effectiveGuestTheme != 0) {
                try {
                    val themeResField = android.view.ContextThemeWrapper::class.java.getDeclaredField("mThemeResource")
                    themeResField.isAccessible = true
                    themeResField.setInt(guest, effectiveGuestTheme)
                } catch (_: Throwable) {}
            }

            RenjanaLog.d(TAG, "Guest context injection complete for $guestClassName")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest context injection partial failure: ${e.message}")
        }
    }

    private fun bypassVectorDrawableCheck() {
        try {
            androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        } catch (_: Throwable) {}

        virtualClassLoader?.let { vcl ->
            try {
                val guestAppCompatDelegate = vcl.loadGuestClass("androidx.appcompat.app.AppCompatDelegate")
                val setMethod = guestAppCompatDelegate.getMethod("setCompatVectorFromResourcesEnabled", java.lang.Boolean.TYPE)
                setMethod.invoke(null, true)
            } catch (_: Throwable) {}
        }

        listOfNotNull(
            runCatching { Class.forName("androidx.appcompat.widget.ResourceManagerInternal") }.getOrNull(),
            runCatching { virtualClassLoader?.loadGuestClass("androidx.appcompat.widget.ResourceManagerInternal") }.getOrNull()
        ).forEach { rmiClass ->
            try {
                val rmiInstance = try {
                    val getMethod = rmiClass.getDeclaredMethod("get")
                    getMethod.isAccessible = true
                    getMethod.invoke(null)
                } catch (_: Throwable) {
                    rmiClass.declaredMethods.firstOrNull {
                        java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == rmiClass && it.parameterCount == 0
                    }?.let { m ->
                        m.isAccessible = true
                        m.invoke(null)
                    }
                }

                if (rmiInstance != null) {
                    rmiClass.declaredFields.filter { it.type == java.lang.Boolean.TYPE }.forEach { field ->
                        try {
                            field.isAccessible = true
                            field.setBoolean(rmiInstance, true)
                        } catch (_: Throwable) {}
                    }
                }

                // Skip Pine hooking ResourceManagerInternal on Android 14/15 to avoid ART crashes
                if (PineHookManager.isAvailable() && Build.VERSION.SDK_INT < 34) {
                    rmiClass.declaredMethods.filter {
                        it.parameterCount == 1 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        (it.returnType == java.lang.Void.TYPE || it.returnType == Void.TYPE)
                    }.forEach { method ->
                        try {
                            top.canyie.pine.Pine.hook(method, object : top.canyie.pine.callback.MethodReplacement() {
                                override fun replaceCall(callFrame: top.canyie.pine.Pine.CallFrame?): Any? = null
                            })
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun cacheLifecycleMethods(guestClass: Class<*>) {
        onCreateMethod = safeMethod(guestClass, "onCreate", Bundle::class.java)
        onStartMethod = safeMethod(guestClass, "onStart")
        onResumeMethod = safeMethod(guestClass, "onResume")
        onPauseMethod = safeMethod(guestClass, "onPause")
        onStopMethod = safeMethod(guestClass, "onStop")
        onDestroyMethod = safeMethod(guestClass, "onDestroy")
        onRestartMethod = safeMethod(guestClass, "onRestart")
        onNewIntentMethod = safeMethod(guestClass, "onNewIntent", Intent::class.java)
        onActivityResultMethod = safeMethod(
            guestClass, "onActivityResult",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java
        )
        onSaveInstanceStateMethod = safeMethod(guestClass, "onSaveInstanceState", Bundle::class.java)
        onRestoreInstanceStateMethod = safeMethod(guestClass, "onRestoreInstanceState", Bundle::class.java)
        onBackPressedMethod = safeMethod(guestClass, "onBackPressed")
        onKeyDownMethod = safeMethod(
            guestClass, "onKeyDown",
            Int::class.javaPrimitiveType!!, KeyEvent::class.java
        )
        onKeyUpMethod = safeMethod(
            guestClass, "onKeyUp",
            Int::class.javaPrimitiveType!!, KeyEvent::class.java
        )
        onTouchEventMethod = safeMethod(guestClass, "onTouchEvent", MotionEvent::class.java)
        onCreateOptionsMenuMethod = safeMethod(guestClass, "onCreateOptionsMenu", Menu::class.java)
        onOptionsItemSelectedMethod = safeMethod(guestClass, "onOptionsItemSelected", MenuItem::class.java)
        onRequestPermissionsResultMethod = safeMethod(
            guestClass, "onRequestPermissionsResult",
            Int::class.javaPrimitiveType!!, Array<String>::class.java, IntArray::class.java
        )
        onConfigurationChangedMethod = safeMethod(guestClass, "onConfigurationChanged", Configuration::class.java)
        onWindowFocusChangedMethod = safeMethod(
            guestClass, "onWindowFocusChanged",
            Boolean::class.javaPrimitiveType!!
        )
    }

    private fun safeMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val m = current.getDeclaredMethod(name, *params)
                m.isAccessible = true
                return m
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun setGuestIntent(guestIntent: Intent) {
        try {
            val intentField = Activity::class.java.getDeclaredField("mIntent")
            intentField.isAccessible = true
            intentField.set(guestActivity, guestIntent)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to set guest intent: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        try { onStartMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onStart failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        ActivityStubManager.onStubResumed(instanceId, getStubIndex())
        reportStateToService(InstanceNotificationManager.ACTION_OPEN_INSTANCE)
        try { onResumeMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onResume failed: ${e.message}")
        }
    }

    override fun onPause() {
        try { onPauseMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onPause failed: ${e.message}")
        }
        super.onPause()
    }

    override fun onStop() {
        try { onStopMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onStop failed: ${e.message}")
        }
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        try { onRestartMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRestart failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { onDestroyMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onDestroy failed: ${e.message}")
        }
        ActivityStubManager.onStubReleased(instanceId, getStubIndex(), guestClassName)
        reportStateToService(InstanceNotificationManager.ACTION_STOP_INSTANCE)
        guestActivity = null
        virtualClassLoader = null
        super.onDestroy()

        if (isFinishing) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    RenjanaLog.i(TAG, "Cleanly terminating subprocess :p${getStubIndex()} (pid=${android.os.Process.myPid()})")
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {}
            }, 300)
        }
    }

    private fun reportStateToService(action: String) {
        if (instanceId.isEmpty()) return
        try {
            val intent = Intent(this, InstanceLifecycleService::class.java).apply {
                this.action = action
                putExtra(InstanceNotificationManager.EXTRA_INSTANCE_ID, instanceId)
                if (guestPackageName.isNotBlank()) putExtra(EXTRA_PACKAGE_NAME, guestPackageName)
                putExtra(EXTRA_STUB_INDEX, getStubIndex())
            }
            startService(intent)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_FINISH_GUEST) {
            RenjanaLog.i(TAG, "StubActivity[${getStubIndex()}] FINISH_GUEST (onNewIntent) — finishing")
            finish()
            return
        }
        val guestIntent = intent.getParcelableExtra<Intent>(EXTRA_GUEST_ORIGINAL_INTENT) ?: intent
        try { onNewIntentMethod?.invoke(guestActivity, guestIntent) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onNewIntent failed: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            val originalRequestCode = ActivityStubManager.resolveRequestCode(
                instanceId, getStubIndex(), requestCode
            )
            onActivityResultMethod?.invoke(guestActivity, originalRequestCode, resultCode, data)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onActivityResult failed: ${e.message}")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        try { onSaveInstanceStateMethod?.invoke(guestActivity, outState) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onSaveInstanceState failed: ${e.message}")
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        try { onRestoreInstanceStateMethod?.invoke(guestActivity, savedInstanceState) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRestoreInstanceState failed: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onBackPressedMethod != null) {
            try {
                onBackPressedMethod!!.invoke(guestActivity)
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Guest onBackPressed failed: ${e.message}")
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return try {
            onKeyDownMethod?.invoke(guestActivity, keyCode, event) as? Boolean
                ?: super.onKeyDown(keyCode, event)
        } catch (e: Exception) {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return try {
            onKeyUpMethod?.invoke(guestActivity, keyCode, event) as? Boolean
                ?: super.onKeyUp(keyCode, event)
        } catch (e: Exception) {
            super.onKeyUp(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return try {
            onTouchEventMethod?.invoke(guestActivity, event) as? Boolean
                ?: super.onTouchEvent(event)
        } catch (e: Exception) {
            super.onTouchEvent(event)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        return try {
            onCreateOptionsMenuMethod?.invoke(guestActivity, menu) as? Boolean
                ?: super.onCreateOptionsMenu(menu)
        } catch (e: Exception) {
            super.onCreateOptionsMenu(menu)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            onOptionsItemSelectedMethod?.invoke(guestActivity, item) as? Boolean
                ?: super.onOptionsItemSelected(item)
        } catch (e: Exception) {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            onRequestPermissionsResultMethod?.invoke(
                guestActivity, requestCode, permissions, grantResults
            )
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRequestPermissionsResult failed: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try { onConfigurationChangedMethod?.invoke(guestActivity, newConfig) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onConfigurationChanged failed: ${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        try { onWindowFocusChangedMethod?.invoke(guestActivity, hasFocus) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onWindowFocusChanged failed: ${e.message}")
        }
    }

    private var guestTheme: Resources.Theme? = null

    private val stubThemeResource: Int
        get() = try {
            val f = android.view.ContextThemeWrapper::class.java.getDeclaredField("mThemeResource")
            f.isAccessible = true
            f.getInt(this)
        } catch (_: Throwable) {
            0
        }

    override fun getTheme(): Resources.Theme {
        val guestRes = virtualClassLoader?.getResources(this)
        if (guestRes != null) {
            if (guestTheme == null) {
                guestTheme = guestRes.newTheme()
                if (effectiveGuestTheme != 0) {
                    try {
                        guestTheme?.applyStyle(effectiveGuestTheme, true)
                    } catch (_: Throwable) {}
                } else {
                    val hostTheme = stubThemeResource.takeIf { it != 0 }
                        ?: com.fesu.renjana.R.style.Theme_Renjana
                    try {
                        guestTheme?.applyStyle(hostTheme, true)
                    } catch (_: Throwable) {}
                }
            }
            return guestTheme!!
        }
        return super.getTheme()
    }

    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        guestTheme = null
    }

    override fun getResources(): Resources {
        return virtualClassLoader?.getResources(this) ?: super.getResources()
    }

    override fun getAssets(): AssetManager {
        return virtualClassLoader?.getAssets() ?: super.getAssets()
    }

    override fun getClassLoader(): ClassLoader {
        return virtualClassLoader ?: super.getClassLoader()
    }

    override fun getLayoutInflater(): android.view.LayoutInflater {
        val inflater = super.getLayoutInflater()
        return inflater.cloneInContext(this)
    }

    fun startGuestActivityForResult(guestIntent: Intent, stubIntent: Intent, requestCode: Int) {
        ActivityStubManager.registerRequestCode(instanceId, getStubIndex(), requestCode, guestIntent)
        startActivityForResult(stubIntent, requestCode)
    }
}

class StubActivity_0 : StubActivity() { override fun getStubIndex(): Int = 0 }
class StubActivity_1 : StubActivity() { override fun getStubIndex(): Int = 1 }
class StubActivity_2 : StubActivity() { override fun getStubIndex(): Int = 2 }
class StubActivity_3 : StubActivity() { override fun getStubIndex(): Int = 3 }
class StubActivity_4 : StubActivity() { override fun getStubIndex(): Int = 4 }
class StubActivity_5 : StubActivity() { override fun getStubIndex(): Int = 5 }
class StubActivity_6 : StubActivity() { override fun getStubIndex(): Int = 6 }
class StubActivity_7 : StubActivity() { override fun getStubIndex(): Int = 7 }
class StubActivity_8 : StubActivity() { override fun getStubIndex(): Int = 8 }
class StubActivity_9 : StubActivity() { override fun getStubIndex(): Int = 9 }
