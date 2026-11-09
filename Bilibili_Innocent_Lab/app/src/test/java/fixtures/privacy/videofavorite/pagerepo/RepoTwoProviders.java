package fixtures.privacy.videofavorite.pagerepo;

/** Two fields of the provider type: which one holds the aid cannot be told, so the shape must not be recognised. */
public final class RepoTwoProviders {
    public final Provider c;
    public final Provider other;

    public RepoTwoProviders(Object context, Object scope, Object reply, Provider provider, Object extra, Object type) {
        this.c = provider;
        this.other = provider;
    }

    public final void n(boolean z) {
    }

    public final void o(boolean z) {
    }

    public final void v(boolean z) {
    }

    public final void w(boolean z) {
    }

    public final void y(boolean z) {
    }
}
