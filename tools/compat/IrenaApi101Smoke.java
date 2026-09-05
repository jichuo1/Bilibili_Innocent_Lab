import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookRuntime;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;

/** Run with Irena's pinned API 101 classes, never the API 102 AAR, ahead of the module jar. */
public final class IrenaApi101Smoke {
    public static void main(String[] args) throws Throwable {
        require(((Number) XposedInterface.class.getField("LIB_API").get(null)).intValue() == 101,
                "This verification must run against actual API 101 classes");
        try {
            XposedInterface.HookBuilder.class.getMethod("setId", String.class);
            throw new AssertionError("API 102 leaked into the verification classpath");
        } catch (NoSuchMethodException expected) {
        }

        Method target = IrenaApi101Smoke.class.getDeclaredMethod("target", int.class);
        AtomicInteger nativeRegistrations = new AtomicInteger();
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        AtomicReference<XposedInterface.ExceptionMode> mode = new AtomicReference<>();
        XposedInterface api = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, member, values) -> {
                    if (member.getName().equals("getApiVersion")) return 101;
                    if (!member.getName().equals("hook")) throw new AssertionError(member);
                    require(target.equals(values[0]), "Unexpected Hook target");
                    return Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, method, params) -> {
                                if (method.getName().equals("setExceptionMode")) {
                                    mode.set((XposedInterface.ExceptionMode) params[0]);
                                    return builder;
                                }
                                if (!method.getName().equals("intercept")) throw new AssertionError(method);
                                nativeRegistrations.incrementAndGet();
                                installed.set((XposedInterface.Hooker) params[0]);
                                return Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, op, ignored) -> {
                                            if (op.getName().equals("getExecutable")) return target;
                                            if (op.getName().equals("unhook")) return null;
                                            throw new AssertionError(op);
                                        });
                            });
                });

        ModernHookRuntime runtime = new ModernHookRuntime(api);
        runtime.install("abi:point", target, creator -> {
            creator.before(param -> {
                param.getArgs()[0] = 5;
                return Unit.INSTANCE;
            });
            creator.after(param -> {
                param.setResult(((Integer) param.getResult()) + 10);
                return Unit.INSTANCE;
            });
            return Unit.INSTANCE;
        });
        require(mode.get() == XposedInterface.ExceptionMode.PROTECTIVE, "Protective mode was lost");
        require(installed.get().intercept(new Chain(target, null)).equals(15), "before/after ABI mismatch");
        Runnable replace = () -> runtime.install("abi:point", target, creator -> {
            creator.replaceTo(99);
            return Unit.INSTANCE;
        });
        require(installed.get().intercept(new Chain(target, replace)).equals(15), "In-flight callback changed");
        require(installed.get().intercept(new Chain(target, null)).equals(99), "Replacement did not take effect");
        require(nativeRegistrations.get() == 1, "API 101 duplicated the native Hook");
        System.out.println("IRENA_API101_BINARY_SMOKE=PASS; realApi=101; setId=absent; nativeRegistrations=1");
    }

    public static int target(int value) {
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Chain implements XposedInterface.Chain {
        private final Executable executable;
        private final Runnable duringOriginal;
        private final Object[] values = {1};
        Chain(Executable executable, Runnable duringOriginal) {
            this.executable = executable;
            this.duringOriginal = duringOriginal;
        }
        public Executable getExecutable() { return executable; }
        public Object getThisObject() { return null; }
        public List<Object> getArgs() { return Arrays.asList(values); }
        public Object getArg(int index) { return values[index]; }
        public Object proceed() { return proceed(values); }
        public Object proceed(Object[] args) {
            if (duringOriginal != null) duringOriginal.run();
            return args[0];
        }
        public Object proceedWith(Object receiver) { return proceed(); }
        public Object proceedWith(Object receiver, Object[] args) { return proceed(args); }
    }
}
