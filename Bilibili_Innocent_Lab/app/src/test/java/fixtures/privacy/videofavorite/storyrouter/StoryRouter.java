package fixtures.privacy.videofavorite.storyrouter;

import android.content.Context;

/** Shape fixture for BLRouter.routeTo(RouteRequest, Context): a static call that returns a response and records what it was asked. */
public final class StoryRouter {
    public static final java.util.List<String> routed = new java.util.ArrayList<>();

    private StoryRouter() {
    }

    public static final StoryResponse routeTo(StoryRequest request, Context context) {
        routed.add(request.getTargetUri());
        return new StoryResponse(StoryCode.FOUND_STUB, request);
    }

    /** The overload the favorites folder card uses: BLRouter.routeTo(RouteRequest, Fragment). */
    public static final StoryResponse routeTo(StoryRequest request, StoryFragment fragment) {
        routed.add(request.getTargetUri());
        return new StoryResponse(StoryCode.FOUND_STUB, request);
    }
}
