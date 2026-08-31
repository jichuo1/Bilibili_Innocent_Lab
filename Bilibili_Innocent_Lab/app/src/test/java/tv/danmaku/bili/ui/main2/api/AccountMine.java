package tv.danmaku.bili.ui.main2.api;

import com.bilibili.lib.homepage.mine.MenuGroup;
import java.util.List;

/** 9.9.x fixture：同时存在 sectionList 与 sectionListV2，定位必须精确选择后者。 */
public final class AccountMine {
    public List<MenuGroup> sectionList;
    public List<MenuGroup> sectionListV2;
    public Object liveTip;
    public Object vipSectionRight;
}
