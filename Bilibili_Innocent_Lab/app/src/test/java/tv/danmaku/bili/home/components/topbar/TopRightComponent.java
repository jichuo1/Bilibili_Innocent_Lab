package tv.danmaku.bili.home.components.topbar;

import java.util.List;
import kotlin.coroutines.Continuation;

/**
 * 9.11.0(9110400) Compose 顶栏右上角项收集 lambda 的夹具。
 *
 * <p>真机上外层组件被 R8 混淆成 {@code topbar.k}，但 Kotlin 合成 lambda 的类描述符
 * 保留了原始外层类名，所以 {@code TopRightComponent$initTopRight$1$1} 这个名字是稳定的。
 * 夹具照抄这个形状，**两个 invoke 都必须在**：桥接的 {@code (Object, Object)} 就是
 * 定位器要靠签名排除掉的那个，只留一个的话"排除"这条纪律就测不出来了。
 */
public final class TopRightComponent {

    @SuppressWarnings("unused")
    public static final class initTopRight$1$1 {

        /** 桥接方法：Kotlin 为泛型 lambda 生成，签名是 (Object, Object)。 */
        public Object invoke(Object items, Object continuation) {
            return invoke((List) items, (Continuation) continuation);
        }

        /** 真正带类型的那个；参数 0 就是全部右上角项。 */
        public Object invoke(List items, Continuation continuation) {
            return items;
        }
    }
}
