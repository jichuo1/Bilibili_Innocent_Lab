package kotlinx.serialization.protobuf;

import kotlinx.serialization.DeserializationStrategy;
import kotlinx.serialization.SerializationStrategy;

/** 测试夹具：只保留桥用到的两个公开方法（宿主里 `ProtoBuf#encodeToByteArray/decodeFromByteArray` 未混淆）。 */
public class ProtoBuf {
    public static ProtoBuf Default = new ProtoBuf();
    @SuppressWarnings("rawtypes")
    public byte[] encodeToByteArray(SerializationStrategy serializer, Object value) {
        return ("K:" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("rawtypes")
    public Object decodeFromByteArray(DeserializationStrategy deserializer, byte[] bytes) {
        return "K:" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
