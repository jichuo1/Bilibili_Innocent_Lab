package com.bilibili.innocentlab.buildlogic;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;

/**
 * Pure-Java ELF64/ELF32 page-size and ZIP STORED LFH alignment verifier (Configuration Cache safe).
 */
public final class BilMediaElf16KbCheck {
    private static final long PAGE_16K = 16384L;
    private static final long PAGE_4K = 4096L;
    private static final int ELF_HEADER_64_MIN_BYTES = 64;
    private static final int PHDR_64_MIN_BYTES = 56;
    private static final int ELF_HEADER_32_MIN_BYTES = 52;
    private static final int PHDR_32_MIN_BYTES = 32;
    private static final long PT_LOAD = 1L;
    private static final long PT_DYNAMIC = 2L;
    private static final long DT_NULL = 0L;
    private static final long DT_TEXTREL = 22L;
    private static final long DT_FLAGS = 30L;
    private static final long DF_TEXTREL = 0x4L;

    private static final class LoadSegmentRange {
        final long firstPage;
        final long lastPage;
        final int flags;
        final int index;

        LoadSegmentRange(long firstPage, long lastPage, int flags, int index) {
            this.firstPage = firstPage;
            this.lastPage = lastPage;
            this.flags = flags;
            this.index = index;
        }
    }

    private BilMediaElf16KbCheck() {
    }

    public static void verifyElf64PageAlignment(byte[] bytes, String label) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(label, "label");
        if (bytes.length < ELF_HEADER_64_MIN_BYTES) {
            throw new IllegalStateException(label + " is too small for ELF64 header");
        }
        verifyElfIdent(bytes, label, (byte) 2, "ELF64");
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long phoff = buf.getLong(32);
        int phentsize = buf.getShort(54) & 0xFFFF;
        int phnum = buf.getShort(56) & 0xFFFF;
        long phTableBytes = (long) phnum * (long) phentsize;
        long totalBytes = bytes.length;
        if (phoff < ELF_HEADER_64_MIN_BYTES || phentsize < PHDR_64_MIN_BYTES || phnum <= 0
                || phTableBytes > totalBytes || phoff > totalBytes - phTableBytes) {
            throw new IllegalStateException(label + " has invalid ELF64 program headers");
        }
        int loadSegments = 0;
        List<LoadSegmentRange> loadRanges = new ArrayList<>();
        for (int i = 0; i < phnum; i++) {
            int off = (int) (phoff + (long) i * (long) phentsize);
            long pType = buf.getInt(off) & 0xFFFF_FFFFL;
            if (pType == PT_LOAD) {
                loadSegments++;
                int pFlags = buf.getInt(off + 4);
                long pOffset = buf.getLong(off + 8);
                long pVaddr = buf.getLong(off + 16);
                long pFilesz = buf.getLong(off + 32);
                long pMemsz = buf.getLong(off + 40);
                long pAlign = buf.getLong(off + 48);
                if (pAlign < PAGE_16K || pAlign % PAGE_16K != 0L) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + i + "] p_align=" + pAlign + " is not 16KB aligned"
                    );
                }
                if (Math.floorMod(pOffset, PAGE_16K) != Math.floorMod(pVaddr, PAGE_16K)) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + i + "] p_offset and p_vaddr are not 16KB congruent"
                    );
                }
                if (pFilesz < 0L || pMemsz < pFilesz || pOffset < 0L
                        || pFilesz > totalBytes || pOffset > totalBytes - pFilesz) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + i + "] has invalid file or memory bounds"
                    );
                }
                if (pMemsz > 0L) {
                    long lastVaddr = pVaddr + (pMemsz - 1L);
                    if (Long.compareUnsigned(lastVaddr, pVaddr) < 0) {
                        throw new IllegalStateException(
                            label + " PT_LOAD[" + i + "] virtual address range wraps 64-bit space"
                        );
                    }
                    long firstPage = Long.divideUnsigned(pVaddr, PAGE_16K);
                    long lastPage = Long.divideUnsigned(lastVaddr, PAGE_16K);
                    loadRanges.add(new LoadSegmentRange(firstPage, lastPage, pFlags, i));
                }
            } else if (pType == PT_DYNAMIC) {
                long dynOffset = buf.getLong(off + 8);
                long dynFilesz = buf.getLong(off + 32);
                if (dynFilesz >= 16L && dynOffset >= 0L
                        && dynFilesz <= totalBytes && dynOffset <= totalBytes - dynFilesz) {
                    long dynEnd = dynOffset + dynFilesz;
                    for (long pos = dynOffset; pos <= dynEnd - 16L; pos += 16L) {
                        int p = (int) pos;
                        long dTag = buf.getLong(p);
                        long dVal = buf.getLong(p + 8);
                        if (dTag == DT_NULL) {
                            break;
                        }
                        if (dTag == DT_TEXTREL || (dTag == DT_FLAGS && (dVal & DF_TEXTREL) != 0L)) {
                            throw new IllegalStateException(
                                label + " contains forbidden TEXTREL in PT_DYNAMIC (d_tag=" + dTag + ")"
                            );
                        }
                    }
                }
            }
        }
        if (loadSegments <= 0) {
            throw new IllegalStateException(label + " has no PT_LOAD segments");
        }
        verifyLoadSegmentOverlap(loadRanges, label, "16KB");
    }

    public static void verifyElf32PageAlignment(byte[] bytes, String label) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(label, "label");
        if (bytes.length < ELF_HEADER_32_MIN_BYTES) {
            throw new IllegalStateException(label + " is too small for ELF32 header");
        }
        verifyElfIdent(bytes, label, (byte) 1, "ELF32");
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long phoff = buf.getInt(28) & 0xFFFF_FFFFL;
        int phentsize = buf.getShort(42) & 0xFFFF;
        int phnum = buf.getShort(44) & 0xFFFF;
        long phTableBytes = (long) phnum * (long) phentsize;
        long totalBytes = bytes.length;
        if (phoff < ELF_HEADER_32_MIN_BYTES || phentsize < PHDR_32_MIN_BYTES || phnum <= 0
                || phTableBytes > totalBytes || phoff > totalBytes - phTableBytes) {
            throw new IllegalStateException(label + " has invalid ELF32 program headers");
        }
        int loadSegments = 0;
        List<LoadSegmentRange> loadRanges = new ArrayList<>();
        for (int i = 0; i < phnum; i++) {
            int off = (int) (phoff + (long) i * (long) phentsize);
            long pType = buf.getInt(off) & 0xFFFF_FFFFL;
            if (pType == PT_LOAD) {
                loadSegments++;
                long pOffset = buf.getInt(off + 4) & 0xFFFF_FFFFL;
                long pVaddr = buf.getInt(off + 8) & 0xFFFF_FFFFL;
                long pFilesz = buf.getInt(off + 16) & 0xFFFF_FFFFL;
                long pMemsz = buf.getInt(off + 20) & 0xFFFF_FFFFL;
                int pFlags = buf.getInt(off + 24);
                long pAlign = buf.getInt(off + 28) & 0xFFFF_FFFFL;
                if (pAlign < PAGE_4K || pAlign % PAGE_4K != 0L) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + i + "] p_align=" + pAlign + " is not 4KB aligned"
                    );
                }
                if (Math.floorMod(pOffset, PAGE_4K) != Math.floorMod(pVaddr, PAGE_4K)) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + i + "] p_offset and p_vaddr are not 4KB congruent"
                    );
                }
                if (pMemsz < pFilesz || pFilesz > totalBytes || pOffset > totalBytes - pFilesz) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + i + "] has invalid file or memory bounds"
                    );
                }
                if (pMemsz > 0L) {
                    long lastVaddr = pVaddr + (pMemsz - 1L);
                    if (lastVaddr > 0xFFFF_FFFFL) {
                        throw new IllegalStateException(
                            label + " PT_LOAD[" + i + "] virtual address range wraps 32-bit space"
                        );
                    }
                    long firstPage = pVaddr / PAGE_4K;
                    long lastPage = lastVaddr / PAGE_4K;
                    loadRanges.add(new LoadSegmentRange(firstPage, lastPage, pFlags, i));
                }
            } else if (pType == PT_DYNAMIC) {
                long dynOffset = buf.getInt(off + 4) & 0xFFFF_FFFFL;
                long dynFilesz = buf.getInt(off + 16) & 0xFFFF_FFFFL;
                if (dynFilesz >= 8L && dynFilesz <= totalBytes && dynOffset <= totalBytes - dynFilesz) {
                    long dynEnd = dynOffset + dynFilesz;
                    for (long pos = dynOffset; pos <= dynEnd - 8L; pos += 8L) {
                        int p = (int) pos;
                        long dTag = buf.getInt(p);
                        long dVal = buf.getInt(p + 4) & 0xFFFF_FFFFL;
                        if (dTag == DT_NULL) {
                            break;
                        }
                        if (dTag == DT_TEXTREL || (dTag == DT_FLAGS && (dVal & DF_TEXTREL) != 0L)) {
                            throw new IllegalStateException(
                                label + " contains forbidden TEXTREL in PT_DYNAMIC (d_tag=" + dTag + ")"
                            );
                        }
                    }
                }
            }
        }
        if (loadSegments <= 0) {
            throw new IllegalStateException(label + " has no PT_LOAD segments");
        }
        verifyLoadSegmentOverlap(loadRanges, label, "4KB");
    }

    private static void verifyElfIdent(byte[] bytes, String label, byte expectedClass, String archLabel) {
        if (bytes[0] != 0x7f || bytes[1] != (byte) 'E' || bytes[2] != (byte) 'L' || bytes[3] != (byte) 'F') {
            throw new IllegalStateException(label + " is not an ELF binary");
        }
        if (bytes[4] != expectedClass || bytes[5] != 1) {
            throw new IllegalStateException(label + " must be little-endian " + archLabel);
        }
    }

    private static void verifyLoadSegmentOverlap(List<LoadSegmentRange> ranges, String label, String pageLabel) {
        for (int i = 0; i < ranges.size(); i++) {
            LoadSegmentRange a = ranges.get(i);
            for (int j = i + 1; j < ranges.size(); j++) {
                LoadSegmentRange b = ranges.get(j);
                if (a.flags != b.flags && a.firstPage <= b.lastPage && b.firstPage <= a.lastPage) {
                    throw new IllegalStateException(
                        label + " PT_LOAD[" + a.index + "] (flags=" + a.flags + ") and PT_LOAD[" + b.index
                            + "] (flags=" + b.flags + ") share a " + pageLabel + " virtual page"
                    );
                }
            }
        }
    }

    public static void verifyApk16KbAlignment(File apk, Set<String> expectedStoredSos) {
        verifyApkStoredAlignment(apk, expectedStoredSos, PAGE_16K, "16KB");
    }

    public static void verifyApk4KbAlignment(File apk, Set<String> expectedStoredSos) {
        verifyApkStoredAlignment(apk, expectedStoredSos, PAGE_4K, "4KB");
    }

    private static void verifyApkStoredAlignment(File apk, Set<String> expectedStoredSos, long pageSize, String pageLabel) {
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
                    if (dataOffset % pageSize != 0L) {
                        throw new IllegalStateException(
                            name + " in " + apk.getName() + " has dataOffset=" + dataOffset
                                + " (not " + pageLabel + " aligned)"
                        );
                    }
                }
                p += (int) recordLen;
            }
            if (!seen.equals(expectedStoredSos)) {
                Set<String> missing = new LinkedHashSet<>(expectedStoredSos);
                missing.removeAll(seen);
                throw new IllegalStateException(
                    "Missing expected .so in CEN of " + apk.getName() + ": " + missing
                );
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to verify " + pageLabel + " alignment in " + apk.getName(), e);
        }
    }
}
