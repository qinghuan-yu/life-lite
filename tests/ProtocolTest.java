package cn.qingyu.lifelite;

import org.json.*;
import java.util.*;
import static cn.qingyu.lifelite.Protocol.*;

/** 离线协议和订单恢复测试，不接触真实账户或设备。 */
public final class ProtocolTest {
    static int checks=0;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    interface Task {void run() throws Exception;}
    static void rejects(Task t,String name) throws Exception {boolean rejected=false;try{t.run();}catch(Exception e){rejected=true;}check(rejected,name);}
    static class Memory implements Api.Store {
        JSONObject data;
        Memory(JSONObject d){data=d;}
        public JSONObject read() throws Exception{return new JSONObject(data.toString());}
        public void write(JSONObject d) throws Exception{data=new JSONObject(d.toString());}
    }
    static class Net implements Api.Transport {
        int starts=0,stops=0,expectedType=21;boolean idle=true,failStart=false,failStop=false;Memory store;
        JSONArray payments=new JSONArray().put(objUnchecked(21)).put(objUnchecked(91));
        static JSONObject objUnchecked(int type){try{return obj("type",type);}catch(Exception e){throw new RuntimeException(e);}}
        public byte[] call(String m,String url,JSONObject headers,JSONObject body) throws Exception {
            JSONObject response;
            if(url.contains("/dev/start?")) {
                check(store.data.has("active_water"),"必须先持久化待确认记录再发开始请求");
                check(url.contains("ptype="+expectedType),"只使用用户确认的支付渠道");
                starts++;idle=false;if(failStart)throw new java.io.IOException("网络超时");response=obj("code",0);
            }else if(url.contains("/dev/end?")){stops++;if(failStop)throw new java.io.IOException("网络超时");idle=true;response=obj("code",0);}
            else if(url.contains("/ui/app/dev/status?")||url.contains("/ui/app/dev/home/1?"))response=obj("code",0,"data",obj("device",obj("id","test01","status",1,"gene",obj("status",idle?99:1),"name","测试饮水机"),"payItems",payments));
            else throw new Exception("未预期的请求 "+url.split("\\?")[0]);
            return response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static class ZhuliNet implements Api.Transport {
        Memory store;int starts=0,ends=0;boolean present=false,completed=false,malformed=false,tamper=false,failStart=false;
        JSONObject params(String url) throws Exception {
            JSONObject p=new JSONObject();for(String part:new java.net.URI(url).getRawQuery().split("&")){String[] kv=part.split("=",2);p.put(java.net.URLDecoder.decode(kv[0],"UTF-8"),java.net.URLDecoder.decode(kv[1],"UTF-8"));}return p;
        }
        public byte[] call(String method,String url,JSONObject headers,JSONObject body) throws Exception {
            JSONObject q=params(url);
            check(q.getString("identitycode").equals("fixture-identity"),"洗浴使用本人会话身份");
            check(q.getString("pid").equals("school-fixture"),"洗浴使用登录返回的项目");
            check(q.getString("sign").equals(signZhuli(q,"fixture-secret")),"洗浴请求签名正确");
            Object data;
            if(url.contains("get_staff_consumeing?")) {
                data=malformed?new JSONObject():present&&!completed?new JSONArray().put(obj("consume_type","bath","order_id","order-fixture","device_id","12345")):new JSONArray();
            } else if(url.contains("device/get_by_id?"))data=obj("device_id",12345,"device_type",0,"net_type",1,"device_name","测试浴位");
            else if(url.contains("collect_gprs_device_status?"))data=new JSONObject();
            else if(url.contains("consume/create_order?")) {
                check(store.data.has("active_bath"),"洗浴先保存待确认记录");
                check(q.getString("staff_id").equals("17")&&q.getString("send_to_device").equals("1")&&q.getString("act").equals("consume"),"创建正常洗浴订单");
                check(!q.has("money")&&!q.has("consume_value"),"按量洗浴不猜测消费金额");
                starts++;present=true;if(failStart)throw new java.io.IOException("超时");data=obj("order_id","order-fixture","status","waiting");
            } else if(url.contains("get_bill_detail_by_id?"))data=obj("order_id","order-fixture","status",completed?"finished":"consuming","consume_money",0.25);
            else if(url.contains("remote_end_order?")){ends++;data=obj("order_id","order-fixture");}
            else throw new Exception("Unexpected mock endpoint");
            JSONObject r=obj("result",true,"timestamp","1800000000","data",Base64.getUrlEncoder().encodeToString(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            r.put("sign",tamper?"INVALID":signZhuli(r,"fixture-secret"));
            return r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static class PaymentNet implements Api.Transport {
        Memory store;int prepays=0,status=1;boolean fail=false,amountChanged=false;String signed="app_id=fixture&sign=a+b/c=";
        public byte[] call(String method,String url,JSONObject headers,JSONObject body) throws Exception {
            JSONObject response;
            if(url.contains("/bill/view-full?"))response=obj("code",0,"data",obj("bill",obj("id","bill-fixture","owner",obj("id","user-fixture"),"status",status,"payment",amountChanged?0.5:0.25)));
            else if(url.contains("/bill/pay/channels?"))response=obj("code",0,"data",obj("channels",new JSONArray().put(obj("type",21))));
            else if(url.contains("/trans/prepay/21?")){
                check(store.data.has("payment_water"),"支付请求前先持久化待确认状态");prepays++;if(fail)throw new java.io.IOException("超时");response=obj("code",0,"data",JSONObject.NULL);
            }else if(url.contains("/trans/confirm?"))response=obj("code",0);
            else if(url.contains("/bill/lst-owner?"))response=obj("code",0,"data",new JSONArray().put(obj("id","bill-fixture","status",status,"payment",0.25)));
            else if(url.contains("/ui/app/sign-pga?")){check(url.contains("type=20"),"免密协议类型为20，不能误用支付方式21");response=obj("code",0,"data",obj("sign",signed));}
            else throw new Exception("Unexpected payment endpoint");
            return response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static class AlipayLoginNet implements Api.Transport {
        int logins=0,responseCode=0;boolean emptyAccount=false;String uid="ali-fixture";
        public byte[] call(String method,String url,JSONObject headers,JSONObject body) throws Exception {
            JSONObject r;
            if(url.contains("/acc/login?")){
                logins++;check("POST".equals(method),"支付宝授权码通过POST交换");
                check(body.length()==1&&body.getString("openCode").equals("sdk-code-fixture"),"仅传SDK授权码，不伪造手机号或短信码");
                check(url.endsWith("sign="+sign798(body)),"支付宝登录按参数生成请求签名");
                r=obj("code",responseCode,"data",obj("al",obj("token","candidate-token-fixture","uid",uid)));
            }else if(url.endsWith("/ui/app/master")){
                check(headers.getString("Authorization").equals("candidate-token-fixture"),"用候选会话验证账户");
                r=obj("code",0,"data",emptyAccount?new JSONObject():obj("account",obj("id",uid,"pn","13800000000"),"favos",new JSONArray()));
            }else throw new Exception("Unexpected login endpoint");
            return r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static class ZhuliAlipayNet extends ZhuliNet {
        int logins=0;boolean missingProject=false,mismatch=false,rejectCandidate=false,badSignature=false,timeout=false;
        String uid="17",project="school-fixture",before;
        public byte[] call(String method,String url,JSONObject headers,JSONObject body) throws Exception {
            JSONObject q=params(url);Object data;String key="6d5dbb85b949447a95ff8fda9a9b759b";
            if(url.contains("/webapi/oauth/")) {
                check(!headers.has("Authorization"),"授权与换取新会话不携带旧令牌");
                check(signZhuli(q,key).equals(q.getString("sign")),"住理OAuth请求按平台规则签名");
                if(url.contains("/alipay/auth_info?"))data="app_id=fixture&sign=a+b/c=";
                else if(url.contains("/oauth/login?")) {
                    logins++;if(timeout)throw new java.io.IOException("请求超时");
                    JSONObject auth=objectData(q.getString("base64_authInfo")),system=objectData(q.getString("base64_systemInfo"));
                    check(auth.getString("auth_code").equals("zhuli-sdk-fixture")&&auth.getString("oauth_type").equals("alipay_app")&&auth.getString("sign_type").equals("RSA"),"住理使用本人SDK授权码及官方OAuth类型");
                    check(system.getString("deviceToken").isEmpty()&&q.getString("app_version").equals("3.12.11"),"住理登录不伪造手机标识");
                    data=obj("platform_token","zhuli-candidate-fixture","user_info",obj("id",uid,"phone","13800000000","identity_code","candidate-identity","is_pwd",1));
                    if(!missingProject)((JSONObject)data).put("server_info",obj("server_addr","https://fixture.whxinna.com","server_id","server-fixture","server_appid",project,"session_secret","candidate-secret","projectname","测试学校"));
                }else throw new Exception("Unexpected OAuth endpoint");
            }else if(url.contains("staff/get_user_detail_by_id?")) {
                key="candidate-secret";
                check(before.equals(store.data.toString()),"候选会话校验前不覆盖旧存储");
                check(q.getString("identitycode").equals("candidate-identity")&&q.getString("pid").equals(project)&&q.getString("sign").equals(signZhuli(q,key)),"用候选身份及项目读取账户");
                if(rejectCandidate)return obj("result",false,"err_msg","login_timeout").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                data=obj("id",mismatch?"different":uid,"total_balance",8.25);
            }else throw new Exception("登录流程不应调用消费接口");
            JSONObject result=obj("result",true,"data",Base64.getUrlEncoder().encodeToString((data instanceof String?(String)data:data.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            result.put("sign",badSignature?"INVALID":signZhuli(result,key));
            return result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    static void zhuliAlipayChecks() throws Exception {
        Memory store=new Memory(obj("water",obj("token","water-session-fixture")));
        ZhuliAlipayNet net=new ZhuliAlipayNet();net.store=store;net.before=store.data.toString();Api api=new Api(store,net);
        check(new JSONObject(api.zhuliAlipayAuthInfo()).getString("data").equals("app_id=fixture&sign=a+b/c="),"住理授权响应封装保持原始加号");
        check(net.logins==0&&net.before.equals(store.data.toString()),"获取授权参数不登录或覆盖账户");
        JSONObject view=api.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model");
        check(view.getJSONObject("bath").getBoolean("loggedIn")&&view.getJSONObject("bath").getDouble("balance")==8.25,"住理支付宝验证账户后登录成功");
        check(!view.toString().contains("candidate-")&&!store.data.toString().contains("zhuli-sdk-fixture"),"住理凭据不进页面且授权码不落盘");
        check(store.data.getJSONObject("water").getString("token").equals("water-session-fixture"),"住理登录不改变慧生活会话");
        net.before=store.data.toString();net.mismatch=true;
        rejects(()->api.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model"),"拒绝住理账户校验身份不一致");net.mismatch=false;
        net.missingProject=true;rejects(()->api.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model"),"未绑定学校不保存会话");net.missingProject=false;
        net.rejectCandidate=true;rejects(()->api.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model"),"无效候选会话不能令旧会话过期");net.rejectCandidate=false;
        net.badSignature=true;rejects(()->api.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model"),"拒绝候选账户响应签名错误");net.badSignature=false;
        check(net.before.equals(store.data.toString()),"失败后完整保留原账户");
        store.data.put("active_bath",obj("device","12345","phase","unknown"));store.data.put("devices_bath",new JSONArray().put(obj("id","12345","name","测试浴位")));
        final Api pendingApi=new Api(store,net);net.before=store.data.toString();
        pendingApi.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model");
        check(store.data.has("active_bath")&&store.data.getJSONArray("devices_bath").length()==1,"同账户同项目重登保留未确认订单和收藏");
        net.before=store.data.toString();net.uid="18";rejects(()->pendingApi.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model"),"有订单时拒绝更换住理用户");net.uid="17";
        net.project="different-project";rejects(()->pendingApi.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model"),"有订单时拒绝切换学校");net.project="school-fixture";
        check(net.before.equals(store.data.toString()),"跨账户或项目登录不丢失原订单");
        int attempts=net.logins;net.timeout=true;rejects(()->pendingApi.loginZhuliAlipay("zhuli-sdk-fixture","15","test-model"),"授权码交换超时不自动重试");check(net.logins==attempts+1,"一次授权只交换一次");
        rejects(()->pendingApi.loginZhuliAlipay("","15","test-model"),"空授权码不发送登录");
    }
    public static void main(String[] args) throws Exception {
        zhuliAlipayChecks();
        check(sign798(obj("un","13800000000","authCode","123456")).equals(md5("authCode=123456&un=13800000000aB3dF6hJ9kL2nP5rT8wY1eG4iM7oQ0v")),"798 登录签名排序");
        check(signZhuli(obj("z",0,"a"," 中 文 ","empty","","nil",JSONObject.NULL,"sign","ignored"),"key").equals(md5("a=中文&z=0&key=key").toUpperCase(Locale.ROOT)),"住理生活签名空值和空格规则");
        check(query(obj("x","a+b &中文")).equals("x=a%2Bb+%26%E4%B8%AD%E6%96%87"),"查询参数编码");
        check(objectData("eyJpZCI6MX0=").getInt("id")==1,"Base64 响应解码");
        check(deviceId("https://i.ilife798.com/qr/abc123",false).equals("abc123"),"798 二维码解析");
        check(deviceId("https://zhuli.whxinna.com/?device_id=123456",true).equals("123456"),"住理二维码解析");
        rejects(()->deviceId("https://evil.example/?device_id=123",true),"拒绝跨平台二维码");
        rejects(()->deviceId("https://whxinna.com.evil.example/?device_id=123",true),"拒绝伪装域名");
        rejects(()->bathNetwork(obj("device_type",0,"net_type",4)),"蓝牙设备不能发送联网开始");
        rejects(()->bathNetwork(obj("device_type",3,"net_type",1)),"洗衣设备不能当洗澡设备");
        check(bathNetwork(obj("device_type",0,"net_type",1))==1,"联网洗浴设备");
        check(!available798(new JSONObject()),"缺失设备状态不视为空闲");
        check(!available798(obj("device",obj("status",0,"gene",obj("status",99)))),"离线设备不视为空闲");
        Memory memory=new Memory(obj("water",obj("token","test-only","phone","13800000000")));
        Net net=new Net();net.store=memory;Api api=new Api(memory,net);
        JSONObject prepared=api.dispatch("prepare",obj("device","test01"));
        String firstTicket=prepared.getString("ticket");
        api.dispatch("start",obj("ticket",prepared.getString("ticket"),"paymentType",21));
        rejects(()->api.dispatch("start",obj("ticket",firstTicket,"paymentType",21)),"确认票据不能重复使用");
        check(net.starts==1,"连续点击只启动一次");
        net.idle=true;api.dispatch("sync",new JSONObject());check(memory.data.has("active_water"),"启动回执后暂未出水不能误判结束");
        net.idle=false;api.dispatch("sync",new JSONObject());check(memory.data.getJSONObject("active_water").getString("phase").equals("running"),"实时状态确认启动");
        rejects(()->api.dispatch("logout",new JSONObject()),"运行期间不能退出账户");
        net.failStop=true;rejects(()->api.dispatch("stop",new JSONObject()),"结束超时应报告失败");
        check(memory.data.getJSONObject("active_water").getString("phase").equals("stop_unknown"),"结束超时保留订单");
        Api restarted=new Api(memory,net);restarted.dispatch("sync",new JSONObject());
        check(restarted.snapshot().getJSONObject("water").has("active"),"重启后恢复待处理订单");
        net.failStop=false;restarted.dispatch("stop",new JSONObject());
        check(!memory.data.has("active_water"),"只有设备确认空闲才能结束本地记录");
        prepared=restarted.dispatch("prepare",obj("device","test01"));String ticket=prepared.getString("ticket");net.failStart=true;
        rejects(()->restarted.dispatch("start",obj("ticket",ticket,"paymentType",21)),"开始超时不自动重试");
        check(memory.data.getJSONObject("active_water").getString("phase").equals("unknown"),"开始超时保存未知状态");
        net.idle=true;restarted.dispatch("sync",new JSONObject());
        check(memory.data.has("active_water"),"开始超时即使空闲仍需用户确认");
        rejects(()->restarted.dispatch("prepare",obj("device","test02")),"未知订单阻止再次开水");
        check(net.starts==2,"无隐式支付重试");
        restarted.dispatch("ackIdle",new JSONObject());check(!memory.data.has("active_water"),"用户核实空闲后才清理未知记录");
        check(!restarted.snapshot().toString().contains("test-only"),"界面快照不泄露令牌");
        net.failStart=false;
        String unsupported=restarted.dispatch("prepare",obj("device","test01")).getString("ticket");
        rejects(()->restarted.dispatch("start",obj("ticket",unsupported,"paymentType",11)),"拒绝未经展示的支付渠道");
        check(net.starts==2,"无效支付方式不会下发设备命令");
        String changed=restarted.dispatch("prepare",obj("device","test01")).getString("ticket");
        net.payments=new JSONArray().put(obj("type",91));
        rejects(()->restarted.dispatch("start",obj("ticket",changed,"paymentType",21)),"确认后渠道变化不自动改用钱包");
        check(net.starts==2,"没有隐式支付回退");
        net.expectedType=91;
        String wallet=restarted.dispatch("prepare",obj("device","test01")).getString("ticket");
        restarted.dispatch("start",obj("ticket",wallet,"paymentType",91));restarted.dispatch("stop",new JSONObject());
        net.payments=new JSONArray();rejects(()->restarted.dispatch("prepare",obj("device","test01")),"缺失支付渠道不能猜测钱包可用");
        check(deviceId("https://i.hnkzy.com/q/1/abc123",false).equals("abc123"),"官方 hnkzy 二维码");
        rejects(()->deviceId("https://i.hnkzy.com.evil.example/q/1/abc123",false),"拒绝伪装 hnkzy 域名");
        check(finished(obj("status","finished"))&&!finished(obj("status","consuming")),"结算终态判断");
        Memory zstore=new Memory(obj("bath",obj("phone","13800000000","user_info",obj("id",17,"identity_code","fixture-identity"),"server_info",obj("server_addr","https://fixture.whxinna.com","server_id","server-fixture","server_appid","school-fixture","session_secret","fixture-secret"))));
        ZhuliNet znet=new ZhuliNet();znet.store=zstore;Api zapi=new Api(zstore,znet);
        JSONObject zp=zapi.dispatch("prepare",obj("provider","bath","device","12345"));
        zapi.dispatch("start",obj("provider","bath","ticket",zp.getString("ticket")));
        zapi.dispatch("sync",obj("provider","bath"));
        check(zstore.data.getJSONObject("active_bath").getString("phase").equals("running"),"洗浴恢复进行中状态");
        zapi.dispatch("stop",obj("provider","bath"));
        check(zstore.data.has("active_bath"),"结束回执不等于结算终态");
        znet.completed=true;zapi.dispatch("sync",obj("provider","bath"));
        check(!zstore.data.has("active_bath")&&zstore.data.getJSONObject("last_bath").getDouble("amount")==0.25,"服务器结算终态及金额");
        znet.tamper=true;rejects(()->zapi.dispatch("sync",obj("provider","bath")),"拒绝响应签名不匹配");znet.tamper=false;
        znet.malformed=true;rejects(()->zapi.dispatch("prepare",obj("provider","bath","device","12345")),"异常订单列表不能当成无订单");znet.malformed=false;
        znet.present=false;znet.completed=false;znet.failStart=true;
        String zticket=zapi.dispatch("prepare",obj("provider","bath","device","12345")).getString("ticket");
        rejects(()->zapi.dispatch("start",obj("provider","bath","ticket",zticket)),"洗浴开始超时保存待确认");
        Api zrestart=new Api(zstore,znet);zrestart.dispatch("sync",obj("provider","bath"));
        check(zstore.data.getJSONObject("active_bath").getString("orderId").equals("order-fixture"),"重启通过账户订单恢复丢失的开始回执");
        check(znet.starts==2,"洗浴超时没有重发开始请求");
        rejects(()->zrestart.dispatch("ackNoOrder",obj("provider","bath")),"存在服务器订单时不能手动清除");
        check(!zrestart.snapshot().toString().contains("fixture-secret")&&!zrestart.snapshot().toString().contains("fixture-identity"),"洗浴会话秘密不进入界面快照");
        Memory payStore=new Memory(obj("water",obj("token","test-only","uid","user-fixture","phone","13800000000")));
        PaymentNet payNet=new PaymentNet();payNet.store=payStore;Api payApi=new Api(payStore,payNet);
        check(payApi.alipaySign().equals(payNet.signed),"签约参数不破坏原始加号");
        payNet.signed="app_id%3Dfixture%26sign%3Da%2Bb%2Fc%3D";
        check(payApi.alipaySign().equals("app_id=fixture&sign=a+b/c="),"仅解码服务端编码的签约串");
        String payTicket=payApi.dispatch("prepareBill",obj("id","bill-fixture")).getString("ticket");
        check(payNet.prepays==0,"预览账单不触发扣款");
        payNet.amountChanged=true;rejects(()->payApi.beginAlipayPayment(payTicket),"金额变化后必须重新确认");payNet.amountChanged=false;
        String payTimeoutTicket=payApi.dispatch("prepareBill",obj("id","bill-fixture")).getString("ticket");payNet.fail=true;
        rejects(()->payApi.beginAlipayPayment(payTimeoutTicket),"预支付超时保留未知支付结果");
        rejects(()->payApi.beginAlipayPayment(payTimeoutTicket),"支付确认票据不能重复使用");
        rejects(()->payApi.dispatch("prepareBill",obj("id","bill-fixture")),"未知支付阻止再次扣款");
        rejects(()->payApi.dispatch("logout",new JSONObject()),"未知支付时不能退出账户");
        Api payRestart=new Api(payStore,payNet);
        check(payRestart.snapshot().getJSONObject("water").has("payment"),"重启仍保存待确认支付");
        payNet.status=3;payRestart.dispatch("bills",new JSONObject());
        check(!payStore.data.has("payment_water"),"服务端确认已付款后清理支付标记");
        check(payNet.prepays==1,"查询和重启均未重发扣款");
        payNet.status=1;payNet.fail=false;
        String signedTicket=payRestart.dispatch("prepareBill",obj("id","bill-fixture")).getString("ticket");
        check(payRestart.beginAlipayPayment(signedTicket).getString("order").isEmpty(),"已签免密返回空订单串，无需伪造SDK付款");
        payRestart.completeAlipayPayment("bill-fixture");
        check(payStore.data.has("payment_water"),"交易确认回执不等于账单已付款");
        payNet.status=3;payRestart.dispatch("bills",new JSONObject());
        check(!payStore.data.has("payment_water"),"最终依赖服务端账单终态");
        Memory expiredStore=new Memory(obj("water",obj("token","fixture","phone","13800000000"),"active_water",obj("device","test01","phase","unknown")));
        Api expiredApi=new Api(expiredStore,(method,url,headers,body)->obj("code",-99).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rejects(()->expiredApi.dispatch("sync",new JSONObject()),"会话过期给出重新登录提示");
        check(!expiredApi.snapshot().getJSONObject("water").getBoolean("loggedIn"),"过期后不再显示已登录");
        check(expiredStore.data.has("active_water"),"登录过期保留未确认订单");
        Memory emptyAccountStore=new Memory(obj("water",obj("token","fixture","phone","13800000000")));
        Api emptyAccountApi=new Api(emptyAccountStore,(method,url,headers,body)->obj("code",0,"data",new JSONObject()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rejects(()->emptyAccountApi.dispatch("refresh",new JSONObject()),"账号主数据为空不能视为登录有效");
        check(!emptyAccountApi.snapshot().getJSONObject("water").getBoolean("loggedIn"),"成功响应中的空账号也显示登录过期");
        Map<String,String> sdk=new HashMap<>();sdk.put("resultStatus","9000");sdk.put("result","auth_code=\"sdk+code%2Bfixture\"&result_code=\"200\"&user_id=not-used");
        check(alipayAuthCode(sdk).equals("sdk+code+fixture"),"解析SDK授权码并保留原始加号");
        sdk.put("resultStatus","6001");rejects(()->alipayAuthCode(sdk),"用户取消授权不交换登录会话");
        sdk.put("resultStatus","9000");sdk.put("result","auth_code=x&result_code=202");rejects(()->alipayAuthCode(sdk),"SDK外层成功但授权失败不能登录");
        sdk.put("result","auth_code=x&auth_code=y&result_code=200");rejects(()->alipayAuthCode(sdk),"拒绝歧义授权码");
        sdk.put("result","result_code=200");rejects(()->alipayAuthCode(sdk),"缺失授权码不能登录");
        Memory aliStore=new Memory(new JSONObject());AlipayLoginNet aliNet=new AlipayLoginNet();Api aliApi=new Api(aliStore,aliNet);
        JSONObject aliView=aliApi.loginAlipay("sdk-code-fixture");
        check(aliView.getJSONObject("water").getBoolean("loggedIn"),"平台账户确认后才显示登录成功");
        check(!aliView.toString().contains("candidate-token-fixture")&&!aliStore.data.toString().contains("sdk-code-fixture"),"令牌不进页面，授权码不落盘");
        aliNet.emptyAccount=true;rejects(()->aliApi.loginAlipay("sdk-code-fixture"),"候选会话缺少账户时不接受");aliNet.emptyAccount=false;
        aliNet.responseCode=-98;rejects(()->aliApi.loginAlipay("sdk-code-fixture"),"需要绑定手机号时不能伪造已登录");aliNet.responseCode=0;
        Memory heldStore=new Memory(obj("water",obj("token","old-token-fixture","uid","old-user-fixture"),"active_water",obj("device","test01","phase","unknown")));
        Api heldApi=new Api(heldStore,aliNet);rejects(()->heldApi.loginAlipay("sdk-code-fixture"),"有待确认订单时不能换支付宝账户");
        check(heldStore.data.getJSONObject("water").getString("token").equals("old-token-fixture")&&heldStore.data.has("active_water"),"账户不匹配不覆盖旧会话或订单");
        aliNet.uid="old-user-fixture";heldApi.loginAlipay("sdk-code-fixture");
        check(heldStore.data.has("active_water"),"同账户支付宝重新登录保留待确认订单");
        System.out.println("PASS "+checks+" protocol and order-safety checks");
    }
    static JSONObject objectData(String s) throws Exception{return (JSONObject)decodeData(s);}
}
