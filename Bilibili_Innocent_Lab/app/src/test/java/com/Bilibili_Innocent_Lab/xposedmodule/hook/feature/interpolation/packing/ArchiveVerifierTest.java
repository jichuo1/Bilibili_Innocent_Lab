package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ArchiveVerifierTest {
    interface Checked { void run() throws Exception; }
    private static int passed;
    private static final String ENTRY = "lib/arm64-v8a/" + ArchiveSpec.LIBRARY_NAME;
    private static final byte[] LIBRARY = ("research-native-".repeat(32)).getBytes(StandardCharsets.UTF_8);

    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static void reject(String name, Checked action) throws Exception {
        try { action.run(); throw new AssertionError(name + " accepted"); }
        catch (IOException | IllegalArgumentException expected) { passed++; }
    }
    static String sha(byte[] data) { return ArchiveVerifier.hex(ArchiveVerifier.digest().digest(data)); }
    static ArchiveSpec spec(byte[] archive) { return new ArchiveSpec("arm64-v8a", archive.length, sha(archive), LIBRARY.length, sha(LIBRARY)); }
    static byte[] zip(String first, byte[] data, String extra) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(first)); output.write(data); output.closeEntry();
            if (extra != null) { output.putNextEntry(new ZipEntry(extra)); output.write(1); output.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    static void extract(byte[] archive, ArchiveSpec expected, ByteArrayOutputStream output) throws Exception {
        byte[] verified = ArchiveVerifier.readVerified(new ByteArrayInputStream(archive), expected);
        ArchiveVerifier.unpackVerified(verified, expected, output);
    }
    @org.junit.Test public void cacheHitStillAuthenticatesTheOriginalArchive() throws Exception {
        byte[] archive = zip(ENTRY, LIBRARY, null);
        ArchiveSpec expected = spec(archive);
        ArchiveVerifier.verifyArchive(new ByteArrayInputStream(archive), expected);
        archive[0] ^= 1;
        org.junit.Assert.assertThrows(IOException.class,
                () -> ArchiveVerifier.verifyArchive(new ByteArrayInputStream(archive), expected));
    }

    @org.junit.Test public void validatesBytesAndRejectsAllBoundViolations() throws Exception {
        byte[] archive = zip(ENTRY, LIBRARY, null);
        ArchiveSpec expected = spec(archive);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        extract(archive, expected, output);
        check(Arrays.equals(LIBRARY, output.toByteArray()), "valid expanded bytes differ"); passed++;
        ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(LIBRARY), expected); passed++;
        byte[] damaged = archive.clone(); damaged[damaged.length / 2] ^= 1;
        reject("archive hash", () -> extract(damaged, expected, new ByteArrayOutputStream()));
        reject("archive truncated", () -> extract(Arrays.copyOf(archive, archive.length - 1), expected, new ByteArrayOutputStream()));
        byte[] extraArchiveByte = Arrays.copyOf(archive, archive.length + 1);
        reject("archive oversized", () -> extract(extraArchiveByte, expected, new ByteArrayOutputStream()));
        byte[] traversal = zip("../" + ArchiveSpec.LIBRARY_NAME, LIBRARY, null);
        reject("path traversal", () -> extract(traversal, spec(traversal), new ByteArrayOutputStream()));
        byte[] multiple = zip(ENTRY, LIBRARY, "other.so");
        reject("second entry", () -> extract(multiple, spec(multiple), new ByteArrayOutputStream()));
        ArchiveSpec tooSmall = new ArchiveSpec(expected.abi, archive.length, sha(archive), LIBRARY.length - 1, sha(LIBRARY));
        ByteArrayOutputStream bounded = new ByteArrayOutputStream();
        reject("expanded bound", () -> extract(archive, tooSmall, bounded));
        check(bounded.size() <= LIBRARY.length - 1, "oversized byte reached target"); passed++;
        ArchiveSpec tooLarge = new ArchiveSpec(expected.abi, archive.length, sha(archive), LIBRARY.length + 1, sha(LIBRARY));
        reject("expanded truncation", () -> extract(archive, tooLarge, new ByteArrayOutputStream()));
        ArchiveSpec wrongExpandedHash = new ArchiveSpec(expected.abi, archive.length, sha(archive), LIBRARY.length, "0".repeat(64));
        reject("expanded hash", () -> extract(archive, wrongExpandedHash, new ByteArrayOutputStream()));
        byte[] changedLibrary = LIBRARY.clone(); changedLibrary[0] ^= 1;
        reject("cached hash", () -> ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(changedLibrary), expected));
        reject("cached truncation", () -> ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(Arrays.copyOf(LIBRARY, LIBRARY.length - 1)), expected));
        reject("cached oversized", () -> ArchiveVerifier.verifyLibrary(new ByteArrayInputStream(Arrays.copyOf(LIBRARY, LIBRARY.length + 1)), expected));
        InputStream zeroProgress = new ByteArrayInputStream(archive) {
            boolean zero;
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                zero = !zero;
                return zero ? 0 : super.read(buffer, offset, length);
            }
        };
        check(Arrays.equals(archive, ArchiveVerifier.readVerified(zeroProgress, expected)), "zero-read progress failed"); passed++;
        reject("unknown ABI", () -> new ArchiveSpec("x86_64", 1, "0".repeat(64), 1, "0".repeat(64)));
        reject("archive cap", () -> new ArchiveSpec("arm64-v8a", ArchiveSpec.MAX_ARCHIVE_BYTES + 1, "0".repeat(64), 1, "0".repeat(64)));
        reject("library cap", () -> new ArchiveSpec("arm64-v8a", 1, "0".repeat(64), ArchiveSpec.MAX_LIBRARY_BYTES + 1, "0".repeat(64)));
        org.junit.Assert.assertEquals(18, passed);
    }
}
