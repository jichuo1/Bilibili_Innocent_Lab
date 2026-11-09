package fixtures.privacy.minemenu;

/** A menu item that has getUri but no getNeedLogin (an older page): the mine menu hook must install nothing. */
public final class ItemWithoutNeedLogin {
    private final String uri;

    public ItemWithoutNeedLogin(String uri) {
        this.uri = uri;
    }

    public String getUri() {
        return uri;
    }
}
