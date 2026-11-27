package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Explicit worker-only dependency preparation. No model, network, automatic retry or loadLibrary fallback. */
public final class FrameNativeLoader {
    public enum Status { NOT_PREPARED, PREPARING, READY, BUSY, UNSUPPORTED_ABI, RESTART_REQUIRED, FAILED }
    private static final NativeLoadState STATE = new NativeLoadState();
    private static final int ELF_HEADER_PROBE_BYTES = 64;
    private static final int EM_386 = 3;
    private static final int EM_ARM = 40;
    private static final int EM_X86_64 = 62;
    private static final int EM_AARCH64 = 183;
    private static final int EM_RISCV = 243;
    private static volatile boolean processAbiResolved;
    private static volatile String cachedProcessAbi;
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
    public static Status prepare(File moduleApk, File hostCodeCache) {
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
                    NativeDependencyCache.prepareAndLoad(hostCodeCache, input, spec, mapped);
                }
            }
        });
    }

    private static String runtimeAbi() {
        if (!processAbiResolved) {
            synchronized (FrameNativeLoader.class) {
                if (!processAbiResolved) {
                    boolean is64Bit = Process.is64Bit();
                    cachedProcessAbi = selectRuntimeAbi(
                        is64Bit,
                        readProcSelfExeHeader(),
                        readUnameMachine(),
                        is64Bit ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS
                    );
                    processAbiResolved = true;
                }
            }
        }
        return cachedProcessAbi;
    }

    static String selectRuntimeAbi(boolean is64Bit, String[] supported) {
        String expected = is64Bit ? "arm64-v8a" : "armeabi-v7a";
        if (supported != null) {
            for (String candidate : supported) if (expected.equals(candidate)) return expected;
        }
        return null;
    }

    static String selectRuntimeAbi(boolean is64Bit, byte[] elfHeader, String unameMachine, String[] supported) {
        String expected = selectRuntimeAbi(is64Bit, supported);
        if (expected == null) return null;
        String unameAbi = mapUnameToAbi(unameMachine, is64Bit);
        if (unameAbi != null && !expected.equals(unameAbi)) {
            return null;
        }
        if (elfHeader != null) {
            String elfAbi = parseElfMachineAbi(elfHeader);
            if (elfAbi == null || !expected.equals(elfAbi)) {
                return null;
            }
        }
        return expected;
    }

    static String parseElfMachineAbi(byte[] elfHeader) {
        if (elfHeader == null || elfHeader.length < 20) return null;
        if (elfHeader[0] != 0x7f || elfHeader[1] != 'E' || elfHeader[2] != 'L' || elfHeader[3] != 'F') {
            return null;
        }
        int eiClass = elfHeader[4] & 0xFF;
        int eiData = elfHeader[5] & 0xFF;
        int machine;
        if (eiData == 1) {
            machine = (elfHeader[18] & 0xFF) | ((elfHeader[19] & 0xFF) << 8);
        } else if (eiData == 2) {
            machine = ((elfHeader[18] & 0xFF) << 8) | (elfHeader[19] & 0xFF);
        } else {
            return null;
        }
        switch (machine) {
            case EM_AARCH64:
                return eiClass == 2 ? "arm64-v8a" : null;
            case EM_ARM:
                return eiClass == 1 ? "armeabi-v7a" : null;
            case EM_X86_64:
                return eiClass == 2 ? "x86_64" : null;
            case EM_386:
                return eiClass == 1 ? "x86" : null;
            case EM_RISCV:
                return eiClass == 2 ? "riscv64" : null;
            default:
                return "elf-" + machine;
        }
    }

    private static String mapUnameToAbi(String machine, boolean is64Bit) {
        if (machine == null) return null;
        String normalized = machine.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if ("x86_64".equals(normalized) || "amd64".equals(normalized)) {
            return is64Bit ? "x86_64" : "x86";
        }
        if ("x86".equals(normalized) || "i386".equals(normalized) || "i486".equals(normalized)
                || "i586".equals(normalized) || "i686".equals(normalized)) {
            return is64Bit ? "x86_64" : "x86";
        }
        if ("aarch64".equals(normalized) || "arm64".equals(normalized) || "arm64-v8a".equals(normalized)) {
            return is64Bit ? "arm64-v8a" : "armeabi-v7a";
        }
        if (normalized.startsWith("arm")) {
            return !is64Bit ? "armeabi-v7a" : "arm-on-64";
        }
        if ("riscv64".equals(normalized)) {
            return is64Bit ? "riscv64" : "riscv32";
        }
        return null;
    }

    private static byte[] readProcSelfExeHeader() {
        byte[] bytes = new byte[ELF_HEADER_PROBE_BYTES];
        try (FileInputStream input = new FileInputStream("/proc/self/exe")) {
            int total = 0;
            while (total < bytes.length) {
                int read = input.read(bytes, total, bytes.length - total);
                if (read < 0) break;
                total += read;
            }
            if (total < 20) return null;
            if (total == bytes.length) return bytes;
            byte[] truncated = new byte[total];
            System.arraycopy(bytes, 0, truncated, 0, total);
            return truncated;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static String readUnameMachine() {
        try {
            String machine = Os.uname().machine;
            return (machine != null && !machine.trim().isEmpty()) ? machine : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
