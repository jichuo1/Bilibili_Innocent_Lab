package tv.danmaku.bili.ui.main2.api;
import java.util.List;
import com.bilibili.lib.homepage.mine.MenuGroupV2;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.encoding.Encoder;
import kotlinx.serialization.encoding.CompositeEncoder;

/** Java records really reject reflective writes to their final instance fields on JVM 17. */
public record MineWriteFailureModel(List<MenuGroupV2> z) {
    public static final Companion Companion = new Companion();
    public static final class Companion { public KSerializer<MineWriteFailureModel> serializer() { return new Serializer(); } }
    public static final class Serializer implements KSerializer<MineWriteFailureModel> {
        private static final SerialDescriptor DESC = new SerialDescriptor() {
            public String getSerialName() { return "tv.danmaku.bili.ui.main2.api.MineWriteFailureModel"; }
            public int getElementsCount() { return 1; }
            public String getElementName(int index) { return "section_list_v2"; }
        };
        public SerialDescriptor getDescriptor() { return DESC; }
        public void serialize(Encoder encoder, Object instance) {
            CompositeEncoder out = encoder.beginStructure(DESC);
            out.encodeSerializableElement(DESC, 0, null, ((MineWriteFailureModel)instance).z);
            out.endStructure(DESC);
        }
    }
}
