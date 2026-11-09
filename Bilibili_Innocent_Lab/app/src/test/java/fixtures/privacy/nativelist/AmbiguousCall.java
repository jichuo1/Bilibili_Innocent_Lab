package fixtures.privacy.nativelist;

/** Two public instance (Response)Result methods and no stable name: nothing may be guessed. */
public class AmbiguousCall {
    public final Result x(Resp response) { return null; }

    public final Result y(Resp response) { return null; }
}
