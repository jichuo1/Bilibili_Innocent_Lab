package fixtures.privacy.videofavorite.storyrouter;

/** Shape fixture for RouteResponse: a public (Code, RouteRequest) constructor. */
public final class StoryResponse {
    public final StoryCode code;
    public final StoryRequest request;

    public StoryResponse(StoryCode code, StoryRequest request) {
        this.code = code;
        this.request = request;
    }
}
