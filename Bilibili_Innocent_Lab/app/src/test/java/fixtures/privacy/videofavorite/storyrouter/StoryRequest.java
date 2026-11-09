package fixtures.privacy.videofavorite.storyrouter;

/** Shape fixture for RouteRequest: only the target URI the hook reads. */
public final class StoryRequest {
    private final String targetUri;

    public StoryRequest(String targetUri) {
        this.targetUri = targetUri;
    }

    public final String getTargetUri() {
        return targetUri;
    }
}
