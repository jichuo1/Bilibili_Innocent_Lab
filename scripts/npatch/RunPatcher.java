import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Runs the unmodified 833 patcher entry point with its BKS signature provider registered. */
public final class RunPatcher {
    public static void main(String[] args) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        top.nkbe.npatch.patch.NPatch.main(args);
    }
}
