package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ArchiveVerifier {
    private ArchiveVerifier() { }

    static byte[] readVerified(InputStream input, ArchiveSpec spec) throws IOException {
        byte[] bytes = new byte[spec.archiveBytes];
        int total = 0;
        while (total < bytes.length) {
            int count = input.read(bytes, total, bytes.length - total);
            if (count < 0) throw new IOException("archive_truncated");
            if (count == 0) {
                int one = input.read();
                if (one < 0) throw new IOException("archive_truncated");
                bytes[total++] = (byte) one;
            } else total += count;
        }
        if (input.read() != -1) throw new IOException("archive_oversized");
        requireHash(digest().digest(bytes), spec.archiveSha256, "archive_sha256");
        return bytes;
    }

    static void unpackVerified(byte[] archive, ArchiveSpec spec, OutputStream target) throws IOException {
        // Recheck at the extraction boundary too; a caller cannot replace the verified array unnoticed.
        if (archive.length != spec.archiveBytes) throw new IOException("archive_size");
        requireHash(digest().digest(archive), spec.archiveSha256, "archive_sha256");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || !spec.entry.equals(entry.getName()) || entry.isDirectory() ||
                    entry.getMethod() != ZipEntry.DEFLATED || (entry.getSize() >= 0 && entry.getSize() != spec.libraryBytes))
                throw new IOException("zip_entry_not_allowlisted");
            MessageDigest expanded = digest();
            byte[] chunk = new byte[16 * 1024];
            int total = 0;
            while (true) {
                int remainingWithProbe = spec.libraryBytes - total + 1;
                int count = zip.read(chunk, 0, Math.min(chunk.length, remainingWithProbe));
                if (count < 0) break;
                if (count == 0) {
                    int one = zip.read();
                    if (one < 0) break;
                    chunk[0] = (byte) one;
                    count = 1;
                }
                if (count > spec.libraryBytes - total) throw new IOException("expanded_oversized");
                expanded.update(chunk, 0, count);
                target.write(chunk, 0, count);
                total += count;
            }
            if (total != spec.libraryBytes) throw new IOException("expanded_truncated");
            requireHash(expanded.digest(), spec.librarySha256, "expanded_sha256");
            if (zip.getNextEntry() != null) throw new IOException("multiple_zip_entries");
        }
    }

    static void verifyArchive(InputStream input, ArchiveSpec spec) throws IOException {
        verifyStream(input, spec.archiveBytes, spec.archiveSha256, "archive");
    }

    static void verifyLibrary(InputStream input, ArchiveSpec spec) throws IOException {
        verifyStream(input, spec.libraryBytes, spec.librarySha256, "cache");
    }

    private static void verifyStream(InputStream input, int expectedBytes, String expectedSha256, String subject) throws IOException {
        MessageDigest hash = digest();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, expectedBytes - total + 1));
            if (count < 0) break;
            if (count == 0) {
                int one = input.read();
                if (one < 0) break;
                buffer[0] = (byte) one;
                count = 1;
            }
            if (count > expectedBytes - total) throw new IOException(subject + "_oversized");
            hash.update(buffer, 0, count);
            total += count;
        }
        if (total != expectedBytes) throw new IOException(subject + "_truncated");
        requireHash(hash.digest(), expectedSha256, subject + "_sha256");
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException missing) { throw new IllegalStateException("sha256_unavailable", missing); }
    }

    static String hex(byte[] value) {
        char[] result = new char[value.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int unsigned = value[i] & 255;
            result[i * 2] = digits[unsigned >>> 4];
            result[i * 2 + 1] = digits[unsigned & 15];
        }
        return new String(result);
    }

    private static void requireHash(byte[] actual, String expected, String reason) throws IOException {
        if (!MessageDigest.isEqual(hex(actual).getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException(reason);
    }
}
