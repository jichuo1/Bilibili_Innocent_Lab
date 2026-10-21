package kotlinx.serialization.encoding;
import kotlinx.serialization.descriptors.SerialDescriptor;
public interface Encoder {
    CompositeEncoder beginStructure(SerialDescriptor descriptor);
}
