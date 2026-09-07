import type { AnalyticsData, AnalyticsFilter, SummaryRow } from "./queries";
import { FEATURE_LABELS } from "./labels";
import { CAPABILITY_BY_ID } from "../capabilities";

export interface Insight {
  kind: "regression" | "concentration" | "observation";
  title: string;
  evidence: string;
  nextStep: string;
  row: SummaryRow;
  score: number;
}
const num = (row: SummaryRow, key: string) => Number(row[key] ?? 0);
const percent = (a: number, b: number) => b > 0 ? (a / b * 100).toFixed(1) + "%" : "—";
const cohort = ["host","module","framework","sdk","channel","abi","manufacturer","model","rom","framework_release","feature"];
function suggestion(reason: string): string {
  if (/RESOLV|MISSING|CLASS|METHOD|LOCAT|SIGNATURE/.test(reason)) {
    return "优先核对该宿主版本的类／方法定位和适配规则，再用现场诊断确认；原因代码不能单独证明定位失效。";
  }
  if (/INSTALL|HOOK|FRAMEWORK|API/.test(reason)) {
    return "优先复核框架 API、安装回执和模块授权链；对照相同宿主与模块版本的现场日志。";
  }
  return "先复现该版本组合，核对有界原因代码与现场诊断；遥测不含堆栈，不能直接定位源码行。";
}

/** Deterministic triage heuristics, not an LLM, confirmed bugs or causal attribution.
 * Compare like-for-like cohorts; period samples select each installation's last
 * observation, so repeated daily reports cannot satisfy sample-size thresholds.
 */
export function analyze(data: AnalyticsData, filter: AnalyticsFilter): Insight[] {
  // Outcome-selected samples have a conditioned denominator; do not call it regression.
  if (filter.state) return [];
  return data.features.slice(0, 400).filter(row => num(row,"failed") > 0 || num(row,"runtime_errors") > 0).map(row => {
    const beforeN = num(row,"before_n"), afterN = num(row,"after_n");
    const beforeFailed = num(row,"before_failed"), afterFailed = num(row,"after_failed");
    const currentRate = afterN > 0 ? afterFailed / afterN : 0;
    const previousRate = beforeN > 0 ? beforeFailed / beforeN : 0;
    const delta = (currentRate - previousRate) * 100;
    const regression = filter.days > 1 && beforeN >= 10 && afterN >= 10 && afterFailed >= 3 && delta >= 20;
    const concentration = num(row,"eligible_installations") >= 10 &&
      num(row,"affected") >= 3 && num(row,"failed") / Math.max(1,num(row,"eligible")) >= .2;
    const name = CAPABILITY_BY_ID.get(String(row.feature))?.label ?? FEATURE_LABELS[String(row.feature)] ?? String(row.feature);
    const reason = data.reasons.find(candidate => cohort.every(key => candidate[key] === row[key]));
    const runtimeOnly = num(row,"failed") === 0;
    const kind: Insight["kind"] = regression ? "regression" : concentration ? "concentration" : "observation";
    return {
      kind,
      title: name + (runtimeOnly ? "：运行处理异常线索" : regression ? "：近期失败比例上升" : concentration ? "：失败集中，建议优先复核" : "：少量失败线索，先观察"),
      evidence: num(row,"failed") === 0 ? "安装结果未见失败，但有 " + num(row,"runtime_error_installations") + " 个安装标识记录过运行处理异常。需结合现场诊断复核，不据此推断根因。" : regression
        ? "同一版本与环境组合：前段 " + beforeFailed + "/" + beforeN + "（" + percent(beforeFailed,beforeN) +
          "），后段 " + afterFailed + "/" + afterN + "（" + percent(afterFailed,afterN) + "），增加 " + delta.toFixed(1) + " 个百分点。每段按安装标识去重取最后一次结果。"
        : "有效安装标识 " + num(row,"eligible_installations") + "，其中 " + num(row,"affected") +
          " 个曾出现失败／缺失；失败快照 " + num(row,"failed") + "/" + num(row,"eligible") +
          "。"+(concentration ? "达到排查提示阈值，不代表已确认 Bug。" : "未达到提示阈值，不能判断版本回归。"),
      nextStep: (reason ? "主要记录代码：" + String(reason.reason) + "。" : "暂无匹配的原因代码。") +
        suggestion(String(reason?.reason ?? "")),
      row, score: (regression ? 2000 : concentration ? 1000 : 0) + num(row,"affected"),
    };
  }).sort((a,b) => b.score-a.score || String(a.row.feature).localeCompare(String(b.row.feature))).slice(0,12);
}
