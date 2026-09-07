// Loopback-only synthetic preview; never part of the Worker bundle.
import { createServer } from "node:http";
import { parseFilter, readAnalytics } from "../src/analytics/queries";
import { renderPage } from "../src/analytics/page";
import { fixtureDb } from "./analytics-fixture";
const now=Date.parse("2026-09-07T10:00:00Z");
const fixture=fixtureDb();
for (let i=0;i<45;i++) {
  for (let d=1;d<=7;d++) {
    fixture.insert("r"+i+"-"+d,"synthetic-"+i,"2026-09-0"+d,i<30?91100:91000,
      d>=4 && i<12?"failed":"installed",{
        schema:2,
        module:i<35?15:14,sdk:i<30?33:36,framework:i<35?"lsposed":"vector",
        device:i<40?{manufacturer:i<25?"xiaomi":"oneplus",model:i<25?"Xiaomi 14":"PMA110",
          rom:i<25?"hyperos":"coloros"}:undefined,
        frameworkVersion:i<35?"2.1":"2.2",frameworkVersionCode:i<35?7000:3110,
        features: [
          {id:"comments_search_links_removed",state:d>=4&&i<12?"failed":"installed",reason_code:"REGISTRATION_FAILED",observed:2,applied:1},
          {id:"home_recommend_ads_removed",state:"installed"},
        ],
      });
  }
}
createServer(async(request,response)=>{
  try {
    const filter=parseFilter(new URL(request.url??"/","http://127.0.0.1:8789"),now);
    const data=await readAnalytics(fixture.db,filter);
    response.writeHead(200,{"content-type":"text/html; charset=utf-8","cache-control":"no-store"});
    response.end(renderPage(data,filter,now).replace("<h1>适配健康总览</h1>",
      "<h1>合成预览 · 非真实用户数据</h1>"));
  } catch {
    response.writeHead(400,{"content-type":"text/plain; charset=utf-8"});
    response.end("筛选无效：合成预览日期固定为 2026-09-07，范围限最近 30 天。");
  }
}).listen(8789,"127.0.0.1",()=>console.log("Synthetic preview: http://127.0.0.1:8789"));
