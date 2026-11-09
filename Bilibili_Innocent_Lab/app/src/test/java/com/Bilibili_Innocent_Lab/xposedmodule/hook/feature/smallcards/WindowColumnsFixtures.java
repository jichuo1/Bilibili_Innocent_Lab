package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.smallcards;

/**
 * 9.1 起宿主窗口档位的同形状替身：枚举常量带 type 与 columns，凑组按请求参数里的 type 找列数，
 * 找不到按 Normal。类名由测试注入，不冒用宿主包名。
 */
public final class WindowColumnsFixtures {
    private WindowColumnsFixtures() {}

    public enum WindowProfile {
        Normal(1, 2), Medium(2, 3), Large(3, 4);

        private final int type;
        private final int columns;

        WindowProfile(int type, int columns) {
            this.type = type;
            this.columns = columns;
        }

        public int getType() {
            return type;
        }

        public int getColumns() {
            return columns;
        }
    }

    /** 列数读取签名不对（返回 long）：不能被当作凑组大小的来源。 */
    public enum WrongWindowProfile {
        Normal;

        public int getType() {
            return 1;
        }

        public long getColumns() {
            return 2L;
        }
    }
}
