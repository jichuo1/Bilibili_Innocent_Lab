#!/usr/bin/env python3
"""Upload an already verified signed Canary APK to its Telegram channel."""
from __future__ import annotations
import argparse
import hashlib
import http.client
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from telegram_receipt import receipt_context, validate_receipt

MAX_FILE_BYTES = 50_000_000
MAX_RESPONSE_BYTES = 256 * 1024
DEFAULT_CHAT = "@Bilibili_Innocent_LabRelease"
REPOSITORY_PATH = "/jichuo1/Bilibili_Innocent_Lab/actions/runs/"

class DeliveryError(Exception):pass

def validate_identity(apk: Path, info: Path, tag: str, commit: str, source_url: str) -> str:
    if not apk.is_file() or not 0<apk.stat().st_size<=MAX_FILE_BYTES:
        raise DeliveryError("APK exceeds the Telegram upload budget")
    if not re.fullmatch(r"v\d+\.\d+\.\d+-canary\.\d+",tag) or not re.fullmatch(r"[0-9a-f]{40}",commit):
        raise DeliveryError("Invalid Canary identity")
    url=urlsplit(source_url)
    run=url.path.removeprefix(REPOSITORY_PATH)
    if (url.scheme!="https" or url.netloc!="github.com" or not url.path.startswith(REPOSITORY_PATH)
        or not run.isdigit() or int(run)<=0 or url.query or url.fragment):
        raise DeliveryError("Unexpected build source URL")
    values={}
    for line in info.read_text(encoding="utf-8").splitlines():
        key,sep,value=line.partition("=")
        if not sep or key in values:raise DeliveryError("Invalid build provenance")
        values[key]=value
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    required={"release_channel":"canary","release_tag":tag,"source_commit":commit,
              "apk_build_type":"release","apk_debuggable":"false","apk_filename":apk.name,"apk_sha256":digest,
              "apk_package_name":"com.Bilibili_Innocent_Lab.xposedmodule","apk_version_name":tag.removeprefix("v")}
    if any(values.get(k)!=v for k,v in required.items()):raise DeliveryError("APK does not match verified build provenance")
    if not re.fullmatch(r"[0-9a-fA-F]{64}",values.get("apk_signer_certificate_sha256","")):
        raise DeliveryError("Verified signing identity is missing")
    return digest

def caption(tag: str, commit: str, digest: str, source_url: str, notes: str) -> str:
    entries=[line[2:].strip() for line in notes.splitlines() if line.startswith("- ")]
    summary="\n".join("• "+entry for entry in entries)
    header=f"Bilibili Innocent Lab · Canary\n{tag}\n\n"
    footer=f"\n\n完整更新记录见后续消息。\n源码：{commit[:8]}\nSHA-256：{digest}\n构建：{source_url}"
    # Telegram 字符预算按 UTF-16 计数，截断点不能切开代理对。
    budget=1000-len((header+footer).encode("utf-16-le"))//2
    if budget < 0:raise DeliveryError("Canary caption identity exceeds its delivery budget")
    summary=split_text(summary,budget)[0] if summary and budget else ""
    return header+summary+footer


def split_text(text: str, budget: int = 3800) -> list[str]:
    parts=[];chars=[];units=0;line_end=0
    for char in text:
        size=2 if ord(char)>0xFFFF else 1
        if size>budget:raise DeliveryError("Telegram text budget cannot hold a Unicode character")
        while units+size>budget:
            end=line_end or len(chars)
            parts.append("".join(chars[:end]));chars=chars[end:]
            units=sum(2 if ord(value)>0xFFFF else 1 for value in chars);line_end=0
        chars.append(char);units+=size
        if char=="\n":line_end=len(chars)
    if chars:parts.append("".join(chars))
    return parts


def announcement_messages(notes: str, *, title: str="完整更新记录") -> list[str]:
    if len(notes.encode("utf-8"))>256*1024:raise DeliveryError("Complete announcement exceeds the delivery budget")
    parts=split_text(notes.rstrip())
    return [f"{title}（{index}/{len(parts)}）\n\n{part}" for index,part in enumerate(parts,1)]

def multipart(fields: dict[str,str], apk: Path) -> tuple[bytes,str]:
    boundary="CanaryUpload"+uuid.uuid4().hex
    parts=[]
    for key,value in fields.items():
        parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n").encode())
    if not re.fullmatch(r"[A-Za-z0-9_.-]+\.apk",apk.name):raise DeliveryError("Invalid APK upload filename")
    parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"document\"; filename=\"{apk.name}\"\r\n"
                  "Content-Type: application/vnd.android.package-archive\r\n\r\n").encode())
    parts.append(apk.read_bytes());parts.append(f"\r\n--{boundary}--\r\n".encode())
    return b"".join(parts),"multipart/form-data; boundary="+boundary

class Telegram:
    def __init__(self,token: str):
        if not re.fullmatch(r"\d+:[A-Za-z0-9_-]+",token):raise DeliveryError("Invalid Telegram secret format")
        self.token=token

    def call(self,method: str,fields: dict,apk: Path|None=None) -> dict:
        if method not in ["getMe","getChat","getChatMember","sendDocument","sendMessage"]:raise DeliveryError("Unsupported Telegram operation")
        if apk is None:
            body=json.dumps(fields).encode();content_type="application/json"
        else:body,content_type=multipart(fields,apk)
        waited=0
        try:
            for attempt in range(4):
                connection=http.client.HTTPSConnection("api.telegram.org",timeout=180)
                try:
                    if method in {"sendDocument","sendMessage"}:
                        print("TELEGRAM_POST_ATTEMPT: "+method,flush=True)
                    connection.request("POST",f"/bot{self.token}/{method}",body,{"Content-Type":content_type})
                    response=connection.getresponse();data=response.read(MAX_RESPONSE_BYTES+1)
                    if len(data)>MAX_RESPONSE_BYTES:raise DeliveryError("Telegram response exceeds budget")
                    value=json.loads(data)
                    if response.status==200 and value.get("ok") is True:return value["result"]
                    parameters=value.get("parameters")
                    retry_after=parameters.get("retry_after") if isinstance(parameters,dict) else None
                    # 只重试明确拒绝的 429；不能缩短服务端等待时间，也不能重发整个交付流程。
                    if not (response.status==429 and value.get("ok") is False and value.get("error_code")==429
                            and type(retry_after) is int and retry_after>0 and attempt<3 and waited+retry_after<=120):
                        detail=str(value.get("description","request rejected")).replace(self.token,"[redacted]")[:200]
                        raise DeliveryError(f"Telegram rejected {method}: {detail}")
                finally:connection.close()
                waited+=retry_after
                print(f"Telegram rate limited {method}; waiting {retry_after}s before retry {attempt+1}/3",flush=True)
                time.sleep(retry_after)
        except DeliveryError:raise
        except Exception:
            # 发送请求超时可能已经投递：不自动重发，避免频道出现重复 APK。异常不含带令牌 URL。
            raise DeliveryError("Telegram network/protocol failure; check delivery before retrying") from None

def message_time(value: int, label: str) -> datetime:
    if type(value) is not int or value<=0:
        raise DeliveryError(f"Telegram did not confirm the {label} message time")
    try:return datetime.fromtimestamp(value,timezone.utc)
    except (ValueError,OverflowError,OSError):
        raise DeliveryError(f"Telegram did not confirm the {label} message time") from None


def deliver(api: Telegram,chat: str,apk: Path,text: str,notes: str="", *, announcement_title: str="完整更新记录") -> dict:
    messages=announcement_messages(notes,title=announcement_title)
    if not re.fullmatch(r"@[A-Za-z][A-Za-z0-9_]{4,31}|-100\d+",chat):raise DeliveryError("Invalid Telegram channel ID")
    me=api.call("getMe",{});target=api.call("getChat",{"chat_id":chat})
    if target.get("type")!="channel":raise DeliveryError("Telegram destination must be a channel")
    if chat.startswith("@") and target.get("username","").lower()!=chat[1:].lower():raise DeliveryError("Telegram channel does not match configured destination")
    member=api.call("getChatMember",{"chat_id":target["id"],"user_id":me["id"]})
    if member.get("status")!="creator" and not (member.get("status")=="administrator" and member.get("can_post_messages") is True):
        raise DeliveryError("Bot needs channel administrator permission to post messages")
    result=api.call("sendDocument",{"chat_id":str(target["id"]),"caption":text},apk)
    if type(result.get("message_id")) is not int or result["message_id"]<=0:
        raise DeliveryError("Telegram did not confirm the APK message identity")
    completed=message_time(result.get("date"),"APK")
    message_ids=[]
    for message in messages:
        time.sleep(1)
        sent=api.call("sendMessage",{"chat_id":str(target["id"]),"text":message,
            "reply_parameters":{"message_id":result["message_id"]},
            "link_preview_options":{"is_disabled":True}})
        if type(sent.get("message_id")) is not int or sent["message_id"]<=0:
            raise DeliveryError("Telegram did not confirm the announcement message identity")
        sent_time=message_time(sent.get("date"),"announcement")
        if sent_time<completed:
            raise DeliveryError("Telegram did not confirm the announcement message time")
        completed=sent_time
        message_ids.append(sent["message_id"])
    return dict(result,chat_id=str(target["id"]),announcement_message_ids=message_ids,
                delivered_at=completed.isoformat().replace("+00:00","Z"))

def main() -> int:
    parser=argparse.ArgumentParser()
    for name in ["tag","commit","source-url"]:parser.add_argument("--"+name,required=True)
    for name in ["apk","build-info","notes"]:parser.add_argument("--"+name,required=True,type=Path)
    parser.add_argument("--summary",type=Path)
    parser.add_argument("--receipt",type=Path)
    parser.add_argument("--dry-run",action="store_true")
    args=parser.parse_args()
    print("TELEGRAM_DELIVERY_PROTOCOL: 3",flush=True)
    try:
        digest=validate_identity(args.apk,args.build_info,args.tag,args.commit,args.source_url)
        notes=args.notes.read_text(encoding="utf-8")
        text=caption(args.tag,args.commit,digest,args.source_url,notes)
        messages=announcement_messages(notes)
        token=os.environ.get("TELEGRAM_BOT_TOKEN","");chat=os.environ.get("TELEGRAM_CHAT_ID",DEFAULT_CHAT)
        if args.dry_run:status=f"Validated Telegram payload and {len(messages)} complete announcement message(s); dry-run only"
        elif not token:raise DeliveryError("Telegram delivery not configured: add TELEGRAM_BOT_TOKEN and grant channel posting permission")
        else:
            context={}
            if args.receipt:
                if not messages:
                    raise DeliveryError("Complete announcement is empty")
                context=receipt_context(
                    "https://github.com/"+os.environ.get("GITHUB_REPOSITORY","")+"/actions/runs/"+os.environ.get("GITHUB_RUN_ID",""),
                    int(os.environ.get("GITHUB_RUN_ATTEMPT","0")),os.environ.get("SOURCE_ARTIFACT_DIGEST",""))
            expected={"channel":chat,"source_commit":args.commit,"release_tag":args.tag,
                      "source_run_url":args.source_url,"apk_sha256":digest}
            if args.receipt and args.receipt.exists():
                stored=json.loads(args.receipt.read_text(encoding="utf-8"))
                if not isinstance(stored,dict):
                    raise DeliveryError("Invalid existing Telegram receipt")
                validate_receipt(stored,context["delivery_run_url"],context["delivery_run_attempt"],context["source_artifact_digest"])
                if any(stored.get(key)!=value for key,value in expected.items()):
                    raise DeliveryError("Existing Telegram receipt does not match the selected delivery")
                result={"message_id":stored["document_message_id"]}
            else:
                result=deliver(Telegram(token),chat,args.apk,text,notes)
            if args.receipt and not args.receipt.exists():
                receipt={"delivery_complete":True,"channel":chat,"source_commit":args.commit,
                    "release_tag":args.tag,"source_run_url":args.source_url,"chat_id":result["chat_id"],
                    "document_message_id":result["message_id"],"announcement_message_ids":result["announcement_message_ids"],
                    "apk_sha256":digest,"delivered_at":result["delivered_at"],**context}
                args.receipt.write_text(json.dumps(receipt,ensure_ascii=False),encoding="utf-8")
            status=f"Telegram delivered Canary APK, message ID {result['message_id']}"
        print(status)
        if args.summary:
            with args.summary.open("a",encoding="utf-8") as output:output.write("\n### Telegram delivery\n\n"+status+"\n")
        return 0
    except (DeliveryError,OSError,ValueError) as error:
        message=str(error).replace(os.environ.get("TELEGRAM_BOT_TOKEN") or "\0","[redacted]")
        print("Telegram delivery failed: "+message,file=sys.stderr)
        if args.summary:
            with args.summary.open("a",encoding="utf-8") as output:output.write("\n### Telegram delivery failed\n\n"+message+"\n")
        return 1

if __name__=="__main__":raise SystemExit(main())
