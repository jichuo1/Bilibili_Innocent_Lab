package kotlinx.serialization.encoding;
import kotlinx.serialization.descriptors.SerialDescriptor;
public interface CompositeEncoder {
    boolean shouldEncodeElementDefault(SerialDescriptor descriptor, int index);
    void encodeSerializableElement(SerialDescriptor descriptor, int index, Object serializer, Object value);
    void encodeNullableSerializableElement(SerialDescriptor descriptor, int index, Object serializer, Object value);
    void encodeStringElement(SerialDescriptor descriptor, int index, String value);
    void encodeLongElement(SerialDescriptor descriptor, int index, long value);
    void endStructure(SerialDescriptor descriptor);
}
