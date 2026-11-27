package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing;

import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.Elf16KbVerifier;
import io.github.jichuo1.meguviz.media.MediaNative;
import io.github.jichuo1.meguviz.media.MediaRuntime;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Frame-native dependency cache only. No model, package manager, network or legacy library-load fallback. */
final class NativeDependencyCache {
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private NativeDependencyCache() { }

    static final class Loaded {
        final Path library;
        final boolean cacheHit;
        Loaded(Path library, boolean cacheHit) { this.library = library; this.cacheHit = cacheHit; }
    }

    static Loaded prepareAndLoad(File ownCodeCache, InputStream archiveInput, ArchiveSpec spec, Runnable mapped)
            throws IOException {
        if (MediaNative.class.getClassLoader() != NativeDependencyCache.class.getClassLoader())
            throw new IOException("classloader_mismatch");
        requireRuntimeAbi(spec.abi);
        Path base = CanonicalCacheRoot.resolve(ownCodeCache).toPath();
        requireOwned(base, true, false);
        Path scope = privateDirectory(base.resolve("native-dependency-packing-v1"));
        Path abi = privateDirectory(scope.resolve(spec.abi));
        Path content = privateDirectory(abi.resolve(spec.librarySha256));
        Path lockPath = content.resolve(".extract.lock");
        try {
            Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (FileAlreadyExistsException existing) { /* The same owned lock inode is reused. */ }
        StructStat lockIdentity = requireOwned(lockPath, false, false);
        if ((lockIdentity.st_mode & 0777) != 0600) throw new IOException("lock_mode");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, NOFOLLOW)) {
            try (FileLock claimed = CacheLock.claim(channel)) {
                requireSame(lockIdentity, requireOwned(lockPath, false, false));
                Path library = content.resolve(ArchiveSpec.LIBRARY_NAME);
                boolean hit = Files.exists(library, NOFOLLOW);
                if (hit) ArchiveVerifier.verifyArchive(archiveInput, spec);
                else publish(content, library, ArchiveVerifier.readVerified(archiveInput, spec), spec);
                // Revalidate while holding the non-waiting publication lock, then load the absolute path.
                verifyCached(library, spec);
                Loaded prepared = new Loaded(library, hit);
                String absolute = library.toAbsolutePath().toString();
                System.load(absolute);
                // Remember the mapping before lock/stream close can fail. It is never unloaded or replaced.
                mapped.run();
                MediaRuntime.attachAlreadyLoaded();
                return prepared;
            }
        }
    }

    private static void publish(Path content, Path library, byte[] archive, ArchiveSpec spec) throws IOException {
        Path temporary = Files.createTempFile(content, ".extract-", ".part",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        boolean moved = false;
        try {
            requireOwned(temporary, false, false);
            try (FileChannel writer = FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW)) {
                OutputStream output = Channels.newOutputStream(writer);
                ArchiveVerifier.unpackVerified(archive, spec, output);
                output.flush();
                writer.force(true);
            }
            chmod(temporary, 0400);
            verifyCached(temporary, spec);
            if (Files.exists(library, NOFOLLOW)) throw new IOException("publication_target_appeared");
            try { Files.move(temporary, library, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { throw new IOException("atomic_publish_unavailable", unavailable); }
            moved = true;
            verifyCached(library, spec);
        } finally {
            // Delete only this invocation's generated partial; no model path or recursive cleanup exists.
            if (!moved && temporary.getParent().equals(content)) Files.deleteIfExists(temporary);
        }
    }

    private static void verifyCached(Path library, ArchiveSpec spec) throws IOException {
        StructStat status = requireOwned(library, false, false);
        if ((status.st_mode & 0777) != 0400 || status.st_size != spec.libraryBytes) throw new IOException("cache_mode_or_size");
        java.io.FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(library.toString(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            requireSame(status, Os.fstat(descriptor));
            try (InputStream input = new java.io.FileInputStream(descriptor)) {
                descriptor = null;
                ArchiveVerifier.verifyLibrary(input, spec);
            }
            if ("arm64-v8a".equals(spec.abi)) {
                Elf16KbVerifier.requireElf64FilePageAlignment(library.toFile(), ArchiveSpec.LIBRARY_NAME);
            } else if ("armeabi-v7a".equals(spec.abi)) {
                Elf16KbVerifier.requireElf32FilePageAlignment(library.toFile(), ArchiveSpec.LIBRARY_NAME);
            }
            requireSame(status, requireOwned(library, false, false));
        } catch (ErrnoException failure) { throw new IOException("cache_open_failed", failure); }
        finally { if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) { } }
    }

    private static void requireSame(StructStat before, StructStat after) throws IOException {
        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size
                || before.st_uid != after.st_uid || before.st_mode != after.st_mode)
            throw new IOException("cache_identity_changed");
    }

    private static Path privateDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (FileAlreadyExistsException existing) { /* No recursive create or symlink traversal. */ }
        requireOwned(directory, true, true);
        return directory;
    }

    private static StructStat requireOwned(Path path, boolean directory, boolean privateMode) throws IOException {
        try {
            StructStat status = Os.lstat(path.toString());
            int type = status.st_mode & OsConstants.S_IFMT;
            if (status.st_uid != Process.myUid() || type != (directory ? OsConstants.S_IFDIR : OsConstants.S_IFREG) ||
                    (status.st_mode & 0002) != 0 || (privateMode && (status.st_mode & 0777) != 0700))
                throw new IOException("cache_not_owned_or_private");
            return status;
        } catch (ErrnoException failure) { throw new IOException("cache_stat_failed", failure); }
    }

    private static void chmod(Path path, int mode) throws IOException {
        try { Os.chmod(path.toString(), mode); }
        catch (ErrnoException failure) { throw new IOException("readonly_publish_failed", failure); }
    }

    private static void requireRuntimeAbi(String abi) throws IOException {
        String[] supported = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        boolean permitted = "arm64-v8a".equals(abi) == Process.is64Bit();
        boolean advertised = false;
        for (String candidate : supported) advertised |= abi.equals(candidate);
        if (!permitted || !advertised) throw new IOException("runtime_abi_mismatch");
    }
}
