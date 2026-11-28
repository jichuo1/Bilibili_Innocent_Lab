package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import static com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader.Status.BUSY;
import static com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader.Status.FAILED;
import static com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader.Status.NOT_PREPARED;
import static com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader.Status.PREPARING;
import static com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader.Status.READY;
import static com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader.Status.RESTART_REQUIRED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public final class FrameNativePackingTest {
    private interface Checked { void run() throws Exception; }

    private static final String ENTRY = "lib/arm64-v8a/" + ArchiveSpec.LIBRARY_NAME;
    private static final byte[] LIBRARY = ("research-native-".repeat(32)).getBytes(StandardCharsets.UTF_8);

    private static final int UID = 12345, DIRECTORY = 0040000, SYMLINK = 0120000;
    private static final File BASE = new File(System.getProperty("java.io.tmpdir"), "innocent-cache-root-test");
    private static final File RAW = new File(BASE, "alias/code_cache").getAbsoluteFile();
    private static final File CANONICAL = new File(BASE, "actual/code_cache").getAbsoluteFile();
    private static final CanonicalCacheRoot.Identity OWNED = identity(7, 13, DIRECTORY | 0750, UID);

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static void reject(String name, AtomicInteger passed, Checked action) throws Exception {
        try {
            action.run();
            throw new AssertionError(name + " accepted");
        } catch (IOException | IllegalArgumentException expected) {
            passed.incrementAndGet();
        }
    }

    private static String sha(byte[] data) {
        return ArchiveVerifier.hex(ArchiveVerifier.digest().digest(data));
    }

    private static ArchiveSpec spec(byte[] archive) {
        return new ArchiveSpec("arm64-v8a", archive.length, sha(archive), LIBRARY.length, sha(LIBRARY));
    }

    private static byte[] zip(String first, byte[] data, String extra) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(first));
            output.write(data);
            output.closeEntry();
            if (extra != null) {
                output.putNextEntry(new ZipEntry(extra));
                output.write(1);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void extract(byte[] archive, ArchiveSpec expected, ByteArrayOutputStream output) throws Exception {
        byte[] verified = ArchiveVerifier.readVerified(new ByteArrayInputStream(archive), expected);
        ArchiveVerifier.unpackVerified(verified, expected, output);
    }

    private static CanonicalCacheRoot.Identity identity(long device, long inode, int mode, int uid) {
        return new CanonicalCacheRoot.Identity(device, inode, mode, uid);
    }

    private static final class Filesystem implements CanonicalCacheRoot.Access {
        final CanonicalCacheRoot.Identity[] snapshots = {OWNED, OWNED, OWNED, OWNED};
        final File[] paths = {CANONICAL, CANONICAL, CANONICAL, CANONICAL};
        int statCalls, canonicalCalls, failStat = -1, failCanonical = -1;

        @Override
        public File canonical(File file) throws IOException {
            int call = ++canonicalCalls;
            if (call == failCanonical) throw new IOException("unavailable");
            assertEquals(call == 1 || call == 3 ? RAW : CANONICAL, file);
            return paths[call - 1];
        }

        @Override
        public CanonicalCacheRoot.Identity lstat(File file) throws IOException {
            int call = ++statCalls;
            if (call == failStat) throw new IOException("unavailable");
            assertEquals(call == 1 || call == 3 ? RAW : CANONICAL, file);
            return snapshots[call - 1];
        }

        @Override public int uid() { return UID; }
        @Override public int typeMask() { return 0170000; }
        @Override public int directoryType() { return DIRECTORY; }
    }

    @Test
    public void archiveVerificationValidatesBytesAndRejectsTamperingAndBounds() throws Exception {
        byte[] cacheHitArchive = zip(ENTRY, LIBRARY, null);
        ArchiveSpec cacheHitExpected = spec(cacheHitArchive);
        ArchiveVerifier.verifyArchive(new ByteArrayInputStream(cacheHitArchive), cacheHitExpected);
        cacheHitArchive[0] ^= 1;
        assertThrows(
                IOException.class,
                () -> ArchiveVerifier.verifyArchive(new ByteArrayInputStream(cacheHitArchive), cacheHitExpected)
        );

        AtomicInteger passed = new AtomicInteger();
        byte[] archive = zip(ENTRY, LIBRARY, null);
        ArchiveSpec expected = spec(archive);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        extract(archive, expected, output);
        check(Arrays.equals(LIBRARY, output.toByteArray()), "valid expanded bytes differ");
        passed.incrementAndGet();
        ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(LIBRARY), expected);
        passed.incrementAndGet();
        byte[] damaged = archive.clone();
        damaged[damaged.length / 2] ^= 1;
        reject("archive hash", passed, () -> extract(damaged, expected, new ByteArrayOutputStream()));
        reject("archive truncated", passed, () -> extract(Arrays.copyOf(archive, archive.length - 1), expected, new ByteArrayOutputStream()));
        byte[] extraArchiveByte = Arrays.copyOf(archive, archive.length + 1);
        reject("archive oversized", passed, () -> extract(extraArchiveByte, expected, new ByteArrayOutputStream()));
        byte[] traversal = zip("../" + ArchiveSpec.LIBRARY_NAME, LIBRARY, null);
        reject("path traversal", passed, () -> extract(traversal, spec(traversal), new ByteArrayOutputStream()));
        byte[] multiple = zip(ENTRY, LIBRARY, "other.so");
        reject("second entry", passed, () -> extract(multiple, spec(multiple), new ByteArrayOutputStream()));
        ArchiveSpec tooSmall = new ArchiveSpec(expected.abi, archive.length, sha(archive), LIBRARY.length - 1, sha(LIBRARY));
        ByteArrayOutputStream bounded = new ByteArrayOutputStream();
        reject("expanded bound", passed, () -> extract(archive, tooSmall, bounded));
        check(bounded.size() <= LIBRARY.length - 1, "oversized byte reached target");
        passed.incrementAndGet();
        ArchiveSpec tooLarge = new ArchiveSpec(expected.abi, archive.length, sha(archive), LIBRARY.length + 1, sha(LIBRARY));
        reject("expanded truncation", passed, () -> extract(archive, tooLarge, new ByteArrayOutputStream()));
        ArchiveSpec wrongExpandedHash = new ArchiveSpec(expected.abi, archive.length, sha(archive), LIBRARY.length, "0".repeat(64));
        reject("expanded hash", passed, () -> extract(archive, wrongExpandedHash, new ByteArrayOutputStream()));
        byte[] changedLibrary = LIBRARY.clone();
        changedLibrary[0] ^= 1;
        reject("cached hash", passed, () -> ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(changedLibrary), expected));
        reject("cached truncation", passed, () -> ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(Arrays.copyOf(LIBRARY, LIBRARY.length - 1)), expected));
        reject("cached oversized", passed, () -> ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(Arrays.copyOf(LIBRARY, LIBRARY.length + 1)), expected));
        InputStream zeroProgress = new ByteArrayInputStream(archive) {
            boolean zero;

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                zero = !zero;
                return zero ? 0 : super.read(buffer, offset, length);
            }
        };
        check(Arrays.equals(archive, ArchiveVerifier.readVerified(zeroProgress, expected)), "zero-read progress failed");
        passed.incrementAndGet();
        reject("unknown ABI", passed, () -> new ArchiveSpec("x86_64", 1, "0".repeat(64), 1, "0".repeat(64)));
        reject("archive cap", passed, () -> new ArchiveSpec("arm64-v8a", ArchiveSpec.MAX_ARCHIVE_BYTES + 1, "0".repeat(64), 1, "0".repeat(64)));
        reject("library cap", passed, () -> new ArchiveSpec("arm64-v8a", 1, "0".repeat(64), ArchiveSpec.MAX_LIBRARY_BYTES + 1, "0".repeat(64)));
        assertEquals(18, passed.get());
    }

    @Test
    public void canonicalCacheRootResolvesValidPathsAndRejectsUnsafeInputs() throws Exception {
        Filesystem aliasFs = new Filesystem();
        assertEquals(CANONICAL, CanonicalCacheRoot.resolve(RAW, aliasFs));
        assertEquals(4, aliasFs.statCalls);
        assertEquals(4, aliasFs.canonicalCalls);

        CanonicalCacheRoot.Access canonicalFs = new CanonicalCacheRoot.Access() {
            @Override public File canonical(File path) { assertEquals(CANONICAL, path); return path; }
            @Override public CanonicalCacheRoot.Identity lstat(File path) { assertEquals(CANONICAL, path); return OWNED; }
            @Override public int uid() { return UID; }
            @Override public int typeMask() { return 0170000; }
            @Override public int directoryType() { return DIRECTORY; }
        };
        assertEquals(CANONICAL, CanonicalCacheRoot.resolve(CANONICAL, canonicalFs));

        for (File path : new File[]{null, new File("code_cache"), new File(BASE, "cache").getAbsoluteFile()}) {
            Filesystem filesystem = new Filesystem();
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(path, filesystem));
            assertEquals(0, filesystem.statCalls);
            assertEquals(0, filesystem.canonicalCalls);
        }

        for (File destination : new File[]{null, new File("code_cache"), new File(BASE, "cache").getAbsoluteFile()}) {
            Filesystem filesystem = new Filesystem();
            filesystem.paths[0] = destination;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            assertEquals(0, filesystem.statCalls);
        }

        CanonicalCacheRoot.Identity[] unsafe = {
                identity(7, 13, SYMLINK | 0750, UID), identity(7, 13, 0100000 | 0750, UID),
                identity(7, 13, DIRECTORY | 0750, UID + 1), identity(7, 13, DIRECTORY | 0777, UID)
        };
        for (int position = 0; position < 2; position++) {
            for (CanonicalCacheRoot.Identity identity : unsafe) {
                Filesystem filesystem = new Filesystem();
                filesystem.snapshots[position] = identity;
                assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            }
        }

        for (CanonicalCacheRoot.Identity other : new CanonicalCacheRoot.Identity[]{
                identity(8, 13, OWNED.mode, UID), identity(7, 14, OWNED.mode, UID),
                identity(7, 13, DIRECTORY | 0700, UID)}) {
            Filesystem filesystem = new Filesystem();
            filesystem.snapshots[1] = other;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }
    }

    @Test
    public void canonicalCacheRootEnforcesToctouBindingAndFailClosedLookups() {
        for (int position = 2; position < 4; position++) {
            for (CanonicalCacheRoot.Identity changed : new CanonicalCacheRoot.Identity[]{
                    identity(7, 14, OWNED.mode, UID), identity(7, 13, OWNED.mode, UID + 1),
                    identity(7, 13, DIRECTORY | 0700, UID), identity(7, 13, SYMLINK | 0750, UID)}) {
                Filesystem filesystem = new Filesystem();
                filesystem.snapshots[position] = changed;
                assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            }
        }

        Filesystem changedRawFs = new Filesystem();
        changedRawFs.paths[2] = new File(BASE, "other/code_cache");
        assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, changedRawFs));

        for (int position : new int[]{1, 3}) {
            Filesystem filesystem = new Filesystem();
            filesystem.paths[position] = RAW;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }

        for (int call = 1; call <= 4; call++) {
            Filesystem filesystem = new Filesystem();
            filesystem.failCanonical = call;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            assertEquals(call, filesystem.canonicalCalls);
        }

        for (int call = 1; call <= 4; call++) {
            Filesystem filesystem = new Filesystem();
            filesystem.failStat = call;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
            assertEquals(call, filesystem.statCalls);
        }

        for (int position = 0; position < 4; position++) {
            Filesystem filesystem = new Filesystem();
            filesystem.snapshots[position] = null;
            assertThrows(IOException.class, () -> CanonicalCacheRoot.resolve(RAW, filesystem));
        }
    }

    @Test
    public void cacheLockAndRuntimeAbiEnforceExclusivityAndArmSelection() throws Exception {
        Path file = Files.createTempFile("innocent-frame-lock-test-", ".lock");
        try (FileChannel first = FileChannel.open(file, StandardOpenOption.WRITE);
             FileChannel second = FileChannel.open(file, StandardOpenOption.WRITE)) {
            try (FileLock held = CacheLock.claim(first)) {
                assertTrue(held.isValid());
                try {
                    CacheLock.claim(second);
                    fail("overlapping claim accepted");
                } catch (CacheLock.Busy expected) {
                    assertEquals("cache_busy", expected.getMessage());
                }
            }
            try (FileLock reclaimed = CacheLock.claim(second)) {
                assertTrue(reclaimed.isValid());
            }
        } finally {
            Files.deleteIfExists(file);
        }

        String[] both = {"arm64-v8a", "armeabi-v7a"};
        assertEquals("arm64-v8a", FrameNativeLoader.selectRuntimeAbi(true, both));
        assertEquals("armeabi-v7a", FrameNativeLoader.selectRuntimeAbi(false, both));
        assertNull(FrameNativeLoader.selectRuntimeAbi(false, new String[]{"arm64-v8a"}));
        assertNull(FrameNativeLoader.selectRuntimeAbi(true, new String[]{"armeabi-v7a"}));
        assertNull(FrameNativeLoader.selectRuntimeAbi(true, new String[]{"x86_64"}));
        assertNull(FrameNativeLoader.selectRuntimeAbi(false, new String[]{"x86"}));
        assertNull(FrameNativeLoader.selectRuntimeAbi(true, new String[0]));
        assertNull(FrameNativeLoader.selectRuntimeAbi(false, new String[]{"armeabi"}));
    }

    @Test
    public void nativeLoadStateTracksReceiptsRetriesIdentityAndConcurrency() throws Exception {
        NativeLoadState receiptState = new NativeLoadState();
        assertEquals(NOT_PREPARED, receiptState.status());
        assertEquals(FAILED, receiptState.prepare(HASH_A, mapped -> {}));
        assertEquals(READY, receiptState.prepare(HASH_A, Runnable::run));

        NativeLoadState retryState = new NativeLoadState();
        assertEquals(FAILED, retryState.prepare(HASH_A, mapped -> { throw new IOException(); }));
        assertEquals(READY, retryState.prepare(HASH_A, Runnable::run));

        NativeLoadState busyState = new NativeLoadState();
        assertEquals(BUSY, busyState.prepare(HASH_A, mapped -> { throw new CacheLock.Busy(); }));
        assertEquals(READY, busyState.prepare(HASH_A, Runnable::run));

        NativeLoadState linkFailureState = new NativeLoadState();
        assertEquals(FAILED, linkFailureState.prepare(HASH_A, mapped -> { throw new UnsatisfiedLinkError(); }));
        assertEquals(FAILED, linkFailureState.status());
        assertEquals(READY, linkFailureState.prepare(HASH_A, Runnable::run));

        NativeLoadState singleLoadState = new NativeLoadState();
        AtomicInteger loads = new AtomicInteger();
        assertEquals(READY, singleLoadState.prepare(HASH_A, mapped -> { loads.incrementAndGet(); mapped.run(); }));
        assertEquals(READY, singleLoadState.prepare(HASH_A, mapped -> fail("duplicate mapping")));
        assertEquals(RESTART_REQUIRED, singleLoadState.prepare(HASH_B, mapped -> fail("replacement mapping")));
        assertEquals(READY, singleLoadState.status());
        assertEquals(1, loads.get());

        NativeLoadState closeFailureState = new NativeLoadState();
        assertEquals(FAILED, closeFailureState.prepare(HASH_A, mapped -> { mapped.run(); throw new IOException(); }));
        assertEquals(FAILED, closeFailureState.status());
        assertEquals(RESTART_REQUIRED, closeFailureState.prepare(HASH_B, mapped -> fail("mapped identity forgotten")));
        assertEquals(FAILED, closeFailureState.prepare(HASH_A, mapped -> fail("uncertain mapping retried")));

        NativeLoadState reentrantState = new NativeLoadState();
        assertEquals(READY, reentrantState.prepare(HASH_A, mapped -> {
            assertEquals(BUSY, reentrantState.prepare(HASH_A, nested -> fail("nested mapping")));
            mapped.run();
        }));

        NativeLoadState concurrentState = new NativeLoadState();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        Thread worker = new Thread(() -> assertEquals(READY, concurrentState.prepare(HASH_A, mapped -> {
            entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            mapped.run();
        })));
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(PREPARING, concurrentState.status());
            assertEquals(BUSY, concurrentState.prepare(HASH_B, mapped -> fail("concurrent map")));
            assertEquals(PREPARING, concurrentState.status());
        } finally {
            finish.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertEquals(READY, concurrentState.status());
    }
}
