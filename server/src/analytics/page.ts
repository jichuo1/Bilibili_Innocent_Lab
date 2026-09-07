import type { AnalyticsData, AnalyticsFilter, SummaryRow } from "./queries";
import { historyAvailable } from "./queries";
import { FEATURE_LABELS } from "./labels";
import { CAPABILITIES, CAPABILITY_BY_ID } from "../capabilities";
import { analyze } from "./insights";
import { ABI_CODES, DELIVERY_CHANNELS, FEATURE_STATES, FRAMEWORK_CODES, ROM_CODES } from "../constants";

export function escapeHtml(value: unknown): string {
  return String(value ?? "—").replace(/[&<>"']/g, c => ({
    "&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;",
  })[c]!);
}
const t = escapeHtml;
const num = (v: unknown) => Number.isFinite(Number(v)) ? Number(v) : 0;
const n = (v: unknown) => num(v).toLocaleString("zh-CN");
const pct = (a: unknown, b: unknown) => num(b) > 0 ? (num(a)/num(b)*100).toFixed(1)+"%" : "—";
export function installationRate(row: SummaryRow): string { return pct(row.installed,row.eligible); }
const labels: Record<string,string> = {
  not_collected:"未采集（旧报告）", hyperos:"HyperOS",miui:"MIUI",coloros:"ColorOS",realme_ui:"realme UI",oxygenos:"OxygenOS",
  originos:"OriginOS",funtouchos:"Funtouch OS",one_ui:"One UI",emui:"EMUI",harmonyos:"HarmonyOS",flyme:"Flyme",lineageos:"LineageOS",
  partial:"部分安装", unknown:"尚无独立证据", installed:"安装成功", failed:"安装失败", missing:"适配缺失", not_applicable:"不适用", safe_skip:"安全跳过",
  standard:"标准框架", npatch:"NPatch", lspatch_manager:"LSPatch 管理器", lspatch_embed:"LSPatch 内嵌",
  lsposed:"LSPosed", vector:"Vector", irena:"Irena", lspatch:"LSPatch", __other__:"其他",
};
const colors = ["#087f8c","#5464d8","#d28b16","#c55373","#438965","#785bb2","#747b88","#2386b8","#b5c0cc"];
function label(dimension: string, value: unknown): string {
  const raw = String(value ?? "unknown");
  if (raw === "unknown") return "未知";
  if (raw === "__other__" || raw === "not_collected") return labels[raw]!;
  if (dimension === "sdk") return "Android SDK "+raw;
  if (dimension === "host" || dimension === "module") return "vCode "+raw;
  return labels[raw] ?? raw;
}
function feature(row: SummaryRow): string {
  return "<strong>"+t(CAPABILITY_BY_ID.get(String(row.feature))?.label ?? FEATURE_LABELS[String(row.feature)] ?? "未知功能")+"</strong><br><code>"+t(row.feature)+"</code>";
}
function url(filter: AnalyticsFilter, patch: Record<string,string|number> = {}): string {
  const params = new URLSearchParams();
  params.set("environment",filter.environment);
  params.set("schema",String(filter.schema));
  if (filter.parent) params.set("parent",filter.parent);
  params.set("days",[1,7,14,30].includes(filter.days) ? String(filter.days) : "30");
  if (filter.custom) { params.set("start",filter.startDay); params.set("end",filter.endDay); }
  for (const key of ["host","module","sdk","framework","channel","abi","feature","state","manufacturer","model","rom","framework_release"] as const) {
    if (filter[key]) params.set(key,String(filter[key]));
  }
  for (const [key,value] of Object.entries(patch)) {
    if (value === "" || value === 0) params.delete(key); else params.set(key,String(value));
  }
  return "/?"+params.toString();
}
function cohortLink(filter: AnalyticsFilter, row: SummaryRow): string {
  return url(filter,{
    host:num(row.host),module:num(row.module),sdk:num(row.sdk),framework:String(row.framework ?? ""),
    channel:String(row.channel ?? ""),abi:String(row.abi ?? ""),feature:String(row.feature ?? ""),
    parent:CAPABILITY_BY_ID.get(String(row.feature))?.parent??"",
    manufacturer:String(row.manufacturer??""),model:String(row.model??""),rom:String(row.rom??""),framework_release:String(row.framework_release??""),
  });
}
const empty = (message = "暂无可展示的数据") => "<div class='empty'>"+t(message)+"</div>";
function table(headers: string[], rows: string[][], limit=100): string {
  if (!rows.length) return empty();
  return (rows.length>limit ? "<p class='note'>仅展示排序靠前的 "+limit+" 组，请收窄筛选。其他分组不参与上方自动线索。</p>" : "")+
    "<div class='table-scroll'><table><thead><tr>"+headers.map(h=>"<th scope='col'>"+t(h)+"</th>").join("")+
    "</tr></thead><tbody>"+rows.slice(0,limit).map(r=>"<tr>"+r.map(c=>"<td>"+c+"</td>").join("")+"</tr>").join("")+
    "</tbody></table></div>";
}
function distribution(data: AnalyticsData, filter: AnalyticsFilter, dimension: string, title: string, donut: boolean): string {
  const rows = data.distributions.filter(r=>r.dimension===dimension);
  const total = rows.reduce((s,r)=>s+num(r.installations),0);
  if (!total) return "<section class='panel'><h3>"+t(title)+"</h3>"+empty()+"</section>";
  let offset=0;
  const link = (r: SummaryRow, content: string) => r.value === "__other__" || (dimension==="sdk" && r.value==="unknown")
    ? content : "<a href='"+t(url(filter,{[dimension]:String(r.value)}))+"'>"+content+"</a>";
  const segments = rows.map((r,i)=>{
    const size=num(r.installations)/total*100;
    const circle="<circle cx='60' cy='60' r='46' fill='none' stroke='"+colors[i%colors.length]+
      "' stroke-width='16' pathLength='100' stroke-dasharray='"+size+" "+(100-size)+
      "' stroke-dashoffset='"+(-offset)+"' transform='rotate(-90 60 60)'><title>"+
      t(label(dimension,r.value))+": "+n(r.installations)+" ("+pct(r.installations,total)+")</title></circle>";
    offset+=size; return link(r,circle);
  }).join("");
  const legend=rows.map((r,i)=>"<li>"+link(r,
    "<span class='legend-label'><i style='background:"+colors[i%colors.length]+"'></i>"+t(label(dimension,r.value))+
    "</span><span>"+n(r.installations)+" <small>"+pct(r.installations,total)+"</small></span>")+"</li>").join("");
  const max=Math.max(...rows.map(r=>num(r.installations)),1);
  const bars=rows.map((r,i)=>"<li>"+link(r,"<span class='bar-label'>"+t(label(dimension,r.value))+
    "<span>"+n(r.installations)+" · "+pct(r.installations,total)+"</span></span><span class='track'><span style='width:"+
    (num(r.installations)/max*100)+"%;background:"+colors[i%colors.length]+"'></span></span>")+"</li>").join("");
  return "<section class='panel distribution'><div class='section-heading'><h3>"+t(title)+"</h3><span class='badge'>"+n(total)+" 安装标识</span></div>"+
    (donut ? "<div class='donut-layout'><svg viewBox='0 0 120 120' role='img' aria-label='"+t(title)+"分布'>"+segments+
    "<text x='60' y='59' text-anchor='middle' class='donut-total'>"+n(total)+"</text><text x='60' y='74' text-anchor='middle' class='donut-label'>安装标识</text></svg>"+
    "<ul class='legend'>"+legend+"</ul></div>" : "<ul class='hbars'>"+bars+"</ul>")+"</section>";
}
function trend(rows: SummaryRow[], filter: AnalyticsFilter, failure: boolean): string {
  const days=Array.from({length:filter.days},(_,i)=>new Date(Date.parse(filter.startDay+"T00:00:00Z")+i*86400000).toISOString().slice(0,10));
  const points=days.map(day=>rows.find(r=>r.day===day));
  if (!rows.length) return empty("当前范围还没有报告");
  const max=failure ? 100 : Math.max(1,...points.map(r=>num(r?.reports)));
  const width=620, left=45, right=600, top=20, bottom=165, step=(right-left)/days.length;
  let svg="<svg viewBox='0 0 "+width+" 205' role='img' aria-label='"+(failure?"每日失败／缺失快照比例":"每日报告数量")+"'>";
  for (const fraction of [0,.5,1]) {
    const y=bottom-(bottom-top)*fraction;
    svg+="<line x1='"+left+"' y1='"+y+"' x2='"+right+"' y2='"+y+"' class='gridline'/><text x='36' y='"+(y+4)+"' text-anchor='end' class='axis'>"+
      (failure ? (fraction*100)+"%" : n(Math.round(max*fraction)))+"</text>";
  }
  days.forEach((day,i)=>{
    const row=points[i], x=left+step*i+step/2;
    const available=failure ? num(row?.eligible)>0 : true;
    const value=failure ? num(row?.failed)/Math.max(1,num(row?.eligible))*100 : num(row?.reports);
    const height=value/max*(bottom-top);
    if (available) {
      svg+="<rect x='"+(x-step*.3)+"' y='"+(bottom-height)+"' width='"+(step*.6)+"' height='"+height+
        "' rx='3' fill='"+(failure?"#c34c54":"#087f8c")+"'><title>"+day+"："+(failure ? pct(row?.failed,row?.eligible)+"，"+n(row?.failed)+"/"+n(row?.eligible) : n(value)+" 份报告")+"</title></rect>";
      if (failure && value===0) svg+="<circle cx='"+x+"' cy='"+bottom+"' r='3' fill='#438965'><title>"+day+"：0%，有有效样本</title></circle>";
    } else svg+="<text x='"+x+"' y='159' text-anchor='middle' class='axis'>—</text>";
    if (i%Math.max(1,Math.ceil(filter.days/7))===0 || i===days.length-1) {
      svg+="<text x='"+x+"' y='190' text-anchor='middle' class='axis'>"+day.slice(5)+"</text>";
    }
  });
  return svg+"</svg>";
}
function select(name: string, title: string, values: readonly string[], current: string, display=(v:string)=>labels[v]??v): string {
  return "<label>"+title+"<select name='"+name+"'><option value=''>全部</option>"+values.map(v=>
    "<option value='"+t(v)+"'"+(current===v?" selected":"")+">"+t(display(v))+"</option>").join("")+"</select></label>";
}
function filters(filter: AnalyticsFilter, data: AnalyticsData): string {
  const list=(dimension:string)=>data.distributions.filter(r=>r.dimension===dimension && r.value!=="__other__").map(r=>
    "<option value='"+t(r.value)+"'>"+t(label(dimension,r.value))+"</option>").join("");
  const dates="<label>起始日期（UTC）<input type='date' name='start' value='"+(filter.custom?filter.startDay:"")+"'></label>"+
    "<label>结束日期（UTC）<input type='date' name='end' value='"+(filter.custom?filter.endDay:"")+"'></label>";
  return "<form method='get' class='filters' id='filters'><div class='filter-main'>"+
    "<label>数据环境<select name='environment'><option value='production'"+(filter.environment==="production"?" selected":"")+">正式环境 · Release</option>"+
    "<option value='staging'"+(filter.environment==="staging"?" selected":"")+">测试环境 · Debug</option></select></label>"+
    "<label>诊断粒度<select name='schema'><option value='2'"+(filter.schema===2?" selected":"")+">细分能力 · 新版</option>"+
    "<option value='1'"+(filter.schema===1?" selected":"")+">功能大组 · 旧版</option></select></label>"+
    "<label>最近时间<select name='days'>"+[1,7,14,30].map(d=>"<option value='"+d+"'"+(filter.days===d?" selected":"")+">"+(d===1?"今天":"最近 "+d+" 天")+"</option>").join("")+"</select></label>"+
    "<label>宿主 versionCode<input name='host' type='number' min='1' max='2147483647' placeholder='全部版本' list='host-values' value='"+(filter.host||"")+"'></label>"+
    "<label>模块 versionCode<input name='module' type='number' min='1' max='2147483647' placeholder='全部版本' list='module-values' value='"+(filter.module||"")+"'></label>"+
    "<button type='submit'>应用筛选</button><a class='reset' href='/?environment="+filter.environment+"&amp;schema="+filter.schema+"'>重置</a></div>"+
    "<details class='advanced'"+(filter.custom||filter.sdk||filter.framework||filter.channel||filter.abi||filter.feature||filter.state||filter.manufacturer||filter.model||filter.rom||filter.framework_release||filter.parent?" open":"")+"><summary>更多筛选 · 系统、框架、功能与日期</summary><div class='filter-grid'>"+
    "<label>Android SDK<input name='sdk' type='number' min='27' max='100' placeholder='全部系统' value='"+(filter.sdk||"")+"'></label>"+
    select("parent","功能大组",[...new Set(CAPABILITIES.map(capability=>capability.parent))],filter.parent,v=>FEATURE_LABELS[v]??v)+
    select("framework","框架",FRAMEWORK_CODES,filter.framework)+select("channel","交付通道",DELIVERY_CHANNELS,filter.channel)+
    select("abi","CPU 架构",ABI_CODES,filter.abi)+
    "<label>厂商<input name='manufacturer' list='manufacturer-values' maxlength='64' placeholder='全部厂商' value='"+t(filter.manufacturer)+"'></label>"+
    "<label>机型<input name='model' list='model-values' maxlength='64' placeholder='全部机型' value='"+t(filter.model)+"'></label>"+
    select("rom","ROM 类别",[...ROM_CODES,"not_collected"],filter.rom)+
    "<label>框架版本（服务自报）<input name='framework_release' list='release-values' maxlength='160' placeholder='全部版本' value='"+t(filter.framework_release)+"'></label>"+
    select("state","安装结果",FEATURE_STATES,filter.state)+
    "<label>功能 ID<input name='feature' list='feature-values' pattern='[a-z][a-z0-9_]{0,63}' placeholder='全部功能' value='"+t(filter.feature)+"'></label>"+dates+
    "</div><p class='sub'>自选日期需同时填写，且限最近 30 天；填写后优先于“最近时间”。结果筛选会改变分母，因此暂停自动回归判断。</p></details>"+
    "<datalist id='manufacturer-values'>"+list("manufacturer")+"</datalist><datalist id='model-values'>"+list("model")+
    "</datalist><datalist id='release-values'>"+list("framework_release")+"</datalist>"+
    "<datalist id='host-values'>"+list("host")+"</datalist><datalist id='module-values'>"+list("module")+"</datalist>"+
    "<datalist id='feature-values'>"+Object.entries(filter.schema===2 ? Object.fromEntries(CAPABILITIES.map(c=>[c.id,c.label])) : FEATURE_LABELS).map(([id,name])=>"<option value='"+t(id)+"'>"+t(name)+"</option>").join("")+"</datalist></form>";
}
function capabilityDirectory(data: AnalyticsData, filter: AnalyticsFilter): string {
  if (filter.schema !== 2) return "";
  const rows=CAPABILITIES.map(capability=>{
    const matches=data.features.filter(row=>row.feature===capability.id);
    const hasEvidence=matches.some(row=>num(row.eligible)>0);
    const unknown=matches.some(row=>num(row.unknown)>0);
    const status=hasEvidence ? "有独立安装样本" : unknown ? "已声明，证据未完成" :
      matches.some(row=>num(row.reports)>0) ? "仅有不适用／跳过记录" : "未获得可用样本";
    return ["<a href='"+t(url(filter,{feature:capability.id,parent:capability.parent}))+"#results'>"+t(capability.label)+"</a>",
      "<a href='"+t(url(filter,{parent:capability.parent,feature:"",state:""}))+"'>"+t(FEATURE_LABELS[capability.parent]??capability.parent)+"</a>",
      capability.runtime===2?"安装 / 触发 / 应用":capability.runtime===1?"安装 / 触发；应用未验证":"安装；运行阶段未接入",
      status];
  });
  return "<section class='panel'><details><summary>细分能力覆盖目录 · "+CAPABILITIES.length+" 项</summary><div class='details-content'>"+
    "<p class='sub'>这是独立功能和技术路径的代码覆盖目录，不是用户开关清单；叶能力数量不是开关数。未知、关闭、不适用或未上报都可能造成缺样本；每条状态只描述当前筛选范围和已返回分组。</p>"+
    table(["细分能力","所属大组","可采集的证据","当前样本"],rows,256)+"</div></details></section>";
}

const CSS = [
  ":root{color-scheme:light;--ink:#172a3e;--muted:#617286;--line:#dce4ed;--accent:#087f8c;--nav:#142b42;--bg:#f2f5f9;--bad:#b83d49}",
  "*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.6 system-ui,-apple-system,'Segoe UI',sans-serif}a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible{outline:3px solid #e2a536;outline-offset:3px}",
  ".rail{position:fixed;inset:0 auto 0 0;width:208px;background:var(--nav);color:#e6eef7;padding:28px 22px;display:flex;flex-direction:column;gap:24px}.brand{font-size:19px;font-weight:750}.rail small{font-size:12px;color:#9cb4cb;letter-spacing:.12em}.rail nav{display:grid;gap:10px}.rail nav a{color:#c9d9e8;padding:10px 12px;border-radius:8px;font-size:14px}.rail nav a:hover{background:#223d57;color:white;text-decoration:none}.rail-note{margin-top:auto;font-size:13px;color:#a8bfd2}",
  "main{margin-left:208px;max-width:1650px;padding:28px 32px 40px}.pagehead{display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:20px}h1{font-size:28px;line-height:1.3;margin:0}h2{font-size:21px;margin:0}h3{font-size:16px;margin:0}.sub{color:var(--muted);font-size:13px;margin:6px 0}.eyebrow{font-size:12px;letter-spacing:.1em;color:var(--muted);margin-bottom:5px}.status,.badge{font-size:12px;padding:4px 9px;border-radius:7px;background:#e4f1f3;color:#21616a;white-space:nowrap}.badge{background:#eef2f6;color:var(--muted)}",
  ".filters,.panel,.card{background:white;border:1px solid var(--line);border-radius:12px}.filters{padding:18px 20px;margin-bottom:20px}.filter-main{display:flex;flex-wrap:wrap;align-items:end;gap:12px}label{display:grid;gap:5px;font-size:14px;color:#52667b}input,select,button{font:inherit;min-height:42px;padding:8px 10px;border:1px solid #bac8d8;border-radius:7px;color:var(--ink);background:white;max-width:100%}input{width:165px}button{background:var(--accent);color:white;border:0;padding:10px 18px;font-size:14px;cursor:pointer}.reset{padding:10px;font-size:14px}.advanced{margin-top:14px;border-top:1px solid var(--line);padding-top:12px}summary{cursor:pointer;font-size:14px;font-weight:650}.filter-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-top:14px}.filter-grid input,.filter-grid select{width:100%}",
  ".cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:24px}.card{padding:18px 20px}.card .label{font-size:14px;color:var(--muted)}.metric{font-size:30px;font-weight:750;letter-spacing:-.03em;margin:7px 0}.bad{color:var(--bad)}.section-heading{display:flex;justify-content:space-between;gap:12px;align-items:center;margin-bottom:16px}.section-intro{margin:26px 0 14px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.grid.three{grid-template-columns:repeat(3,minmax(0,1fr))}.panel{padding:20px;min-width:0;margin-bottom:18px}.grid>.panel{margin-bottom:0}.grid{margin-bottom:18px}.empty{padding:35px 16px;background:#f5f8fb;border:1px dashed #d5e0e9;border-radius:9px;color:var(--muted);text-align:center;font-size:14px}.notice,.note{padding:12px 16px;border-left:3px solid #cb9325;background:#fff7e8;border-radius:6px;font-size:14px}.notice{margin-bottom:18px}.note{color:#755414}.empty strong{display:block;font-size:18px;color:var(--ink)}",
  ".donut-layout{display:flex;align-items:center;gap:18px;flex-wrap:wrap}.donut-layout svg{width:142px;flex-shrink:0}.donut-total{font-size:19px;font-weight:750;fill:var(--ink)}.donut-label{font-size:9px;fill:var(--muted)}.legend,.hbars{list-style:none;margin:0;padding:0;flex:1;min-width:140px}.legend li{padding:5px 0;font-size:14px}.legend a,.legend li>span{display:flex;justify-content:space-between;gap:8px}.legend-label{display:inline-flex;align-items:center;gap:7px}.legend i{display:inline-block;width:9px;height:9px;border-radius:3px;flex-shrink:0}.legend small{font-size:12px;color:var(--muted);margin-left:7px}.hbars li{margin:12px 0}.hbars a{display:block}.bar-label{display:flex;justify-content:space-between;gap:8px;font-size:14px;margin-bottom:5px}.bar-label>span{color:var(--muted);font-size:12px}.track{display:block;height:8px;background:#edf1f6;border-radius:9px;overflow:hidden}.track>span{display:block;height:100%;border-radius:9px}.distribution h3{font-size:16px}svg{max-width:100%;height:auto}.trend svg{width:100%}.gridline{stroke:#e0e7ef;stroke-width:1}.axis{fill:#62768c;font-size:12px}.legend-inline{display:flex;gap:16px;font-size:13px;color:var(--muted)}",
  ".insights{display:grid;gap:12px}.insight{border:1px solid var(--line);border-left:4px solid #c5a058;padding:16px 18px;border-radius:9px}.insight.regression{border-left-color:#bd4656}.insight.concentration{border-left-color:#c28728}.insight p{font-size:14px;margin:8px 0}.insight .meta{font-size:12px;color:var(--muted);overflow-wrap:anywhere}.insight a{display:inline-block;font-size:14px;font-weight:650}.table-scroll{overflow:auto}table{border-collapse:collapse;width:100%;font-size:14px;white-space:nowrap}th,td{text-align:left;padding:11px 12px;border-bottom:1px solid #e7edf3}th{color:var(--muted);font-weight:600;background:#f7f9fc}tbody tr:hover{background:#f7fbfc}code{font-size:12px;color:var(--muted);overflow-wrap:anywhere}.table-scroll .sub{margin:0}.pill{font-size:12px;padding:3px 7px;border-radius:6px;background:#fff0d8;color:#825912}.good{background:#e6f3eb;color:#276b49}.details-content{margin-top:15px}.method p{font-size:14px;color:#52667b}.footer{font-size:12px;color:var(--muted);margin-top:26px}.muted{color:var(--muted)}.capability{display:flex;flex-direction:column;justify-content:center;min-height:220px}",
  "@media(min-width:1600px){.cards{grid-template-columns:repeat(4,minmax(0,1fr))}}@media(max-width:1200px){.rail{width:175px;padding:24px 15px}main{margin-left:175px;padding:22px}.grid.three{grid-template-columns:repeat(2,minmax(0,1fr))}.filter-grid{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:820px){.rail{position:static;width:auto;padding:14px 18px;gap:10px}.rail nav{display:flex;flex-wrap:wrap;gap:4px}.rail nav a{padding:4px 8px}.rail-note,.rail small{display:none}main{margin-left:0;padding:18px}.cards{grid-template-columns:repeat(2,minmax(0,1fr))}.grid,.grid.three{grid-template-columns:1fr}.filter-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.pagehead{align-items:flex-start}h1{font-size:24px}.panel{padding:17px}.metric{font-size:26px}}@media(max-width:450px){.filter-main>label{flex:1;min-width:130px}.filter-main input{width:100%}.filter-grid{grid-template-columns:1fr}.cards{gap:10px}.card{padding:14px}.pagehead .status{display:none}.donut-layout{gap:12px}}@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}",
].join("\n");

export function renderPage(data: AnalyticsData, filter: AnalyticsFilter, now=Date.now()): string {
  const overview=data.overview[0]??{}, insights=analyze(data,filter);
  const blocked=num(overview.query_blocked)>0;
  const environment=filter.environment==="production"?"正式环境":"测试环境";
  if (blocked) return "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>请收窄分析范围</title><style>"+CSS+
    "</style></head><body><main><h1>请收窄分析范围</h1>"+filters(filter,data)+
    "<div class='notice'>当前范围超过分析工作量上限，未返回截断样本。请缩短日期，或按宿主、模块、框架、交付通道筛选后重试。图表未计算，不代表没有报告或没有故障。</div></main></body></html>";
  const cards=[
    ["已保存报告",n(overview.reports),"每天去重快照；手动更新不增份数",""],
    ["参与安装标识",n(overview.installations),"范围内去重，不等于用户人数",""],
    ["出现失败的安装标识",n(overview.affected),"至少一次安装失败／适配缺失","bad"],
    ["完整安装率",installationRate(overview),"成功 ÷（成功＋部分＋失败＋缺失）",""],
  ];
  const signalHtml=insights.map(i=>"<article class='insight "+i.kind+"'><h3>"+t(i.title)+"</h3>"+
    "<div class='meta'>宿主 "+t(i.row.host)+" · 模块 "+t(i.row.module)+" · "+t(label("framework",i.row.framework))+
    " · SDK "+t(i.row.sdk)+" · "+t(label("channel",i.row.channel))+" · "+t(i.row.abi)+" · "+t(i.row.manufacturer)+" "+t(i.row.model)+" · "+t(label("rom",i.row.rom))+" · "+t(i.row.framework_release)+"</div>"+
    "<p>"+t(i.evidence)+"</p><p class='muted'>"+t(i.nextStep)+"</p><a href='"+t(cohortLink(filter,i.row))+"#results'>查看这个组合 →</a></article>").join("");
  const featureRows=data.features.map(r=>[
    "<a href='"+t(cohortLink(filter,r))+"#results'>"+feature(r)+"</a>",
    t(r.host)+" / "+t(r.module),t(label("framework",r.framework))+"<br><span class='sub'>SDK "+t(r.sdk)+" · "+t(r.abi)+"<br>"+t(label("channel",r.channel))+"<br>"+t(r.manufacturer)+" "+t(r.model)+" · "+t(label("rom",r.rom))+"<br>"+t(r.framework_release)+"</span>",
    n(r.installations),n(r.reports),n(r.installed),"<span class='bad'>"+n(r.failed)+"</span>",installationRate(r),
    n(r.unknown), n(r.runtime_errors),
    (CAPABILITY_BY_ID.get(String(r.feature))?.runtime===0 && filter.schema===2 ? "未接入" : n(r.observed))+" / "+
    ((CAPABILITY_BY_ID.get(String(r.feature))?.runtime??2)<2 && filter.schema===2 ? "未验证" : n(r.applied)),num(r.eligible_installations)<10?"<span class='pill'>样本不足</span>":"<span class='pill good'>可观察趋势</span>",
  ]);
  return "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"+
    "<title>无辜实验室 · 适配健康分析</title><style>"+CSS+"</style></head><body>"+
    "<aside class='rail'><div><small>INNOCENT LAB</small><div class='brand'>适配健康分析</div></div><nav aria-label='看板导航'>"+
    "<a href='#overview'>01　数据概览</a><a href='#signals'>02　问题线索</a><a href='#distribution'>03　环境分布</a>"+
    "<a href='#results'>04　功能与原因</a><a href='#method'>05　统计口径</a></nav><div class='rail-note'>仅本人可见<br>只读分析 · 不共享原始数据</div></aside>"+
    "<main><header class='pagehead' id='overview'><div><div class='eyebrow'>TELEMETRY / HEALTH OVERVIEW</div><h1>适配健康总览</h1>"+
    "<p class='sub'>"+environment+" · "+filter.startDay+" — "+filter.endDay+"（UTC） · "+(filter.schema===2?"细分能力":"旧版大组")+"</p></div><span class='status'>● 私人看板 · 登录保护</span></header>"+
    filters(filter,data)+(blocked ? "<div class='notice'><strong>当前范围超过分析工作量上限，未返回截断样本。</strong>请缩短日期，或按宿主、模块、框架、交付通道收窄范围后再查询。</div>" :
    num(overview.reports)===0 ? "<div class='notice'><strong>当前范围还没有报告</strong> · 可切换环境、诊断粒度或放宽筛选。新版细分数据需新客户端重新授权并上报；旧报告无法补齐。没有数据不等于没有问题。</div>":"")+
    (num(overview.incomplete_reports)>0 ? "<div class='notice'>"+n(overview.incomplete_reports)+" 份报告的安装链未完成或失败。其结果不进入成功率和回归判断，请先检查授权与宿主启动诊断。</div>":"")+
    (num(overview.unknown)>0 ? "<div class='notice'>存在 "+n(overview.unknown)+" 条未取得独立证据的能力记录，不能将它们解释为安装成功。</div>":"")+
    (filter.state?"<div class='notice'>已按安装结果筛选，比例只描述筛选记录；自动回归判断已暂停。清除结果筛选后可查看问题线索。</div>":"")+
    "<div class='cards'>"+cards.map(([name,value,note,cls])=>"<div class='card'><div class='label'>"+name+"</div><div class='metric "+cls+"'>"+(blocked?"—":value)+"</div><div class='sub'>"+note+"</div></div>").join("")+"</div>"+
    "<div class='grid'><section class='panel trend'><div class='section-heading'><h3>报告到达趋势</h3><span class='badge'>"+filter.days+" 天</span></div>"+
    trend(data.daily,filter,false)+"<p class='sub'>最近报告日期："+t(overview.latest_day)+" · 宿主版本 "+n(overview.host_versions)+" 个</p></section>"+
    "<section class='panel trend'><div class='section-heading'><h3>每日失败／缺失比例</h3><span class='badge'>功能快照口径</span></div>"+
    trend(data.daily,filter,true)+"<p class='sub'>失败＋缺失＋部分安装 ÷ 有效安装结果；“—”为无有效样本，绿点为有样本且失败为零。</p></section></div>"+
    "<section class='panel' id='signals'><div class='section-heading'><h2>自动问题线索</h2><span class='badge'>规则归纳 · 非已确认 Bug</span></div>"+
    "<p class='sub'>按影响安装标识数排序。前段至 "+t(new Date(Date.parse(filter.splitDay+"T00:00:00Z")-86400000).toISOString().slice(0,10))+
    "，后段从 "+filter.splitDay+" 起；同一宿主／模块／框架及其版本／SDK／通道／ABI／厂商／机型／ROM 内比较。单日范围不作前后对比。</p>"+
    (data.features.length>400?"<p class='note'>分组超过 400，当前线索只覆盖优先展示的分组，请增加筛选。</p>":"")+
    "<div class='insights'>"+(signalHtml||empty(filter.state?"清除“安装结果”筛选后恢复自动归纳":num(overview.reports)===0?"等待真实报告后生成线索":"当前没有可归纳的失败线索；这不代表全部功能正常"))+"</div></section>"+
    "<div class='section-intro' id='distribution'><h2>版本与运行环境</h2><p class='sub'>每个安装标识仅取所选范围内最后一份匹配快照，各图总量一致；点击分类可筛选。前 8 类以外合并为“其他”。</p></div>"+
    "<div class='grid three'>"+
    distribution(data,filter,"host","宿主版本分布",false)+distribution(data,filter,"module","模块版本分布",false)+distribution(data,filter,"sdk","Android 系统分布",true)+
    distribution(data,filter,"framework","框架分布",true)+distribution(data,filter,"channel","交付通道分布",true)+distribution(data,filter,"abi","CPU 架构分布",true)+
    distribution(data,filter,"manufacturer","厂商分布",true)+distribution(data,filter,"model","机型分布",false)+
    distribution(data,filter,"rom","ROM 分布",true)+distribution(data,filter,"framework_release","框架版本分布（服务自报）",false)+
    "</div><section class='panel'><h3>设备与框架版本口径</h3>"+
    "<p class='sub'>厂商和机型为系统提供的产品名称，ROM 为本机识别的类别，不含完整构建指纹。未识别为“未知”，旧报告为“未采集”。框架版本按“框架 / 版本名称 / versionCode”展示，来自已连接框架服务；不是扫描到的管理器 APK 版本，也不是 API 等级。版本代码 0 表示未知。以上均为客户端自报，不是设备或框架真实性认证。</p></section>"+
    "<section class='panel' id='results'><div class='section-heading'><h2>功能安装结果</h2><span class='badge'>失败影响优先</span></div>"+
    "<p class='sub'>安装成功不等于功能已生效，部分安装不算完整成功。“触发／应用”为含相应证据的快照数，不是动作次数。不适用、安全跳过不进入成功率分母。</p>"+
    table(["功能","宿主 / 模块","运行环境","安装标识","快照","完整安装","未完整安装","完整安装率","证据未知","运行异常快照","触发 / 应用","样本提示"],featureRows,400)+"</section>"+
    "<section class='panel'><details><summary>大组补充运行证据（不与子能力重复计数）</summary><div class='details-content'>"+
    "<p class='sub'>大组只能说明某条处理路径被观察到，不能代替每个子能力的证据。未单独验证清理前后状态的路径不会报告子项已应用。</p>"+
    table(["大组","报告","部分可用快照","失败／缺失","证据未知","有触发证据","运行异常快照"],data.groups.map(r=>[t(FEATURE_LABELS[String(r.feature)]??r.feature),n(r.reports),n(r.partial),n(r.failed),n(r.unknown),n(r.observed),n(r.runtime_errors)]),50)+
    "</div></details></section>"+
    capabilityDirectory(data,filter)+
    "<section class='panel'><h2>失败原因</h2><p class='sub'>有界代码用于缩小排查范围，不包含用户日志或异常原文。</p>"+
    table(["功能","宿主 / 模块","框架 / SDK","通道 / ABI","原因代码","失败快照","涉及安装标识"],data.reasons.map(r=>[
      feature(r),t(r.host)+" / "+t(r.module),t(r.framework)+" / "+t(r.sdk),t(r.channel)+" / "+t(r.abi),
      "<code>"+t(r.reason)+"</code>",n(r.reports),n(r.installations),
    ]),100)+"</section>"+
    "<section class='panel'><details><summary>每日明细与长期聚合</summary><div class='details-content'>"+
    table(["UTC 日期","报告","安装标识","有效功能快照","失败/缺失","失败比例"],data.daily.map(r=>[t(r.day),n(r.reports),n(r.installations),n(r.eligible),n(r.failed),pct(r.failed,r.eligible)]),30)+
    "<h3>已落库的长期聚合</h3><p class='sub'>每日单元至少 10 个安装样本才保存。功能样本累计不能解释为用户人数。</p>"+
    (historyAvailable(filter)?table(["UTC 日期","功能样本累计","安装成功累计","失败/缺失累计"],data.history.map(r=>[t(r.day),n(r.feature_samples),n(r.installed),n(r.failed)]),30):
      empty("当前长期聚合不支持设备、框架版本、SDK、通道、ABI、结果或大组映射筛选；当前筛选下隐藏，不展示不匹配的总数。"))+
    "</div></details></section>"+
    "<section class='panel method' id='method'><details><summary>统计口径与自动分析规则</summary>"+
    "<p>问题线索是本地规则计算，不调用外部 AI 服务，不确认 Bug 或根因。近期上升提示要求前后段各至少 10 个有效安装标识、后段至少 3 个失败样本，且失败比例增加至少 20 个百分点；这是排查阈值，不是统计显著性或因果证明。</p>"+
    "<p>失败集中提示要求至少 10 个有效安装标识、至少 3 个曾失败的标识、失败快照占比至少 20%。前后段每个安装标识分别取最后一次功能结果；同一标识可能同时出现在两段。</p>"+
    "<p>安装标识会轮换，不代表实名用户或唯一物理设备。不同版本组之间不可直接相加。只有启用遥测且回执可用的样本会被统计，存在自选择偏差。</p>"+
    "<p>版本以 versionCode 展示，不猜测营销版本号。系统仅为 Android SDK。设备和 ROM 仅按当前采集证据展示。不展示缺乏证据的崩溃率、精确耗时或 DexKit 使用率。</p>"+
    "<p>新旧粒度分开查询；细分目录中的“未获得样本”不等于已关闭或功能正常。主题、模块界面、日志等本地设置不进入宿主安装成功率。运行证据仅表明当前宿主进程曾发生相应阶段，不是当天操作次数。看板仅运行只读汇总，不展示原始 JSON、安装键或删除令牌，不延长原始报告保存期限；无第三方脚本、字体、图表服务，也不在后台轮询。</p>"+
    "</details></section><footer class='footer'>查询时间 "+t(new Date(now).toISOString())+" · 私人分析 · 手动刷新</footer></main></body></html>";
}
