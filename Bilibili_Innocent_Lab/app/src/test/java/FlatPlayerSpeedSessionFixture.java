import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlinx.coroutines.CoroutineScope;
import tv.danmaku.biliplayerv2.service.IPlayerCoreService;

/** Models the relocated playable interface and the related core-wrapper constructor. */
public interface FlatPlayerSpeedSessionFixture {
    Params params();

    final class Params {
        public long getAvid() { return 1; }
        public long getCid() { return 2; }
    }

    final class Manager {}

    final class Owner {
        public final Manager manager = new Manager();
        public FlatPlayerSpeedSessionFixture active() { return null; }
        public Object run(FlatPlayerSpeedSessionFixture playable, ContinuationImpl continuation) { return null; }
        public Object other(Decoy value, ContinuationImpl continuation) { return null; }
    }

    interface Decoy {}

    final class Wrapper {
        public Wrapper(IPlayerCoreService core, Owner owner, CoroutineScope scope) {}
    }
}
