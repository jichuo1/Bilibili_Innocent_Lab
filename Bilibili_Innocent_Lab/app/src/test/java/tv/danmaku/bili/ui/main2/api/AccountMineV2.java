package tv.danmaku.bili.ui.main2.api;

import java.util.List;
import com.bilibili.lib.homepage.mine.MenuGroupV2;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.encoding.Encoder;
import kotlinx.serialization.encoding.CompositeEncoder;

/** Deliberately renamed and out-of-order fields; unrelated list slots must never be pruned. */
public final class AccountMineV2 {
    public static final Companion Companion = new Companion();
    private final List<MenuGroupV2> z;
    private final List<MenuGroupV2> B;
    private final List<?> C;
    private final LiveTip N;
    public AccountMineV2(List<MenuGroupV2> sections, List<MenuGroupV2> sectionsV2, List<?> unrelated, LiveTip tip) {
        B = sections; z = sectionsV2; C = unrelated; N = tip;
    }
    public List<MenuGroupV2> directRenderGroups() { return z; }
    public List<?> unrelated() { return C; }
    public LiveTip tip() { return N; }
    public static final class Companion { public KSerializer<AccountMineV2> serializer() { return new Serializer(); } }
    public static final class Serializer implements KSerializer<AccountMineV2> {
        private static final SerialDescriptor DESC = descriptor("tv.danmaku.bili.ui.main2.api.AccountMineV2",
            "game_tips", "live_tip", "section_list_v2", "section_list");
        public SerialDescriptor getDescriptor() { return DESC; }
        public void serialize(Encoder encoder, Object instance) {
            AccountMineV2 value = (AccountMineV2)instance;
            CompositeEncoder out = encoder.beginStructure(DESC);
            out.encodeSerializableElement(DESC, 0, null, value.C);
            out.encodeNullableSerializableElement(DESC, 1, null, value.N);
            out.encodeSerializableElement(DESC, 2, null, value.z);
            out.encodeSerializableElement(DESC, 3, null, value.B);
            out.endStructure(DESC);
        }
    }
    /** R8 class name and serial name differ; repeated strings are intentionally legitimate. */
    public static final class LiveTip {
        public static final TipCompanion Companion = new TipCompanion();
        private final String x;
        private final long y;
        private final String z;
        public LiveTip(String text, long id, String url) { x = text; y = id; z = url; }
        public static final class TipCompanion { public KSerializer<LiveTip> serializer() { return new TipSerializer(); } }
        public static final class TipSerializer implements KSerializer<LiveTip> {
            private static final SerialDescriptor DESC = descriptor("original.AccountMineV2.LiveTip", "id", "text", "url");
            public SerialDescriptor getDescriptor() { return DESC; }
            public void serialize(Encoder encoder, Object instance) {
                LiveTip value = (LiveTip)instance;
                CompositeEncoder out = encoder.beginStructure(DESC);
                out.encodeLongElement(DESC, 0, value.y);
                out.encodeStringElement(DESC, 1, value.x);
                out.encodeStringElement(DESC, 2, value.z);
                out.endStructure(DESC);
            }
        }
    }
    private static SerialDescriptor descriptor(final String name, final String... elements) {
        return new SerialDescriptor() {
            public String getSerialName() { return name; }
            public int getElementsCount() { return elements.length; }
            public String getElementName(int index) { return elements[index]; }
        };
    }
}
