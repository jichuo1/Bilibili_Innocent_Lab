## 执行计划：GitHub Release 描述可读化改写

### 背景确认
- 15 个历史 release（v1.0.4~v1.0.8 及全部 alpha）的正文 CHANGELOG 由 commit 标题机械生成，术语化且繁琐（v1.0.8 尤甚，且"新增/优化"重复双列）。
- 生成脚本为纯 Python 规则（CI 无 LLM），未来 release 采用**自动合并精简 + 只留用户感知变化**；Stable 用关键词翻译合并、Alpha 用原始标题去重合并（双通道差异化，已与你确认）。
- 排版（居中标题区块、下载表、反馈区、TIP/WARNING、脚注、APK 列表与 SHA）一律不动。

### A. 生成脚本改造
**`generate_stable_changelog.py`**
1. 新增 `USER_FACING_TRANSLATIONS` 映射：把术语化 commit 标题翻译为通俗短句并归类（新增/修复/优化）。样例：
   - `重构 (hook)：X` → `优化 Hook 机制：X`
   - `重构 (适配器)：X` → `优化版本适配：X`
   - `新功能 (净化)：X` → `新增净化功能：X`
   - `新功能 (播放器)：X` → `新增播放器选项：X`
   - `新功能 (评论)：X` → `新增评论选项：X`
   - `性能 (UI)：X` → `优化界面性能：X`
   - 同类多次合并且标注 `(×N)` 或"等"。
2. 新增 `filter_build_maintenance(entries)`：剔除 `build/chore/ci/docs/test` 及本地化前缀的纯维护条目，不作为"新增/修复/优化"逐条展示。
3. `render_categorized_entries` 增加去重：同一 commit 不重复出现在两个分类。

**`generate_alpha_changelog.py`**
- 增加去重 + `filter_build_maintenance`：过滤纯 build/docs/CI 条目，保留用户感知标题；不做关键词翻译（Alpha 成熟度低）。

### B. 模板（排版不动）
`stable.md` / `alpha.md` 仅保持 `{{CHANGELOG}}` 注入位置与整体结构不变；如需折叠的"维护"细节区，由生成器在 CHANGELOG 内输出 `<details>`，不改模板文件。

### C. 历史 15 个 release 改写
用 `gh release edit <tag> --notes <新正文>` 逐个重写全部 15 个 release，保留现有排版与 assets/prerelease 状态，仅把 CHANGELOG 换成翻译合并后的可读版并过滤纯 build/doc 条目。

### D. 测试同步
更新 `test_generate_stable_changelog.py` 以匹配新的过滤/去重/翻译行为（现有"### ✨ 新增""### ⚡ 优化\n\n- 无"断言需调整）。

### E. 验证
本地 `python3 -m unittest discover -s .github/release-templates -p 'test_*.py'` 全绿；grep 确认无 `{{TOKEN}}` 残留。

### 明确不做
不改 `render_release_template.py`、`validate_*`、`sync_lsposed_release.py`；不改 Gradle 源码/版本号；不引入 LLM。