package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

final class ArchiveSpec {
    static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
    static final int MAX_LIBRARY_BYTES = 16 * 1024 * 1024;
    static final String LIBRARY_NAME = "libmeguviz_media_rife_ncnn.so";
    final String abi;
    final String entry;
    final int archiveBytes;
    final String archiveSha256;
    final int libraryBytes;
    final String librarySha256;

    ArchiveSpec(String abi, int archiveBytes, String archiveSha256, int libraryBytes, String librarySha256) {
        if (!("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) || archiveBytes <= 0 ||
                archiveBytes > MAX_ARCHIVE_BYTES || libraryBytes <= 0 || libraryBytes > MAX_LIBRARY_BYTES ||
                !hash(archiveSha256) || !hash(librarySha256)) throw new IllegalArgumentException("invalid_fixed_spec");
        this.abi = abi;
        this.entry = "lib/" + abi + "/" + LIBRARY_NAME;
        this.archiveBytes = archiveBytes;
        this.archiveSha256 = archiveSha256;
        this.libraryBytes = libraryBytes;
        this.librarySha256 = librarySha256;
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
