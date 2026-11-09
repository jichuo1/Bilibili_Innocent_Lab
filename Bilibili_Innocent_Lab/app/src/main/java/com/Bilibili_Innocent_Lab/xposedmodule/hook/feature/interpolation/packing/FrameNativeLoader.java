package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import android.os.Build;
import android.os.Looper;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Explicit worker-only dependency preparation. No model, network, automatic retry or loadLibrary fallback. */
public final class FrameNativeLoader {
    public enum Status { NOT_PREPARED, PREPARING, READY, BUSY, UNSUPPORTED_ABI, RESTART_REQUIRED, FAILED }
    private static final NativeLoadState STATE = new NativeLoadState();
    private FrameNativeLoader() { }

    /** Cheap status only; safe on the presentation path. */
    public static Status status() {
        try {
            return runtimeAbi() == null ? Status.UNSUPPORTED_ABI : STATE.status();
        } catch (RuntimeException | LinkageError unavailable) {
            // An unreadable process ABI cannot prove that the native payload is usable.
            return Status.UNSUPPORTED_ABI;
        }
    }

    /**
     * Caller must pass API102 getModuleApplicationInfo().sourceDir and the actual host
     * Context.codeCacheDir. An owned directory alias is accepted only after matching repeated
     * raw/canonical identity snapshots; subsequent cache checks still apply to the canonical root.
     * Never use ProtectionDomain, module-private data, or a host APK here.
     * Runs synchronously on the caller's bounded background worker; no thread or work queue is created.
     */
    public static Status prepare(File moduleApk, File hostCodeCache, Class<?> actualJniClass) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("native_prepare_requires_worker");
        String abi = runtimeAbi();
        if (abi == null) return Status.UNSUPPORTED_ABI;
        final ArchiveSpec spec;
        try { spec = FrameNativeSpec.forAbi(abi); }
        catch (IllegalArgumentException unavailable) { return Status.UNSUPPORTED_ABI; }
        return STATE.prepare(spec.librarySha256, mapped -> {
            File source = moduleApk.getAbsoluteFile();
            if (!source.equals(source.getCanonicalFile()) || !source.isFile()) throw new IOException("module_apk_not_canonical");
            try (ZipFile apk = new ZipFile(source)) {
                String name = "assets/native-packing/" + spec.abi + "/VisionTool.zip";
                ZipEntry selected = null;
                Enumeration<? extends ZipEntry> entries = apk.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    if (++count > 65536) throw new IOException("apk_entry_bound");
                    ZipEntry entry = entries.nextElement();
                    if (name.equals(entry.getName())) {
                        if (selected != null || entry.isDirectory() || entry.getSize() != spec.archiveBytes)
                            throw new IOException("native_asset_ambiguous_or_size");
                        selected = entry;
                    }
                }
                if (selected == null) throw new IOException("native_asset_missing");
                try (InputStream input = apk.getInputStream(selected)) {
                    NativeDependencyCache.prepareAndLoad(hostCodeCache, input, spec, actualJniClass, mapped);
                }
            }
        });
    }

    private static String runtimeAbi() {
        boolean is64Bit = Process.is64Bit();
        return selectRuntimeAbi(is64Bit, is64Bit ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS);
    }

    static String selectRuntimeAbi(boolean is64Bit, String[] supported) {
        String expected = is64Bit ? "arm64-v8a" : "armeabi-v7a";
        for (String candidate : supported) if (expected.equals(candidate)) return expected;
        return null;
    }
}
