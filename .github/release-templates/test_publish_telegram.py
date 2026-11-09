from pathlib import Path
import tempfile
import hashlib
import json
import unittest
import io
from contextlib import redirect_stdout
from unittest.mock import patch,Mock,call
from publish_telegram import validate_identity,caption,multipart,deliver,split_text,announcement_messages,main,DeliveryError,MAX_FILE_BYTES,MAX_RESPONSE_BYTES,Telegram

class TelegramDeliveryTest(unittest.TestCase):
    def fixture(self):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        root=Path(directory.name);apk=root/"Bilibili_Innocent_Lab-v1.2.1-canary.3-aaaaaaaa.apk"
        apk.write_bytes(b"test APK data")
        digest=hashlib.sha256(apk.read_bytes()).hexdigest()
        info=root/"BUILD_INFO.txt"
        info.write_text("\n".join(["release_channel=canary","release_tag=v1.2.1-canary.3","source_commit="+"a"*40,
            "apk_build_type=release","apk_debuggable=false","apk_filename="+apk.name,"apk_sha256="+digest,
            "apk_package_name=com.Bilibili_Innocent_Lab.xposedmodule","apk_version_name=1.2.1-canary.3",
            "apk_signer_certificate_sha256="+"b"*64]),encoding="utf-8")
        return apk,info,digest

    def test_verified_identity(self):
        apk,info,digest=self.fixture()
        self.assertEqual(digest,validate_identity(apk,info,"v1.2.1-canary.3","a"*40,"https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123"))

    def test_payload_mismatch_and_debug_are_rejected(self):
        for mutation in ["debug","digest","url"]:
            apk,info,_=self.fixture()
            url="https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123"
            if mutation=="debug":info.write_text(info.read_text().replace("apk_debuggable=false","apk_debuggable=true"))
            if mutation=="digest":apk.write_bytes(b"changed")
            if mutation=="url":url="https://evil.invalid/actions/runs/123"
            with self.subTest(mutation=mutation),self.assertRaises(DeliveryError):validate_identity(apk,info,"v1.2.1-canary.3","a"*40,url)

    def test_caption_budget_retains_identity_and_url(self):
        url="https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123"
        text=caption("v1.2.1-canary.3","a"*40,"b"*64,url,"- "+"🙂"*2000)
        self.assertLessEqual(len(text.encode("utf-16-le"))//2,1000)
        self.assertIn(url,text);self.assertIn("b"*64,text)

    def test_multipart_uploads_apk_bytes_instead_of_remote_url(self):
        apk,_,_=self.fixture();body,kind=multipart({"chat_id":"-100123","caption":"test"},apk)
        self.assertIn(apk.read_bytes(),body);self.assertIn(b"name=\"document\"",body)
        self.assertTrue(kind.startswith("multipart/form-data; boundary="))

    def test_permission_failure_never_posts(self):
        apk,_,_=self.fixture()
        api=type("Fake",(),{"call":lambda self,method,fields,apk=None:{"getMe":{"id":1},"getChat":{"id":-100123,"type":"channel","username":"Bilibili_Innocent_LabRelease"},"getChatMember":{"status":"member"}}[method]})()
        with self.assertRaises(DeliveryError):deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption")

    def test_post_marker_is_emitted_only_at_a_message_request_boundary(self):
        for method in ["getMe","getChat","getChatMember","sendDocument","sendMessage"]:
            output=io.StringIO()
            with self.subTest(method=method),patch("publish_telegram.http.client.HTTPSConnection") as factory,redirect_stdout(output):
                factory.return_value.request.side_effect=OSError("fixture connection failure")
                with self.assertRaises(DeliveryError):Telegram("1:test").call(method,{"chat_id":"-100123"})
                factory.return_value.request.assert_called_once()
            self.assertEqual(method in {"sendDocument","sendMessage"},"TELEGRAM_POST_ATTEMPT:" in output.getvalue())
            self.assertNotIn("1:test",output.getvalue())
            self.assertNotIn("fixture connection failure",output.getvalue())

    def telegram_connection(self, status, value):
        connection=Mock();response=connection.getresponse.return_value
        response.status=status
        response.read.return_value=value if isinstance(value,bytes) else json.dumps(value).encode()
        return connection

    def rate_limit_response(self, retry_after=31):
        return {"ok":False,"error_code":429,"description":"Too Many Requests: retry after 31 /bot1:test/sendMessage",
                "parameters":{"retry_after":retry_after}}

    def test_rate_limit_retries_the_same_request_after_closing_the_connection(self):
        apk,_,_=self.fixture()
        for method in ["getMe","getChat","getChatMember","sendDocument","sendMessage"]:
            connections=[self.telegram_connection(429,self.rate_limit_response()),
                         self.telegram_connection(200,{"ok":True,"result":{"message_id":7}})]
            output=io.StringIO()
            with self.subTest(method=method),patch("publish_telegram.http.client.HTTPSConnection",side_effect=connections) as factory,\
                 patch("publish_telegram.time.sleep") as sleep,patch("publish_telegram.multipart",wraps=multipart) as prepare,redirect_stdout(output):
                def wait(seconds):
                    self.assertEqual(31,seconds);self.assertEqual(1,factory.call_count)
                    connections[0].close.assert_called_once();connections[1].request.assert_not_called()
                sleep.side_effect=wait
                result=Telegram("1:test").call(method,{"chat_id":"-100123"},apk if method=="sendDocument" else None)
                self.assertEqual({"message_id":7},result)
                self.assertEqual([call("api.telegram.org",timeout=180)]*2,factory.call_args_list)
                sleep.assert_called_once_with(31)
                self.assertEqual(1 if method=="sendDocument" else 0,prepare.call_count)
            for connection in connections:
                connection.request.assert_called_once();connection.close.assert_called_once()
                connection.getresponse.return_value.read.assert_called_once_with(MAX_RESPONSE_BYTES+1)
            self.assertEqual(connections[0].request.call_args,connections[1].request.call_args)
            self.assertIn("waiting 31s before retry 1/3",output.getvalue())
            self.assertEqual(2 if method in {"sendDocument","sendMessage"} else 0,output.getvalue().count("TELEGRAM_POST_ATTEMPT:"))
            self.assertNotIn("1:test",output.getvalue())

    def test_message_retry_does_not_repeat_the_apk_or_previous_messages(self):
        apk,_,_=self.fixture();notes="x"*8000;messages=announcement_messages(notes)
        results=[{"id":1},{"id":-100123,"type":"channel","username":"Bilibili_Innocent_LabRelease"},
                 {"status":"administrator","can_post_messages":True},{"message_id":7,"date":1700000000},
                 {"message_id":8,"date":1700000001},{"message_id":9,"date":1700000002},
                 {"message_id":10,"date":1700000003}]
        connections=[self.telegram_connection(200,{"ok":True,"result":result}) for result in results]
        connections.insert(5,self.telegram_connection(429,self.rate_limit_response()));output=io.StringIO()
        with patch("publish_telegram.http.client.HTTPSConnection",side_effect=connections) as factory,\
             patch("publish_telegram.time.sleep") as sleep,redirect_stdout(output):
            def wait(seconds):
                for connection in connections[:factory.call_count]:connection.close.assert_called_once()
            sleep.side_effect=wait
            result=deliver(Telegram("1:test"),"@Bilibili_Innocent_LabRelease",apk,"caption",notes)
            self.assertEqual(len(connections),factory.call_count)
            self.assertEqual([call(1),call(1),call(31),call(1)],sleep.call_args_list)
        requests=[connection.request.call_args.args for connection in connections]
        self.assertEqual(["getMe","getChat","getChatMember","sendDocument","sendMessage","sendMessage","sendMessage","sendMessage"],
                         [request[1].rsplit("/",1)[1] for request in requests])
        self.assertEqual([messages[0],messages[1],messages[1],messages[2]],[json.loads(request[2])["text"] for request in requests[4:]])
        self.assertEqual(requests[5],requests[6])
        self.assertEqual([8,9,10],result["announcement_message_ids"])
        self.assertEqual(1,output.getvalue().count("TELEGRAM_POST_ATTEMPT: sendDocument"))
        self.assertEqual(4,output.getvalue().count("TELEGRAM_POST_ATTEMPT: sendMessage"))
        self.assertNotIn("1:test",output.getvalue())
        for connection in connections:connection.request.assert_called_once();connection.close.assert_called_once()

    def test_rate_limit_allows_three_retries_and_the_exact_wait_budget(self):
        for waits in [[40,40,40],[120]]:
            connections=[self.telegram_connection(429,self.rate_limit_response(seconds)) for seconds in waits]
            connections.append(self.telegram_connection(200,{"ok":True,"result":{"message_id":7}}))
            with self.subTest(waits=waits),patch("publish_telegram.http.client.HTTPSConnection",side_effect=connections) as factory,\
                 patch("publish_telegram.time.sleep") as sleep,redirect_stdout(io.StringIO()):
                def wait(seconds):
                    self.assertEqual(sleep.call_count,factory.call_count)
                    for connection in connections[:factory.call_count]:connection.close.assert_called_once()
                sleep.side_effect=wait
                self.assertEqual({"message_id":7},Telegram("1:test").call("sendMessage",{}))
                self.assertEqual(len(waits)+1,factory.call_count)
                self.assertEqual([call(seconds) for seconds in waits],sleep.call_args_list)
            for connection in connections:connection.request.assert_called_once();connection.close.assert_called_once()

    def test_rate_limit_exhaustion_never_shortens_the_requested_wait(self):
        for waits,expected in [([1,1,1,1],[1,1,1]),([60,61],[60]),([120,1],[120]),([121],[]),([40,40,41],[40,40])]:
            connections=[self.telegram_connection(429,self.rate_limit_response(seconds)) for seconds in waits]
            output=io.StringIO()
            with self.subTest(waits=waits),patch("publish_telegram.http.client.HTTPSConnection",side_effect=connections) as factory,\
                 patch("publish_telegram.time.sleep") as sleep,redirect_stdout(output):
                with self.assertRaisesRegex(DeliveryError,"Telegram rejected sendMessage:") as error:Telegram("1:test").call("sendMessage",{})
                self.assertEqual(len(waits),factory.call_count)
                self.assertEqual([call(seconds) for seconds in expected],sleep.call_args_list)
            self.assertIn("[redacted]",str(error.exception))
            self.assertNotIn("1:test",str(error.exception)+output.getvalue())
            self.assertEqual(len(waits),output.getvalue().count("TELEGRAM_POST_ATTEMPT: sendMessage"))
            self.assertEqual(len(expected),output.getvalue().count("Telegram rate limited"))
            for connection in connections:connection.request.assert_called_once();connection.close.assert_called_once()

    def test_missing_or_invalid_retry_after_never_retries(self):
        parameters=[{},*[{"parameters":value} for value in [None,False,[],"31",31,{}]],
                    *[{"parameters":{"retry_after":value}} for value in [None,False,True,0,-1,"31",31.0,[],{},"1:test"]]]
        for fields in parameters:
            value=self.rate_limit_response();value.pop("parameters");value.update(fields)
            connection=self.telegram_connection(429,value);output=io.StringIO()
            with self.subTest(fields=fields),patch("publish_telegram.http.client.HTTPSConnection",return_value=connection) as factory,\
                 patch("publish_telegram.time.sleep") as sleep,redirect_stdout(output):
                with self.assertRaisesRegex(DeliveryError,"Telegram rejected sendMessage:") as error:Telegram("1:test").call("sendMessage",{})
                factory.assert_called_once();sleep.assert_not_called()
            connection.request.assert_called_once();connection.close.assert_called_once()
            self.assertIn("[redacted]",str(error.exception))
            self.assertNotIn("1:test",str(error.exception)+output.getvalue())
            self.assertNotIn("Telegram rate limited",output.getvalue())

    def test_retry_requires_both_http_429_and_an_explicit_json_429_rejection(self):
        cases=[(status,self.rate_limit_response()) for status in [200,400,401,403,500,502,503]]
        cases.extend((429,dict(self.rate_limit_response(),ok=value)) for value in [True,None,0,"false"])
        cases.extend((429,dict(self.rate_limit_response(),error_code=value)) for value in [None,True,400,500,"429"])
        for key in ["ok","error_code"]:
            value=self.rate_limit_response();value.pop(key);cases.append((429,value))
        for status,value in cases:
            connection=self.telegram_connection(status,value);output=io.StringIO()
            with self.subTest(status=status,value=value),patch("publish_telegram.http.client.HTTPSConnection",return_value=connection) as factory,\
                 patch("publish_telegram.time.sleep") as sleep,redirect_stdout(output):
                with self.assertRaisesRegex(DeliveryError,"Telegram rejected sendMessage:") as error:Telegram("1:test").call("sendMessage",{})
                factory.assert_called_once();sleep.assert_not_called()
            connection.request.assert_called_once();connection.close.assert_called_once()
            self.assertNotIn("1:test",str(error.exception)+output.getvalue())
            self.assertNotIn("Telegram rate limited",output.getvalue())

    def test_bad_json_and_oversized_responses_never_retry(self):
        for status in [200,429]:
            for data in [b"{1:test",b"null",b"[]",b"1",b"x"*(MAX_RESPONSE_BYTES+1)]:
                connection=self.telegram_connection(status,data);output=io.StringIO()
                with self.subTest(status=status,data=data[:20]),patch("publish_telegram.http.client.HTTPSConnection",return_value=connection) as factory,\
                     patch("publish_telegram.time.sleep") as sleep,redirect_stdout(output):
                    with self.assertRaises(DeliveryError) as error:Telegram("1:test").call("sendMessage",{})
                    factory.assert_called_once();sleep.assert_not_called()
                connection.request.assert_called_once();connection.close.assert_called_once()
                self.assertNotIn("1:test",str(error.exception)+output.getvalue())

    def test_network_failures_never_retry_even_after_a_rate_limit(self):
        for throttled in [False,True]:
            for stage in ["connect","request","getresponse","read","close"]:
                for exception in [TimeoutError,OSError]:
                    connection=self.telegram_connection(200,{"ok":True,"result":{"message_id":7}})
                    failure=exception("fixture failure at /bot1:test/sendMessage")
                    if stage=="read":connection.getresponse.return_value.read.side_effect=failure
                    elif stage!="connect":getattr(connection,stage).side_effect=failure
                    previous=[self.telegram_connection(429,self.rate_limit_response())] if throttled else []
                    output=io.StringIO()
                    with self.subTest(throttled=throttled,stage=stage,exception=exception),\
                         patch("publish_telegram.http.client.HTTPSConnection",side_effect=previous+[failure if stage=="connect" else connection]) as factory,\
                         patch("publish_telegram.time.sleep") as sleep,redirect_stdout(output):
                        with self.assertRaisesRegex(DeliveryError,"network/protocol failure") as error:Telegram("1:test").call("sendMessage",{})
                        self.assertEqual(len(previous)+1,factory.call_count)
                        self.assertEqual([call(31)] if throttled else [],sleep.call_args_list)
                    if stage=="connect":connection.request.assert_not_called();connection.close.assert_not_called()
                    else:connection.request.assert_called_once();connection.close.assert_called_once()
                    for item in previous:item.request.assert_called_once();item.close.assert_called_once()
                    self.assertNotIn("1:test",str(error.exception)+output.getvalue())
                    self.assertNotIn("fixture failure",str(error.exception)+output.getvalue())
                    self.assertTrue(error.exception.__suppress_context__)

    def test_multipart_or_json_preparation_failure_has_no_post_marker(self):
        for method,fields,apk,error in [("sendDocument",{},Path("invalid upload name.apk"),DeliveryError),
                                        ("sendMessage",{"unsupported":object()},None,TypeError)]:
            output=io.StringIO()
            with self.subTest(method=method),patch("publish_telegram.http.client.HTTPSConnection") as factory,redirect_stdout(output):
                with self.assertRaises(error):Telegram("1:test").call(method,fields,apk)
                factory.assert_not_called()
            self.assertNotIn("TELEGRAM_POST_ATTEMPT:",output.getvalue())

    def test_admin_posts_to_the_resolved_channel(self):
        apk,_,_=self.fixture();calls=[]
        def call(method,fields,file=None):
            calls.append((method,fields,file))
            return {"getMe":{"id":1},"getChat":{"id":-100123,"type":"channel","username":"Bilibili_Innocent_LabRelease"},
                "getChatMember":{"status":"administrator","can_post_messages":True},"sendDocument":{"message_id":7,"date":1700000000}}[method]
        api=type("Fake",(),{})();api.call=call
        self.assertEqual(7,deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption")["message_id"])
        self.assertEqual("-100123",calls[-1][1]["chat_id"])
        self.assertEqual(apk,calls[-1][2])

    def test_caption_is_no_longer_limited_to_four_entries(self):
        text=caption("v1.2.1-canary.3","a"*40,"b"*64,
            "https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123",
            "\n".join(f"- change {index}" for index in range(10)))
        self.assertIn("change 9",text)

    def test_complete_long_announcement_preserves_every_unicode_character(self):
        notes="\n".join(f"- 更新 {index}："+"🙂"*100 for index in range(60))
        messages=announcement_messages(notes)
        self.assertGreater(len(messages),1)
        self.assertEqual(notes,"".join(message.split("\n\n",1)[1] for message in messages))
        self.assertTrue(all(len(message.encode("utf-16-le"))//2<=4096 for message in messages))

    def test_normal_commit_entries_are_split_at_line_boundaries(self):
        entries=[f"- change {index}: "+"说明🙂"*40 for index in range(80)]
        messages=announcement_messages("\n".join(entries))
        bodies=[message.split("\n\n",1)[1] for message in messages]
        self.assertTrue(all(body.endswith("\n") for body in bodies[:-1]))
        self.assertTrue(all(sum(entry in body for body in bodies)==1 for entry in entries))

    def test_line_boundary_near_unicode_budget_still_obeys_the_limit(self):
        text="\n"+"🙂"*100
        parts=split_text(text,17)
        self.assertEqual(text,"".join(parts))
        self.assertTrue(all(len(part.encode("utf-16-le"))//2<=17 for part in parts))

    def announcement_api(self, fail=False, *, document_date=1700000000, announcement_dates=None, fail_at=1):
        calls=[];sent_count=0
        def call(method,fields,file=None):
            nonlocal sent_count
            calls.append((method,fields,file))
            if method=="sendMessage":
                sent_count+=1
                if fail and sent_count==fail_at:raise DeliveryError("announcement interrupted")
                date=announcement_dates[sent_count-1] if announcement_dates is not None else 1700000000+sent_count
                return {"message_id":len(calls)+7,"date":date}
            return {"getMe":{"id":1},"getChat":{"id":-100123,"type":"channel","username":"Bilibili_Innocent_LabRelease"},
                "getChatMember":{"status":"administrator","can_post_messages":True},"sendDocument":{"message_id":7,"date":document_date}}[method]
        api=type("Fake",(),{})();api.call=call
        return api,calls

    def test_all_announcement_parts_reply_to_the_uploaded_apk(self):
        apk,_,_=self.fixture();api,calls=self.announcement_api()
        with patch("publish_telegram.time.sleep"):
            result=deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","完整记录🙂"*3000)
        sent=[fields for method,fields,_ in calls if method=="sendMessage"]
        self.assertGreater(len(sent),1)
        self.assertEqual(len(sent),len(result["announcement_message_ids"]))
        self.assertTrue(all(fields["reply_parameters"]["message_id"]==7 for fields in sent))

    def test_completion_time_comes_from_the_last_confirmed_announcement(self):
        apk,_,_=self.fixture();api,calls=self.announcement_api(announcement_dates=[1700000001,1700000002,1700000010])
        with patch("publish_telegram.time.sleep"):
            result=deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","x"*8000)
        self.assertEqual(3,len(result["announcement_message_ids"]))
        self.assertEqual("2023-11-14T22:13:30Z",result["delivered_at"])
        self.assertEqual(3,sum(method=="sendMessage" for method,_,_ in calls))

    def test_invalid_apk_message_time_stops_before_sending_the_announcement(self):
        for date in [None,True,False,0,-1,"1700000000",1700000000.0]:
            apk,_,_=self.fixture();api,calls=self.announcement_api(document_date=date)
            with self.subTest(date=date),patch("publish_telegram.time.sleep"),self.assertRaisesRegex(DeliveryError,"APK message time"):
                deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","- change")
            self.assertEqual(1,sum(method=="sendDocument" for method,_,_ in calls))
            self.assertFalse(any(method=="sendMessage" for method,_,_ in calls))

    def test_invalid_or_reversed_announcement_time_never_confirms_delivery(self):
        for date in [None,True,False,0,-1,"1700000001",1700000001.0,1699999999]:
            apk,_,_=self.fixture();api,calls=self.announcement_api(announcement_dates=[date])
            with self.subTest(date=date),patch("publish_telegram.time.sleep"),self.assertRaisesRegex(DeliveryError,"announcement message time"):
                deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","x"*8000)
            self.assertEqual(1,sum(method=="sendMessage" for method,_,_ in calls))

    def test_equal_message_times_are_valid_with_second_resolution(self):
        apk,_,_=self.fixture();api,_=self.announcement_api(announcement_dates=[1700000000])
        with patch("publish_telegram.time.sleep"):
            result=deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","- change")
        self.assertEqual("2023-11-14T22:13:20Z",result["delivered_at"])

    def test_oversized_announcement_fails_before_posting_anything(self):
        apk,_,_=self.fixture();api,calls=self.announcement_api()
        with self.assertRaises(DeliveryError):
            deliver(api,"@Bilibili_Innocent_LabRelease",apk,"caption","x"*(256*1024+1))
        self.assertEqual([],calls)

    def invoke_main(self, apk,info,notes,receipt,*,dry_run=False,api=None,environment=None):
        arguments=["publish_telegram.py","--tag","v1.2.1-canary.3","--commit","a"*40,
            "--source-url","https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123",
            "--apk",str(apk),"--build-info",str(info),"--notes",str(notes),"--receipt",str(receipt)]
        if dry_run:arguments.append("--dry-run")
        context={"TELEGRAM_BOT_TOKEN":"1:test","GITHUB_REPOSITORY":"jichuo1/Bilibili_Innocent_Lab",
                 "GITHUB_RUN_ID":"9","GITHUB_RUN_ATTEMPT":"2","SOURCE_ARTIFACT_DIGEST":"sha256:"+"c"*64}
        context.update(environment or {})
        with patch("sys.argv",arguments),patch.dict("os.environ",context,clear=True),\
             patch("publish_telegram.Telegram",return_value=api),patch("publish_telegram.time.sleep"):
            return main()

    def test_success_writes_receipt_only_after_complete_notes(self):
        apk,info,digest=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
        notes.write_text("- change\n",encoding="utf-8");api,_=self.announcement_api()
        self.assertEqual(0,self.invoke_main(apk,info,notes,receipt,api=api))
        result=json.loads(receipt.read_text(encoding="utf-8"))
        self.assertTrue(result["delivery_complete"])
        self.assertEqual("a"*40,result["source_commit"])
        self.assertEqual(1,len(result["announcement_message_ids"]))
        self.assertEqual(2,result["receipt_schema"])
        self.assertEqual("https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/9",result["delivery_run_url"])
        self.assertEqual(2,result["delivery_run_attempt"])
        self.assertEqual("sha256:"+"c"*64,result["source_artifact_digest"])
        self.assertEqual(digest,result["apk_sha256"])
        self.assertEqual("2023-11-14T22:13:21Z",result["delivered_at"])
        self.assertEqual("https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123",result["source_run_url"])
        self.assertEqual("v1.2.1-canary.3",result["release_tag"])

    def complete_receipt(self, digest):
        return {"receipt_schema":2,"delivery_complete":True,"channel":"@Bilibili_Innocent_LabRelease",
                "source_commit":"a"*40,"release_tag":"v1.2.1-canary.3",
                "source_run_url":"https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/123",
                "delivery_run_url":"https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/9",
                "delivery_run_attempt":2,"source_artifact_digest":"sha256:"+"c"*64,"apk_sha256":digest,
                "chat_id":"-100123","document_message_id":7,"announcement_message_ids":[8],
                "delivered_at":"2023-11-14T22:13:21Z"}

    def test_repeating_a_complete_delivery_reuses_the_receipt_without_api_calls(self):
        apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
        notes.write_text("- change",encoding="utf-8");api,calls=self.announcement_api()
        self.assertEqual(0,self.invoke_main(apk,info,notes,receipt,api=api))
        self.assertEqual(1,sum(method=="sendDocument" for method,_,_ in calls))
        saved=receipt.read_bytes();retry_api,retry_calls=self.announcement_api()
        self.assertEqual(0,self.invoke_main(apk,info,notes,receipt,api=retry_api))
        self.assertEqual([],retry_calls)
        self.assertEqual(saved,receipt.read_bytes())

    def test_existing_receipt_rejects_another_valid_apk_before_any_api_call(self):
        apk,info,digest=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
        notes.write_text("- change",encoding="utf-8")
        receipt.write_text(json.dumps(self.complete_receipt(digest)),encoding="utf-8")
        saved=receipt.read_bytes();apk.write_bytes(b"another verified APK")
        replacement=hashlib.sha256(apk.read_bytes()).hexdigest()
        info.write_text(info.read_text(encoding="utf-8").replace(digest,replacement),encoding="utf-8")
        api,calls=self.announcement_api()
        self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api))
        self.assertEqual([],calls)
        self.assertEqual(saved,receipt.read_bytes())

    def test_existing_receipt_rejects_another_context_before_any_api_call(self):
        for environment in [{"GITHUB_RUN_ID":"10"},{"GITHUB_RUN_ATTEMPT":"3"},
                            {"SOURCE_ARTIFACT_DIGEST":"sha256:"+"d"*64},{"TELEGRAM_CHAT_ID":"@other_channel"}]:
            apk,info,digest=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
            notes.write_text("- change",encoding="utf-8")
            receipt.write_text(json.dumps(self.complete_receipt(digest)),encoding="utf-8")
            saved=receipt.read_bytes();api,calls=self.announcement_api()
            with self.subTest(environment=environment):
                self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api,environment=environment))
            self.assertEqual([],calls)
            self.assertEqual(saved,receipt.read_bytes())

    def test_existing_receipt_rejects_another_selected_source_before_any_api_call(self):
        for key,value in [("source_commit","b"*40),("release_tag","v1.2.1-canary.4"),
                          ("source_run_url","https://github.com/jichuo1/Bilibili_Innocent_Lab/actions/runs/124")]:
            apk,info,digest=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
            notes.write_text("- change",encoding="utf-8")
            stored=self.complete_receipt(digest);stored[key]=value
            receipt.write_text(json.dumps(stored),encoding="utf-8")
            saved=receipt.read_bytes();api,calls=self.announcement_api()
            with self.subTest(key=key):
                self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api))
            self.assertEqual([],calls)
            self.assertEqual(saved,receipt.read_bytes())

    def test_malformed_or_incomplete_existing_receipt_is_never_replaced_by_a_new_send(self):
        for mutation in ["malformed","list","null","incomplete","no_announcements","duplicate_announcements",
                         "apk_is_announcement","no_time","naive_time","old_schema","float_schema","boolean_attempt"]:
            apk,info,digest=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
            notes.write_text("- change",encoding="utf-8");stored=self.complete_receipt(digest)
            if mutation=="incomplete":stored["delivery_complete"]=False
            if mutation=="no_announcements":stored["announcement_message_ids"]=[]
            if mutation=="duplicate_announcements":stored["announcement_message_ids"]=[8,8]
            if mutation=="apk_is_announcement":stored["announcement_message_ids"]=[7]
            if mutation=="no_time":stored.pop("delivered_at")
            if mutation=="naive_time":stored["delivered_at"]="2023-11-14T22:13:21"
            if mutation=="old_schema":stored.pop("receipt_schema")
            if mutation=="float_schema":stored["receipt_schema"]=2.0
            if mutation=="boolean_attempt":stored["delivery_run_attempt"]=True
            content={"malformed":"{","list":"[]","null":"null"}.get(mutation,json.dumps(stored))
            receipt.write_text(content,encoding="utf-8");api,calls=self.announcement_api()
            with self.subTest(mutation=mutation):
                self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api))
            self.assertEqual([],calls)
            self.assertEqual(content,receipt.read_text(encoding="utf-8"))

    def test_empty_announcement_is_rejected_before_any_api_call(self):
        for text in [""," \n\t"]:
            apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
            notes.write_text(text,encoding="utf-8");api,calls=self.announcement_api()
            with self.subTest(text=text):
                self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api))
            self.assertEqual([],calls)
            self.assertFalse(receipt.exists())

    def test_dry_run_and_partial_announcement_failure_do_not_write_receipts(self):
        for dry_run in [True,False]:
            apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
            notes.write_text("- change\n",encoding="utf-8");api,calls=self.announcement_api(fail=True)
            self.assertEqual(0 if dry_run else 1,self.invoke_main(apk,info,notes,receipt,dry_run=dry_run,api=api))
            self.assertFalse(receipt.exists())
            if dry_run:self.assertEqual([],calls)

    def test_partial_multi_part_announcement_does_not_write_a_complete_receipt(self):
        apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
        notes.write_text("x"*8000,encoding="utf-8");api,calls=self.announcement_api(fail=True,fail_at=2)
        self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api))
        self.assertFalse(receipt.exists())
        self.assertEqual(1,sum(method=="sendDocument" for method,_,_ in calls))
        self.assertEqual(2,sum(method=="sendMessage" for method,_,_ in calls))

    def test_bad_receipt_context_fails_before_any_telegram_api_call(self):
        for key,value in [("GITHUB_REPOSITORY","other/repository"),("GITHUB_RUN_ID",""),("GITHUB_RUN_ID","9/10"),
                          ("GITHUB_RUN_ATTEMPT","0"),("GITHUB_RUN_ATTEMPT","101"),("GITHUB_RUN_ATTEMPT","invalid"),
                          ("SOURCE_ARTIFACT_DIGEST",""),("SOURCE_ARTIFACT_DIGEST","sha256:"+"x"*64)]:
            apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
            notes.write_text("- change",encoding="utf-8");api,calls=self.announcement_api()
            with self.subTest(key=key,value=value):
                self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api,environment={key:value}))
            self.assertEqual([],calls)
            self.assertFalse(receipt.exists())

    def test_dry_run_needs_no_delivery_context_or_token_and_never_calls_telegram(self):
        apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
        notes.write_text("- change",encoding="utf-8");api,calls=self.announcement_api()
        environment={key:"" for key in ["TELEGRAM_BOT_TOKEN","GITHUB_REPOSITORY","GITHUB_RUN_ID",
                                        "GITHUB_RUN_ATTEMPT","SOURCE_ARTIFACT_DIGEST"]}
        self.assertEqual(0,self.invoke_main(apk,info,notes,receipt,dry_run=True,api=api,environment=environment))
        self.assertEqual([],calls)
        self.assertFalse(receipt.exists())

    def test_unconfirmed_announcement_time_does_not_write_a_receipt(self):
        apk,info,_=self.fixture();notes=apk.parent/"notes.txt";receipt=apk.parent/"receipt.json"
        notes.write_text("- change",encoding="utf-8");api,calls=self.announcement_api(announcement_dates=[None])
        self.assertEqual(1,self.invoke_main(apk,info,notes,receipt,api=api))
        self.assertFalse(receipt.exists())
        self.assertEqual(1,sum(method=="sendMessage" for method,_,_ in calls))
