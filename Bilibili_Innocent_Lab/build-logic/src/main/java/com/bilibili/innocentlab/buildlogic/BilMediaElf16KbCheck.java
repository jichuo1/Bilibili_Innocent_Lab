package com.bilibili.innocentlab.buildlogic;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;

/**
 * Pure-Java ELF64 16KB page-size and ZIP STORED LFH alignment verifier (Configuration Cache safe).
 */
public final class BilMediaElf16KbCheck {
    private static final long PAGE_16K = 16384L;

    private BilMediaElf16KbCheck() {
    }

    public static void verifyElf64PageAlignment(byte[] bytes, String label) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(label, "label");
        if (bytes.length < 64) {
            throw new IllegalStateException(label + " is too small for ELF64 header");
        }
        if (bytes[0] != 0x7f || bytes[1] != (byte) 'E' || bytes[2] != (byte) 'L' || bytes[3] != (byte) 'F') {
            throw new IllegalStateException(label + " is not an ELF binary");
        }
        if (bytes[4] != 2 || bytes[5] != 1) {
            throw new IllegalStateException(label + " must be little-endian ELF64");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long phoff = buf.getLong(32);
        int phentsize = buf.getShort(54) & 0xFFFF;
        int phnum = buf.getShort(56) & 0xFFFF;
        long phTableBytes = (long) phnum * (long) phentsize;
        if (phoff < 64L || phentsize < 56 || phnum <= 0 || phoff > (long) bytes.length - phTableBytes) {
            throw new IllegalStateException(label + " has invalid ELF64 program headers");
        }
        int loadSegments = 0;
        for (int i = 0; i < phnum; i++) {
            int off = (int) (phoff + (long) i * (long) phentsize);
            long pType = buf.getInt(off) & 0xFFFF_FFFFL;
            if (pType == 1L) {
                loadSegments++;
                long pAlign = buf.getLong(off + 48);
                if (pAlign < PAGE_16K || pAlign % PAGE_16K != 0L) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + i + "] p_align=" + pAlign + " is not 16KB aligned"
                    );
                }
            }
        }
        if (loadSegments <= 0) {
            throw new IllegalStateException(label + " has no PT_LOAD segments");
        }
    }

    public static void verifyApk16KbAlignment(File apk, Set<String> expectedStoredSos) {
        Objects.requireNonNull(apk, "apk");
        Objects.requireNonNull(expectedStoredSos, "expectedStoredSos");
        if (expectedStoredSos.isEmpty()) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(apk, "r")) {
            long fileLen = raf.length();
            if (fileLen < 22L) {
                throw new IllegalStateException(apk.getName() + " is too small to be a ZIP");
            }
            int tailSize = (int) Math.min(fileLen, 22L + 65535L);
            byte[] tail = new byte[tailSize];
            raf.seek(fileLen - tailSize);
            raf.readFully(tail);
            ByteBuffer tailBuf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int eocdPos = -1;
            for (int pos = tailSize - 22; pos >= 0; pos--) {
                if (tailBuf.getInt(pos) == 0x06054b50) {
                    int commentLen = tailBuf.getShort(pos + 20) & 0xFFFF;
                    if (pos + 22 + commentLen == tailSize) {
                        eocdPos = pos;
                        break;
                    }
                }
            }
            if (eocdPos < 0) {
                throw new IllegalStateException("Missing ZIP EOCD in " + apk.getName());
            }
            long cdSize = tailBuf.getInt(eocdPos + 12) & 0xFFFF_FFFFL;
            long cdOffset = tailBuf.getInt(eocdPos + 16) & 0xFFFF_FFFFL;
            if (cdOffset < 0L || cdSize <= 0L || cdSize > Integer.MAX_VALUE || cdOffset > fileLen - cdSize) {
                throw new IllegalStateException("Invalid ZIP central directory in " + apk.getName());
            }
            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOffset);
            raf.readFully(cd);
            ByteBuffer cdBuf = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN);
            Set<String> seen = new HashSet<>();
            byte[] lfh = new byte[30];
            ByteBuffer lfhBuf = ByteBuffer.wrap(lfh).order(ByteOrder.LITTLE_ENDIAN);
            int p = 0;
            while (p < cd.length) {
                if (p + 46 > cd.length || cdBuf.getInt(p) != 0x02014b50) {
                    throw new IllegalStateException("Corrupt ZIP central directory entry in " + apk.getName());
                }
                int method = cdBuf.getShort(p + 10) & 0xFFFF;
                int nameLen = cdBuf.getShort(p + 28) & 0xFFFF;
                int extraLen = cdBuf.getShort(p + 30) & 0xFFFF;
                int commentLen = cdBuf.getShort(p + 32) & 0xFFFF;
                long lfhOff = cdBuf.getInt(p + 42) & 0xFFFF_FFFFL;
                long recordLen = 46L + nameLen + extraLen + commentLen;
                if ((long) p + recordLen > (long) cd.length) {
                    throw new IllegalStateException("Truncated CEN record in " + apk.getName());
                }
                String name = new String(cd, p + 46, nameLen, StandardCharsets.UTF_8);
                if (expectedStoredSos.contains(name)) {
                    if (!seen.add(name)) {
                        throw new IllegalStateException("Duplicate ZIP CEN entry " + name + " in " + apk.getName());
                    }
                    if (method != ZipEntry.STORED) {
                        throw new IllegalStateException(
                            name + " in " + apk.getName() + " must be STORED (method=" + method + ")"
                        );
                    }
                    if (lfhOff + 30L > cdOffset) {
                        throw new IllegalStateException(name + " LFH offset out of range in " + apk.getName());
                    }
                    raf.seek(lfhOff);
                    raf.readFully(lfh);
                    if (lfhBuf.getInt(0) != 0x04034b50) {
                        throw new IllegalStateException(name + " missing LFH magic in " + apk.getName());
                    }
                    int lfhMethod = lfhBuf.getShort(8) & 0xFFFF;
                    if (lfhMethod != ZipEntry.STORED) {
                        throw new IllegalStateException(
                            name + " LFH method=" + lfhMethod + " != STORED in " + apk.getName()
                        );
                    }
                    int lfhNameLen = lfhBuf.getShort(26) & 0xFFFF;
                    int lfhExtraLen = lfhBuf.getShort(28) & 0xFFFF;
                    long dataOffset = lfhOff + 30L + lfhNameLen + lfhExtraLen;
                    if (dataOffset % PAGE_16K != 0L) {
                        throw new IllegalStateException(
                            name + " in " + apk.getName() + " has dataOffset=" + dataOffset + " (not 16KB aligned)"
                        );
                    }
                }
                p += (int) recordLen;
            }
            if (!seen.equals(expectedStoredSos)) {
                Set<String> missing = new LinkedHashSet<>(expectedStoredSos);
                missing.removeAll(seen);
                throw new IllegalStateException(
                    "Missing expected arm64-v8a .so in CEN of " + apk.getName() + ": " + missing
                );
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to verify 16KB alignment in " + apk.getName(), e);
        }
    }
}
