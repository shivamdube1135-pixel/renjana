package com.fesu.renjana.core

import android.content.res.AssetManager
import android.content.res.Resources
import dalvik.system.DexClassLoader
import java.io.File

class VirtualClassLoader(
    private val apkPath: String,
    private val instanceId: String,
    private val optimizedDir: File,
    private val guestPackageName: String? = null,
    private val nativeLibDir: String? = null,
    parent: ClassLoader? = null
) : ClassLoader(parent ?: ClassLoader.getSystemClassLoader()) {

    private val dexClassLoader: DexClassLoader
    private var resources: Resources? = null
    private var assetManager: AssetManager? = null

    companion object {
        fun resolveAllApkPaths(apkPath: String): List<String> {
            val paths = mutableListOf<String>()
            val mainFile = File(apkPath)
            if (mainFile.exists()) {
                paths.add(mainFile.absolutePath)
                val parentDir = mainFile.parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    parentDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".apk") && file.absolutePath != mainFile.absolutePath
                    }?.forEach { split ->
                        paths.add(split.absolutePath)
                    }
                }
            } else {
                paths.add(apkPath)
            }
            return paths.distinct()
        }
    }

    private val allApkPaths: List<String> = resolveAllApkPaths(apkPath)

    init {
        if (!optimizedDir.exists()) {
            optimizedDir.mkdirs()
        }

        allApkPaths.forEach { path ->
            try {
                val f = File(path)
                if (f.exists() && f.canWrite()) {
                    f.setReadOnly()
                }
            } catch (_: Throwable) {}
        }

        val combinedDexPath = allApkPaths.joinToString(File.pathSeparator)
        // Use extracted nativeLibDir cleanly so linker doesn't choke on APK zip paths
        val libSearchPath = nativeLibDir
        dexClassLoader = DexClassLoader(
            combinedDexPath,
            optimizedDir.absolutePath,
            libSearchPath,
            frameworkOnlyParent()
        )
    }

    private fun frameworkOnlyParent(): ClassLoader = try {
        ClassLoader.getSystemClassLoader().parent ?: ClassLoader.getSystemClassLoader()
    } catch (_: Throwable) {
        ClassLoader.getSystemClassLoader()
    }

    override fun loadClass(name: String): Class<*> {
        return loadClass(name, false)
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        return try {
            val clazz = dexClassLoader.loadClass(name)
            if (resolve) {
                resolveClass(clazz)
            }
            clazz
        } catch (e: ClassNotFoundException) {
            super.loadClass(name, resolve)
        }
    }

    override fun findClass(name: String): Class<*> {
        return dexClassLoader.loadClass(name)
    }

    fun getResources(context: android.content.Context): Resources {
        if (resources == null) {
            resources = createResources(context)
        }
        return resources!!
    }

    fun getAssets(): AssetManager {
        if (assetManager == null) {
            if (resources != null) {
                assetManager = resources!!.assets
            } else {
                assetManager = createAssetManager()
            }
        }
        return assetManager!!
    }

    private fun createAssetManager(): AssetManager {
        try {
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val assetManager = ctor.newInstance()
            val addAssetPathMethod = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath",
                String::class.java
            )
            addAssetPathMethod.isAccessible = true
            for (path in allApkPaths) {
                try {
                    val result = addAssetPathMethod.invoke(assetManager, path) as? Int
                    com.fesu.renjana.utils.RenjanaLog.i("VirtualClassLoader", "Added asset path: $path (cookie=$result)")
                } catch (e: Exception) {
                    com.fesu.renjana.utils.RenjanaLog.w("VirtualClassLoader", "Failed to add asset path $path: ${e.message}")
                }
            }
            return assetManager
        } catch (e: Exception) {
            throw RuntimeException("Failed to create AssetManager for $apkPath", e)
        }
    }

    private fun createResources(context: android.content.Context): Resources {
        try {
            val pm = context.packageManager
            val targetPkg = guestPackageName ?: try {
                pm.getPackageArchiveInfo(apkPath, 0)?.packageName
            } catch (_: Throwable) { null }

            if (!targetPkg.isNullOrEmpty()) {
                try {
                    val appRes = pm.getResourcesForApplication(targetPkg)
                    com.fesu.renjana.utils.RenjanaLog.i("VirtualClassLoader", "Loaded Resources via PackageManager for $targetPkg")
                    return appRes
                } catch (e: Throwable) {
                    com.fesu.renjana.utils.RenjanaLog.w("VirtualClassLoader", "Failed to get Resources for $targetPkg via PM: ${e.message}")
                }
            }

            val pkgInfo = pm.getPackageArchiveInfo(apkPath, 0)
            if (pkgInfo?.applicationInfo != null) {
                val ai = pkgInfo.applicationInfo
                ai.sourceDir = apkPath
                ai.publicSourceDir = apkPath
                val splits = allApkPaths.filter { it != apkPath }.toTypedArray()
                if (splits.isNotEmpty()) {
                    ai.splitSourceDirs = splits
                    ai.splitPublicSourceDirs = splits
                }
                val appRes = pm.getResourcesForApplication(ai)
                com.fesu.renjana.utils.RenjanaLog.i("VirtualClassLoader", "Loaded Resources via ApplicationInfo archive for $apkPath")
                return appRes
            }
        } catch (e: Throwable) {
            com.fesu.renjana.utils.RenjanaLog.w("VirtualClassLoader", "PackageManager getResources fallback to AssetManager: ${e.message}")
        }

        val assets = getAssets()
        val appRes = context.applicationContext?.resources ?: Resources.getSystem()
        @Suppress("DEPRECATION")
        return Resources(
            assets,
            appRes.displayMetrics,
            appRes.configuration
        )
    }

    fun setLibraryPath(libPath: String) {
        try {
            val pathListField = DexClassLoader::class.java.superclass
                .getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(dexClassLoader)

            val nativeLibraryDirectoriesField = pathList.javaClass
                .getDeclaredField("nativeLibraryDirectories")
            nativeLibraryDirectoriesField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val dirs = nativeLibraryDirectoriesField.get(pathList) as List<File>
            val newDirs = ArrayList(dirs)
            newDirs.add(File(libPath))
            nativeLibraryDirectoriesField.set(pathList, newDirs)
        } catch (e: Exception) {
            println("Warning: Failed to set library path: ${e.message}")
        }
    }

    fun loadGuestClass(className: String): Class<*> {
        return dexClassLoader.loadClass(className)
    }

    fun hasClass(className: String): Boolean {
        return try {
            dexClassLoader.loadClass(className)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
