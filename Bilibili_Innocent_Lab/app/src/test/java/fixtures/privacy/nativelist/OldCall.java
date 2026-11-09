package fixtures.privacy.nativelist;

/** 8.84 to 8.96 shape: two (Response)Result methods, the converter is the package-private {@code p}. */
public class OldCall {
    Result p(Resp response) { return null; }

    private Result q(Resp response) { return null; }

    public boolean isExecuted() { return false; }
}
