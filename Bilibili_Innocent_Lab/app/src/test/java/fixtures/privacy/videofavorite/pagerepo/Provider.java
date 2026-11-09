package fixtures.privacy.videofavorite.pagerepo;

/** Shaped like the host's page video-id provider: several no-argument long methods, the first by name is the aid. */
public final class Provider {
    private final long aid;

    public Provider(long aid) {
        this.aid = aid;
    }

    public long a() {
        return aid;
    }

    public long d() {
        return 7L;
    }
}
