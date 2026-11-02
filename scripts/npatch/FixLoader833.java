import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Java 17 source launcher; dexlib2 is supplied by the caller's JADX 1.5.6 fat JAR. */
public final class FixLoader833 {
    private static final String PATCHER_SHA = "d7e69d464b90aa888a5d555b94d38dab94a4aab19d80f863d238b6715a0fcea3";
    private static final String LOADER_SHA = "29325bd4715bf62dcfdfeb7d1acdaa58d9bcf0040a4fa22756d615542736ea8b";
    private static final String ASSET = "assets/npatch/loader.bin";
    private static final String OWNER = "Ltop/nkbe/npatch/service/RemoteApplicationService;";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: FixLoader833.java <original-833.jar> <new-output.jar>");
        Path input = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IOException("Output already exists: " + output);
        require(hash(Files.readAllBytes(input)).equals(PATCHER_SHA), "Unsupported patcher SHA-256");
        try (ZipFile jar = new ZipFile(input.toFile())) {
            byte[] original = jar.getInputStream(Objects.requireNonNull(jar.getEntry(ASSET))).readAllBytes();
            require(hash(original).equals(LOADER_SHA), "Unsupported loader SHA-256");
            DexBackedDexFile dex = new DexBackedDexFile(null, original);
            Method originalMethod = findMethod(dex);
            require(originalMethod.getImplementation() != null && (originalMethod.getAccessFlags() & 8) == 0,
                "Expected instance method implementation");
            // 833 implements IFrameworkService but does NOT extend Binder. A local Binder is a
            // liveness token for VectorServiceClient; service calls still use the local Java wrapper.
            // Never return `this`: that would pass a non-IBinder into linkToDeath on this release.
            var implementation = new ImmutableMethodImplementation(2, List.of(
                new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference("Landroid/os/Binder;")),
                new ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0,
                    new ImmutableMethodReference("Landroid/os/Binder;", "<init>", List.of(), "V")),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), List.of(), List.of());
            Method replacement = new ImmutableMethod(OWNER, originalMethod.getName(), originalMethod.getParameters(),
                originalMethod.getReturnType(), originalMethod.getAccessFlags(), originalMethod.getAnnotations(),
                originalMethod.getHiddenApiRestrictions(), implementation);
            byte[] patched = rewrite(dex, replacement);
            DexBackedDexFile reread = new DexBackedDexFile(dex.getOpcodes(), patched);
            var instructions = new ArrayList<com.android.tools.smali.dexlib2.iface.instruction.Instruction>();
            findMethod(reread).getImplementation().getInstructions().forEach(instructions::add);
            require(instructions.size() == 3 && instructions.get(0).getOpcode() == Opcode.NEW_INSTANCE &&
                instructions.get(1).getOpcode() == Opcode.INVOKE_DIRECT &&
                instructions.get(2).getOpcode() == Opcode.RETURN_OBJECT &&
                ((OneRegisterInstruction) instructions.get(2)).getRegisterA() == 0 &&
                findMethod(reread).getImplementation().getRegisterCount() == 2, "Patched method verification failed");
            require(Arrays.equals(patched, rewrite(reread, replacement)), "Patched method operands changed during serialization");
            // Restoring the original method must reproduce the canonical original DEX byte for byte.
            // This checks all other class/field/method bodies, metadata, annotations and debug information.
            require(Arrays.equals(rewrite(dex, originalMethod), rewrite(reread, originalMethod)),
                "Unexpected change outside asBinder()");

            Path temporary = Files.createTempFile(output.getParent(), "npatch-833-", ".jar.tmp");
            try {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    for (ZipEntry entry : Collections.list(jar.entries())) {
                        ZipEntry next = new ZipEntry(entry.getName());
                        next.setTime(entry.getTime());
                        zip.putNextEntry(next);
                        if (!entry.isDirectory()) {
                            if (entry.getName().equals(ASSET)) zip.write(patched);
                            else jar.getInputStream(entry).transferTo(zip);
                        }
                        zip.closeEntry();
                    }
                }
                verifyJar(jar, temporary, patched);
                Files.move(temporary, output); // Never replace a caller's existing artifact.
            } finally {
                Files.deleteIfExists(temporary);
            }
            System.out.println("Verified single-method loader fix: " + output);
            System.out.println("loader SHA-256: " + hash(patched));
            System.out.println("patcher SHA-256: " + hash(Files.readAllBytes(output)));
        }
    }

    private static boolean target(Method method) {
        return method.getDefiningClass().equals(OWNER) && method.getName().equals("asBinder") &&
            method.getParameterTypes().isEmpty() && method.getReturnType().equals("Landroid/os/IBinder;");
    }

    private static Method findMethod(DexFile dex) {
        var found = new ArrayList<Method>();
        for (ClassDef cls : dex.getClasses()) for (Method method : cls.getMethods()) if (target(method)) found.add(method);
        require(found.size() == 1, "Expected exactly one RemoteApplicationService.asBinder()");
        return found.get(0);
    }

    private static byte[] rewrite(DexFile dex, Method replacement) throws IOException {
        var classes = new ArrayList<ClassDef>();
        for (ClassDef cls : dex.getClasses()) {
            if (!cls.getType().equals(OWNER)) { classes.add(cls); continue; }
            var methods = new ArrayList<Method>();
            for (Method method : cls.getMethods()) methods.add(target(method) ? replacement : method);
            classes.add(new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods));
        }
        classes.sort(Comparator.comparing(ClassDef::getType));
        MemoryDataStore store = new MemoryDataStore();
        try {
            DexPool.writeTo(store, new ImmutableDexFile(dex.getOpcodes(), classes));
            return store.getData();
        } finally {
            store.close();
        }
    }

    private static void verifyJar(ZipFile original, Path output, byte[] loader) throws IOException {
        try (ZipFile result = new ZipFile(output.toFile())) {
            require(result.size() == original.size(), "JAR entry count changed");
            for (ZipEntry entry : Collections.list(original.entries())) {
                ZipEntry next = result.getEntry(entry.getName());
                require(next != null, "Missing JAR entry: " + entry.getName());
                if (entry.isDirectory()) continue;
                byte[] expected = entry.getName().equals(ASSET) ? loader : original.getInputStream(entry).readAllBytes();
                require(Arrays.equals(expected, result.getInputStream(next).readAllBytes()),
                    "Unexpected JAR entry change: " + entry.getName());
            }
        }
    }

    private static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
