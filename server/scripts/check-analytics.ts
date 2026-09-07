// Read-only live query check. Prints counts/metadata only, never report contents.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { ANALYTICS_QUERIES, parseFilter } from "../src/analytics/queries";

const environment=process.argv[2];
assert.ok(environment==="production"||environment==="staging");
const database=environment==="production"?"innocent-lab-telemetry-prod":"innocent-lab-telemetry-staging";
const filter=parseFilter(new URL("https://stats.bilibili.date/?environment="+environment));
const params=[
  filter.startDay,filter.endDay,filter.host,filter.module,filter.sdk,filter.framework,
  filter.channel,filter.abi,filter.feature,filter.state,filter.splitDay,
  filter.manufacturer,filter.model,filter.rom,filter.framework_release,filter.schema,filter.parent,
];
const sql=Object.values(ANALYTICS_QUERIES).map(query=>{
  assert.doesNotMatch(query,/\b(INSERT|UPDATE|DELETE|DROP|ALTER|PRAGMA)\b/i);
  return query.replace(/\?(\d+)/g,(_,index:string)=>{
    const value=params[Number(index)-1];
    assert.notEqual(value,undefined);
    return typeof value==="number"?String(value):"'"+String(value).replaceAll("'","''")+"'";
  })+";";
}).join("\n");
const output=execFileSync(process.execPath,[
  fileURLToPath(new URL("../node_modules/wrangler/bin/wrangler.js",import.meta.url)),
  "d1","execute",database,"--remote","--env",environment,"--command",sql,"--json",
],{encoding:"utf8",timeout:90000,maxBuffer:4*1024*1024,windowsHide:true});
const results=JSON.parse(output) as {success:boolean;results:unknown[];meta?:{rows_read?:number;duration?:number}}[];
assert.equal(results.length,Object.keys(ANALYTICS_QUERIES).length);
results.forEach((result,index)=>{
  assert.equal(result.success,true);
  console.log(JSON.stringify({environment,query:Object.keys(ANALYTICS_QUERIES)[index],
    resultRows:result.results.length,rowsRead:result.meta?.rows_read,durationMs:result.meta?.duration}));
});
