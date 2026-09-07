// Local synthetic data only. Never imported by production Worker entrypoints.
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { validReport } from "./helpers";
import { CAPABILITY_BY_ID } from "../src/capabilities";

export function fixtureDb() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../migrations/0001_init.sql",import.meta.url),"utf8"));
  sqlite.exec(readFileSync(new URL("../migrations/0004_capability_daily.sql",import.meta.url),"utf8"));
  const db = {
    prepare(sql: string) { return { bind(...params: unknown[]) { return {sql,params}; } }; },
    async batch(statements: {sql:string;params:(string|number)[]}[]) {
      return statements.map(s=>({success:true,results:sqlite.prepare(s.sql).all(...s.params)}));
    },
  } as unknown as D1Database;
  const insert = (reportId:string,installKey:string,day:string,host:number,state:string,
    options: {schema?:1|2;complete?:boolean;module?:number;sdk?:number;framework?:string;channel?:string;abi?:string;features?:unknown[];
      device?:{manufacturer:string;model:string;rom:string};frameworkVersion?:string;frameworkVersionCode?:number} = {}) => {
    const payload = validReport({
      schema_version: options.schema??1,
      ...(options.schema===2?{feature_catalog_version:1,snapshot_complete:options.complete??true,
        snapshot_state:options.complete===false?"failed":"completed",evidence_scope:"current_host_process",feature_groups:[]}:{}),
      ...(options.device ? {device:options.device,disclosure_version:3} : {}),
      runtime:{android_sdk:options.sdk??33,abi:options.abi??"arm64-v8a",
        ...(options.frameworkVersion!==undefined?{framework_version:options.frameworkVersion,
          framework_version_code:options.frameworkVersionCode??0}:{})},
      features:options.features??[{id:"comment_filter",state,observed:1,applied:0,reason_code:"REGISTRATION_FAILED"}],
    });
    if (options.schema===2) payload.features=(payload.features as Record<string,unknown>[]).map(feature=>({
      ...feature,parent:CAPABILITY_BY_ID.get(String(feature.id))?.parent,
    }));
    sqlite.prepare("INSERT INTO reports VALUES(?,?,?,?,?,?,?,?,?,?)").run(
      reportId,options.schema??1,installKey,"deletion-secret-hash",day,options.module??15,host,
      options.framework??"lsposed",options.channel??"standard",JSON.stringify(payload),
    );
  };
  return {sqlite,db,insert};
}
