import assert from "node:assert/strict";
import test from "node:test";
import { ANALYTICS_QUERIES, parseFilter as parseAnalyticsFilter, readAnalytics } from "../src/analytics/queries";
import { renderPage, installationRate } from "../src/analytics/page";
import { handleAnalytics } from "../src/analytics/index";
import type { AnalyticsEnv } from "../src/analytics/index";
import { sha256Hex } from "../src/crypto";
import { fixtureDb } from "./analytics-fixture";
import { analyze } from "../src/analytics/insights";

function legacyFilter(url: URL, now?: number) {
  if (!url.searchParams.has("schema")) url.searchParams.set("schema","1");
  return parseAnalyticsFilter(url,now);
}
const now = Date.parse("2026-09-07T10:00:00Z");

test("filters allow only known environments, bounded periods and numeric host versions", () => {
  assert.deepEqual(legacyFilter(new URL("https://stats.bilibili.date/"),now), {
    environment:"production",days:7,schema:1,parent:"",host:0,startDay:"2026-09-01",endDay:"2026-09-07",
    module:0,sdk:0,framework:"",channel:"",abi:"",feature:"",state:"",splitDay:"2026-09-04",custom:false,
    manufacturer:"",model:"",rom:"",framework_release:"",
  });
  for (const search of ["days=365","environment=unknown","host=1%20OR%201=1","host=2147483648"]) {
    assert.throws(()=>legacyFilter(new URL("https://stats.bilibili.date/?"+search),now));
  }
});

test("SQLite queries distinguish reports, distinct installations and eligible installation outcomes",async()=>{
  const {sqlite,db,insert}=fixtureDb();
  try {
    insert("r1","installation-a","2026-09-01",91100,"installed");
    insert("r2","installation-a","2026-09-02",91100,"installed");
    insert("r3","installation-b","2026-09-02",91100,"failed");
    insert("r4","installation-c","2026-09-02",91100,"not_applicable");
    insert("old","installation-d","2026-08-01",91100,"failed");
    const filter=legacyFilter(new URL("https://stats.bilibili.date/?host=91100"),now);
    const data=await readAnalytics(db,filter);
    assert.equal(data.overview[0]?.reports,4);
    assert.equal(data.overview[0]?.installations,3);
    assert.equal(data.features[0]?.eligible,3);
    assert.equal(data.features[0]?.failed,1);
    assert.equal(installationRate(data.features[0]!),"66.7%");
    const serialized=JSON.stringify(data);
    assert.equal(serialized.includes("installation-a"),false);
    assert.equal(serialized.includes("deletion-secret-hash"),false);
    assert.equal(serialized.includes("purge_token"),false);
    const html=renderPage(data,filter,now);
    assert.ok(html.includes("样本不足"));
    assert.ok(html.includes("66.7%"));
    assert.equal(sqlite.prepare("SELECT COUNT(*) AS c FROM reports").get()?.c,5);
  } finally {sqlite.close();}
});

test("rendering handles empty data and escapes database strings",async()=>{
  const {sqlite,db}=fixtureDb();
  try {
    const filter=legacyFilter(new URL("https://stats.bilibili.date/"),now);
    const data=await readAnalytics(db,filter);
    assert.ok(renderPage(data,filter,now).includes("当前范围还没有报告"));
    data.reasons=[{feature:"<script>alert(1)</script>",host:1,module:1,framework:"lsposed",reason:"<img onerror=alert(1)>",reports:1}];
    const html=renderPage(data,filter,now);
    assert.equal(html.includes("<script>"),false);
    assert.ok(html.includes("&lt;script&gt;"));
    assert.equal(installationRate({installed:0,eligible:0}),"—");
    for (const sql of Object.values(ANALYTICS_QUERIES)) {
      assert.doesNotMatch(sql,/\b(INSERT|UPDATE|DELETE|DROP|ALTER)\b/i);
    }
  } finally {sqlite.close();}
});

test("missing identity, forged headers or a different owner never reach D1",async()=>{
  let touched=false;
  const env={ADMIN_EMAIL_SHA256:await sha256Hex("owner@example.test"),
    get PROD_DB(){touched=true;throw new Error("must not access");},
    get STAGING_DB(){touched=true;throw new Error("must not access");},
  } as unknown as AnalyticsEnv;
  const request=new Request("https://stats.bilibili.date/",{headers:{"Cf-Access-Authenticated-User-Email":"owner@example.test"}});
  assert.equal((await handleAnalytics(request,env,{})).status,403);
  const other={access:{aud:"a",getIdentity:async()=>({email:"other@example.test"})}} as Pick<ExecutionContext,"access">;
  assert.equal((await handleAnalytics(request,env,other)).status,403);
  assert.equal(touched,false);
});

test("authorized owner gets private HTML and chooses only the requested database",async()=>{
  const {sqlite,db}=fixtureDb();
  try {
    const env={ADMIN_EMAIL_SHA256:await sha256Hex("owner@example.test"),STAGING_DB:db,
      get PROD_DB(){throw new Error("wrong database");},ANALYTICS_LIMITER:{limit:async()=>({success:true})},
    } as unknown as AnalyticsEnv;
    const ctx={access:{aud:"a",getIdentity:async()=>({email:"Owner@example.test"})}} as Pick<ExecutionContext,"access">;
    const response=await handleAnalytics(new Request("https://stats.bilibili.date/?environment=staging"),env,ctx);
    assert.equal(response.status,200);
    assert.equal(response.headers.get("cache-control"),"no-store");
    assert.ok((await response.text()).includes("测试环境"));
    assert.equal((await handleAnalytics(new Request("https://stats.bilibili.date/",{method:"POST"}),env,ctx)).status,405);
  } finally {sqlite.close();}
});

test("filter validation rejects injection, duplicate fields, invalid dates and unavailable history ranges",()=>{
  for (const search of [
    "module=-1","sdk=26","sdk=101","framework=evil","channel=unknown","abi=x86","feature=bad%27",
    "state=disabled","host=1&host=2","unexpected=x","start=2026-02-30&end=2026-09-07",
    "start=2026-09-01","start=2026-09-08&end=2026-09-08","start=2026-08-01&end=2026-09-07",
  ]) assert.throws(()=>legacyFilter(new URL("https://stats.bilibili.date/?"+search),now),search);
  const f=legacyFilter(new URL("https://stats.bilibili.date/?start=2026-08-20&end=2026-09-02"),now);
  assert.equal(f.custom,true); assert.equal(f.days,14); assert.equal(f.splitDay,"2026-08-27");
});

test("every distribution uses the latest matching snapshot, and other preserves the complete denominator",async()=>{
  // D1's compound-SELECT limit is lower than desktop SQLite's; expand dimensions without UNION.
  assert.doesNotMatch(ANALYTICS_QUERIES.distributions,/\bUNION\b/);
  const f=fixtureDb();
  try {
    f.insert("old","same","2026-09-01",90000,"installed",{module:14,sdk:33});
    f.insert("new","same","2026-09-07",91100,"installed",{module:15,sdk:36});
    for(let i=0;i<12;i++) f.insert("r"+i,"i"+i,"2026-09-07",92000+i,"installed");
    const data=await readAnalytics(f.db,legacyFilter(new URL("https://stats.bilibili.date/"),now));
    assert.equal(data.overview[0]?.reports,14);
    assert.equal(data.overview[0]?.installations,13);
    for(const dimension of ["host","module","sdk","framework","channel","abi"]) {
      const rows=data.distributions.filter(r=>r.dimension===dimension);
      assert.equal(rows.reduce((s,r)=>s+Number(r.installations),0),13,dimension);
    }
    assert.equal(data.distributions.some(r=>r.dimension==="host"&&r.value==="90000"),false);
    assert.equal(data.distributions.some(r=>r.dimension==="host"&&r.value==="__other__"),true);
    assert.equal(data.distributions.find(r=>r.dimension==="sdk"&&r.value==="36")?.installations,1);
  } finally {f.sqlite.close();}
});

test("all environment filters combine, and feature plus outcome must match the same feature",async()=>{
  const f=fixtureDb();
  try {
    f.insert("a","a","2026-09-07",91100,"failed",{module:16,sdk:36,framework:"vector",channel:"standard",abi:"arm64-v8a"});
    f.insert("b","b","2026-09-07",91100,"installed",{module:16,sdk:36,framework:"vector",features:[
      {id:"comment_filter",state:"installed"},{id:"free_copy",state:"failed"},
    ]});
    f.insert("c","c","2026-09-07",91000,"failed");
    const filter=legacyFilter(new URL("https://stats.bilibili.date/?host=91100&module=16&sdk=36&framework=vector&channel=standard&abi=arm64-v8a&feature=comment_filter&state=failed"),now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.overview[0]?.reports,1);
    assert.equal(data.daily[0]?.reports,1);
    assert.equal(data.features.length,1);
    assert.equal(data.reasons.length,1);
    assert.equal(data.distributions.find(r=>r.dimension==="sdk")?.installations,1);
    assert.deepEqual(analyze(data,filter),[]);
    assert.ok(renderPage(data,filter,now).includes("自动回归判断已暂停"));
  } finally {f.sqlite.close();}
});

test("empty feature arrays retain reports and render missing rate rather than zero percent",async()=>{
  const f=fixtureDb();
  try {
    f.insert("a","a","2026-09-07",91100,"installed",{features:[]});
    const filter=legacyFilter(new URL("https://stats.bilibili.date/"),now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.overview[0]?.reports,1);
    assert.equal(data.daily[0]?.reports,1);
    assert.equal(data.daily[0]?.eligible,0);
    assert.equal(installationRate(data.overview[0]!),"—");
    assert.deepEqual(analyze(data,filter),[]);
  } finally {f.sqlite.close();}
});

test("regression signals compare matched cohorts and deduplicate repeated daily samples",async()=>{
  const f=fixtureDb();
  try {
    for(let i=0;i<20;i++) {
      f.insert("b"+i,"id"+i,"2026-09-01",91100,"installed");
      f.insert("b2"+i,"id"+i,"2026-09-02",91100,"installed");
      f.insert("a"+i,"id"+i,"2026-09-07",91100,i<10?"failed":"installed");
    }
    const filter=legacyFilter(new URL("https://stats.bilibili.date/"),now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.features[0]?.before_n,20);
    assert.equal(data.features[0]?.after_n,20);
    const signals=analyze(data,filter);
    assert.equal(signals[0]?.kind,"regression");
    assert.ok(signals[0]?.evidence.includes("50.0 个百分点"));
    // Different SDK cannot serve as a baseline for this cohort.
    f.sqlite.prepare("UPDATE reports SET payload_json=json_set(payload_json,'$.runtime.android_sdk',36) WHERE report_day<'2026-09-04'").run();
    const separated=await readAnalytics(f.db,filter);
    assert.ok(analyze(separated,filter).every(i=>i.kind!=="regression"));
  } finally {f.sqlite.close();}
});

test("one repeatedly failing installation cannot satisfy sample thresholds",async()=>{
  const f=fixtureDb();
  try {
    for(let d=1;d<=7;d++) f.insert("r"+d,"same","2026-09-0"+d,91100,"failed");
    const filter=legacyFilter(new URL("https://stats.bilibili.date/"),now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.features[0]?.eligible_installations,1);
    assert.equal(analyze(data,filter)[0]?.kind,"observation");
    assert.equal(data.features[0]?.before_n,1);
    assert.equal(data.features[0]?.after_n,1);
  } finally {f.sqlite.close();}
});

test("unsupported long-term dimensions hide history instead of displaying an unfiltered total",async()=>{
  const f=fixtureDb();
  try {
    f.sqlite.exec("INSERT INTO feature_daily VALUES('2026-09-06',91100,15,'lsposed','comment_filter',10,9,1)");
    const base=legacyFilter(new URL("https://stats.bilibili.date/?module=15&feature=comment_filter"),now);
    assert.equal((await readAnalytics(f.db,base)).history.length,1);
    const filtered=legacyFilter(new URL("https://stats.bilibili.date/?sdk=33"),now);
    const data=await readAnalytics(f.db,filtered);
    assert.equal(data.history.length,0);
    assert.ok(renderPage(data,filtered,now).includes("当前筛选下隐藏"));
  } finally {f.sqlite.close();}
});

test("rendered chart drilldowns preserve filters and never include raw identity or executable code",async()=>{
  const f=fixtureDb();
  try {
    f.insert("a","private-identifier","2026-09-07",91100,"failed");
    const filter=legacyFilter(new URL("https://stats.bilibili.date/?environment=staging&days=14&framework=lsposed"),now);
    const data=await readAnalytics(f.db,filter);
    const html=renderPage(data,filter,now);
    assert.ok(html.includes("<svg"));
    assert.ok(html.includes("environment=staging&amp;schema=1&amp;days=14&amp;framework=lsposed&amp;host=91100"));
    assert.equal(html.includes("private-identifier"),false);
    assert.equal(html.includes("deletion-secret-hash"),false);
    assert.equal(html.includes("<script"),false);
    assert.equal(html.includes("<iframe"),false);
    assert.ok(html.includes("机型分布"));
    assert.ok(html.includes("ROM 分布"));
    assert.ok(html.includes("框架版本分布"));
    assert.ok(html.includes("未采集"));
  } finally {f.sqlite.close();}
});

test("device and framework release distributions distinguish legacy, unknown and real observations",async()=>{
  const f=fixtureDb();
  try {
    f.insert("old","old","2026-09-07",91100,"installed");
    f.insert("new","new","2026-09-07",91100,"failed",{
      device:{manufacturer:"xiaomi",model:"Xiaomi 14",rom:"hyperos"},frameworkVersion:"2.2",frameworkVersionCode:3110,
    });
    f.insert("unknown","unknown","2026-09-07",91100,"installed",{
      device:{manufacturer:"unknown",model:"unknown",rom:"unknown"},frameworkVersion:"unknown",frameworkVersionCode:0,
    });
    const base=legacyFilter(new URL("https://stats.bilibili.date/"),now);
    const all=await readAnalytics(f.db,base);
    for(const dim of ["manufacturer","model","rom","framework_release"]) {
      const rows=all.distributions.filter(r=>r.dimension===dim);
      assert.equal(rows.reduce((sum,r)=>sum+Number(r.installations),0),3);
      assert.equal(rows.some(r=>r.value==="not_collected"),true);
    }
    assert.ok(all.distributions.some(r=>r.dimension==="framework_release"&&r.value==="lsposed / 2.2 / 3110"));
    const url=new URL("https://stats.bilibili.date/?manufacturer=xiaomi&model=Xiaomi+14&rom=hyperos");
    url.searchParams.set("framework_release","lsposed / 2.2 / 3110");
    const filter=legacyFilter(url,now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.overview[0]?.reports,1);
    assert.equal(data.features[0]?.manufacturer,"xiaomi");
    assert.equal(data.features[0]?.framework_release,"lsposed / 2.2 / 3110");
    assert.equal(data.reasons[0]?.model,"Xiaomi 14");
    assert.equal(data.history.length,0);
    const html=renderPage(data,filter,now);
    assert.ok(html.includes("manufacturer=xiaomi"));
    assert.ok(html.includes("model=Xiaomi+14"));
  } finally {f.sqlite.close();}
});

test("new cohort dimensions prevent device or framework upgrades from being labeled same-cohort regressions",async()=>{
  for(const dimension of ["model","rom","frameworkVersion"] as const) {
    const f=fixtureDb();
    try {
      for(let i=0;i<12;i++) {
        f.insert("b"+i,"i"+i,"2026-09-01",91100,"installed",{
          device:{manufacturer:"xiaomi",model:"M1",rom:"miui"},frameworkVersion:"2.1",frameworkVersionCode:3000,
        });
        f.insert("a"+i,"i"+i,"2026-09-07",91100,"failed",{
          device:{manufacturer:"xiaomi",model:dimension==="model"?"M2":"M1",rom:dimension==="rom"?"hyperos":"miui"},
          frameworkVersion:dimension==="frameworkVersion"?"2.2":"2.1",frameworkVersionCode:3000,
        });
      }
      const filter=legacyFilter(new URL("https://stats.bilibili.date/"),now);
      assert.ok(analyze(await readAnalytics(f.db,filter),filter).every(i=>i.kind!=="regression"));
    } finally {f.sqlite.close();}
  }
});

test("capability schema is the default and is never mixed with legacy groups",async()=>{
  const f=fixtureDb();
  try {
    f.insert("old","old","2026-09-07",91100,"installed");
    f.insert("new","new","2026-09-07",91100,"partial",{schema:2,features:[
      {id:"comments_search_links_removed",state:"partial",reason_code:"PARTIAL_COVERAGE"},
      {id:"comments_vote_widgets_removed",state:"unknown",reason_code:"CAPABILITY_UNVERIFIED"},
    ]});
    const filter=parseAnalyticsFilter(new URL("https://stats.bilibili.date/"),now);
    assert.equal(filter.schema,2);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.overview[0]?.reports,1);
    assert.equal(data.overview[0]?.eligible,1);
    assert.equal(data.overview[0]?.failed,1);
    assert.equal(data.overview[0]?.unknown,1);
    assert.equal(data.features.length,2);
    assert.ok(renderPage(data,filter,now).includes("细分能力覆盖目录"));
    assert.equal((await readAnalytics(f.db,legacyFilter(new URL("https://stats.bilibili.date/"),now))).overview[0]?.reports,1);
  } finally {f.sqlite.close();}
});

test("incomplete snapshots and unknown evidence do not turn into a successful installation rate",async()=>{
  const f=fixtureDb();
  try {
    f.insert("incomplete","a","2026-09-07",91100,"installed",{schema:2,complete:false,
      features:[{id:"comments_search_links_removed",state:"installed"}]});
    const filter=parseAnalyticsFilter(new URL("https://stats.bilibili.date/"),now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.overview[0]?.incomplete_reports,1);
    assert.equal(data.overview[0]?.eligible,0);
    assert.equal(data.overview[0]?.installed,0);
    assert.equal(installationRate(data.overview[0]!),"—");
    assert.deepEqual(analyze(data,filter),[]);
  } finally {f.sqlite.close();}
});

test("oversized candidate ranges are rejected as a whole rather than silently sampled",async()=>{
  const f=fixtureDb();
  try {
    for(let i=0;i<2001;i++) f.insert("r"+i,"i"+i,"2026-09-07",91100,"installed",{schema:2,features:[]});
    const filter=parseAnalyticsFilter(new URL("https://stats.bilibili.date/"),now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.overview[0]?.query_blocked,1);
    assert.equal(data.overview[0]?.reports,0);
    assert.equal(data.features.length,0);
    const html=renderPage(data,filter,now);
    assert.ok(html.includes("未返回截断样本"));
    assert.ok(!html.includes("当前范围还没有报告"));
  } finally {f.sqlite.close();}
});

test("parent drilldown uses server-derived membership and does not include unrelated capabilities",async()=>{
  const f=fixtureDb();
  try {
    f.insert("r","i","2026-09-07",91100,"installed",{schema:2,features:[
      {id:"comments_search_links_removed",state:"installed"},
      {id:"home_recommend_ads_removed",state:"installed"},
    ]});
    const filter=parseAnalyticsFilter(new URL("https://stats.bilibili.date/?parent=comment_purify"),now);
    const data=await readAnalytics(f.db,filter);
    assert.equal(data.features.length,1);
    assert.equal(data.features[0]?.feature,"comments_search_links_removed");
    assert.ok(renderPage(data,filter,now).includes("parent=comment_purify"));
  } finally {f.sqlite.close();}
});
