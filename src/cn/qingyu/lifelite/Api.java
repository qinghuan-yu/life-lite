package cn.qingyu.lifelite;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import static cn.qingyu.lifelite.Protocol.*;

/** 直接连接平台服务。所有有副作用的调用只发一次，超时交给订单查询恢复。 */
public class Api {
    public interface Transport { byte[] call(String method,String url,JSONObject headers,JSONObject body) throws Exception; }
    public interface Store { JSONObject read() throws Exception; void write(JSONObject state) throws Exception; }
    public static final String WATER="water", BATH="bath";
    private static final String WBASE="https://i.ilife798.com/api/v1", ZBASE="https://pm.whxinna.com";
    private static final String ZKEY="6d5dbb85b949447a95ff8fda9a9b759b";
    private final Transport net;
    private final Store store;
    private final JSONObject state;
    private final SecureRandom random=new SecureRandom();
    private String captchaRandom="";
    private long smsSentAt=0;
    private final Map<String,JSONObject> prepared=new HashMap<>();
    public Api(Store store,Transport net) throws Exception {
        this.store=store;this.net=net;this.state=store.read();
        if(state.optInt("deviceMemoryVersion",0)<1) {
            for(String p:Arrays.asList(WATER,BATH)) {
                if(state.optJSONObject(p)==null)continue;
                for(String kind:Arrays.asList("last_","active_")) {
                    JSONObject record=state.optJSONObject(kind+p);
                    if(record!=null&&confirmedUse(record))try{rememberUse(p,record);}catch(IllegalArgumentException ignored){}
                }
                ensureSelected(p);
            }
            state.put("deviceMemoryVersion",1);save();
        }
    }
    private void save() throws Exception {store.write(state);}
    private boolean confirmedUse(JSONObject record) {return record.optBoolean("accepted",false)||!record.optString("orderId").isEmpty()||record.optString("phase").equals("running");}
    private JSONArray devices(String p) {JSONArray list=state.optJSONArray("devices_"+p);return list==null?new JSONArray():list;}
    private JSONObject findDevice(String p,String id) {JSONArray list=devices(p);for(int i=0;i<list.length();i++){JSONObject d=list.optJSONObject(i);if(d!=null&&id.equals(d.optString("id")))return d;}return null;}
    private void ensureSelected(String p) throws Exception {
        if(findDevice(p,state.optString("selected_"+p))!=null)return;
        JSONArray list=devices(p);if(list.length()==0)state.remove("selected_"+p);else state.put("selected_"+p,list.getJSONObject(0).getString("id"));
    }
    private void rememberDevice(String p,String id,String name,String location,boolean select) throws Exception {
        if(!id.matches(p.equals(BATH)?"[0-9]{1,20}":"[A-Za-z0-9_-]{1,80}"))throw new IllegalArgumentException("设备号无效");
        JSONObject previous=findDevice(p,id),device=obj("id",id,"name",name.isEmpty()?id:name,"location",location.isEmpty()&&previous!=null?previous.optString("location"):location);
        JSONArray fresh=new JSONArray().put(device),old=devices(p);
        for(int i=0;i<old.length()&&fresh.length()<20;i++)if(!id.equals(old.getJSONObject(i).optString("id")))fresh.put(old.get(i));
        state.put("devices_"+p,fresh);if(select)state.put("selected_"+p,id);ensureSelected(p);
    }
    private void rememberUse(String p,JSONObject record) throws Exception {rememberDevice(p,record.optString("device"),record.optString("name",p.equals(WATER)?"饮水机":"洗浴水控机"),record.optString("location"),true);}
    private void mergeWaterFavorites(JSONArray remote) throws Exception {
        JSONArray merged=new JSONArray(devices(WATER).toString());Set<String> seen=new HashSet<>();
        for(int i=0;i<merged.length();i++)seen.add(merged.getJSONObject(i).optString("id"));
        for(int i=0;i<remote.length()&&merged.length()<20;i++){
            JSONObject v=remote.getJSONObject(i);String id=v.get("id").toString();
            if(seen.add(id))merged.put(obj("id",id,"name",v.optString("name","饮水机")));
        }
        state.put("devices_"+WATER,merged);ensureSelected(WATER);
    }
    private JSONObject session(String p) throws Exception {
        JSONObject s=state.optJSONObject(p);
        if(s==null) throw new Exception("请先登录"+(p.equals(WATER)?"慧生活798":"住理生活"));
        if(s.optBoolean("expired",false))throw new Exception("登录已过期，请重新登录");
        return s;
    }
    private static JSONObject object(Object x) throws Exception {
        if(!(x instanceof JSONObject)) throw new Exception("平台返回格式异常，请稍后重试"); return (JSONObject)x;
    }
    private JSONObject json(String method,String url,JSONObject headers,JSONObject body) throws Exception {
        try { return new JSONObject(new String(net.call(method,url,headers,body),StandardCharsets.UTF_8)); }
        catch(JSONException malformed) {throw new Exception("平台返回了无法识别的响应");}
    }
    private JSONObject water(String path,JSONObject params) throws Exception {
        JSONObject headers=obj("Authorization",session(WATER).getString("token"),"VersionCode","3.1.8","User-Agent","Android_ilife798_3.1.8");
        JSONObject r=json("GET",WBASE+path+"?"+query(params),headers,null); checkWater(r); return r;
    }
    private void checkWater(JSONObject r) throws Exception {
        if(r.optInt("code")==-99&&state.optJSONObject(WATER)!=null){state.getJSONObject(WATER).put("expired",true);save();}
        if(!r.has("code")||r.optInt("code",Integer.MIN_VALUE)!=0) throw new Exception(r.optInt("code")==-99?"慧生活798登录已过期，请重新登录":r.optString("msg","慧生活798请求未成功"));
    }
    private JSONObject sign(JSONObject params,String key) throws Exception {
        JSONObject p=new JSONObject(params.toString());
        for(String k:keys(p)) {
            Object v=p.get(k);
            if(v instanceof JSONObject || v instanceof JSONArray) {
                p.put("base64_"+k,Base64.getUrlEncoder().encodeToString(v.toString().getBytes(StandardCharsets.UTF_8)));p.remove(k);
            }
        }
        p.put("timestamp",Long.toString(System.currentTimeMillis()/1000));
        byte[] bytes=new byte[24];random.nextBytes(bytes);p.put("noncestr",Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        p.put("sign",signZhuli(p,key));return p;
    }
    private Object zhuli(String path,JSONObject params,boolean platform) throws Exception {
        return zhuli(path,params,platform,null);
    }
    private Object zhuli(String path,JSONObject params,boolean platform,JSONObject candidate) throws Exception {
        String base=ZBASE,key=ZKEY; JSONObject headers=new JSONObject();
        JSONObject p=new JSONObject(params.toString());
        if(!platform) {
            JSONObject s=candidate==null?session(BATH):candidate,u=s.getJSONObject("user_info"),server=s.getJSONObject("server_info");
            base=server.getString("server_addr").replaceAll("/+$","");
            URI uri=new URI(base);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null) throw new Exception("项目服务器没有提供可用的 HTTPS 地址，请使用住理生活");
            key=server.optString("session_secret");if(key.isEmpty())key=server.optString("appsecret");
            if(key.isEmpty()) throw new Exception("项目会话信息不完整，请重新登录");
            p.put("user_id",u.get("id"));p.put("identitycode",u.getString("identity_code"));
            p.put("pid",server.get("server_appid"));p.put("appid",server.get("server_id"));
        } else if(candidate==null&&state.optJSONObject(BATH)!=null) headers.put("Authorization",state.getJSONObject(BATH).optString("platform_token"));
        JSONObject r=json("GET",base+path+"?"+query(sign(p,key)),headers,null);
        if(!platform && r.has("sign") && !MessageDigestCompat.equal(signZhuli(r,key),r.optString("sign"))) throw new Exception("响应校验失败，请重新登录后查询订单");
        if(!r.optBoolean("result",false)) {
            String error=r.optString("err_msg",r.optString("msg","请求未成功"));
            if(error.equals("CAPTCHA_REQUIRED")||error.equals("CAPTCHA_ERROR")) throw new Exception("需要图形验证码，请点击获取并填写");
            if(Arrays.asList("api_sign_error","login_timeout","login_error","forbidden","error_user_id").contains(error)) {if(!platform&&candidate==null&&state.optJSONObject(BATH)!=null){state.getJSONObject(BATH).put("expired",true);save();}throw new Exception("住理生活登录已失效，请重新登录");}
            throw new Exception(r.optString("msg",error));
        }
        return decodeData(r.opt("data"));
    }
    private Object z(String endpoint,JSONObject p) throws Exception {return zhuli("/webapi/v1/"+endpoint,p,false);}
    /** 签约参数只交给原生支付宝 SDK，不发送到网页或日志。 */
    public synchronized String alipaySign() throws Exception {
        String sign=water("/ui/app/sign-pga",obj("type",20)).getJSONObject("data").optString("sign");
        if(sign.isEmpty())throw new Exception("平台没有返回签约参数，请在慧生活798检查免密协议");
        return !sign.contains("&")&&sign.toLowerCase(Locale.ROOT).contains("%3d")?URLDecoder.decode(sign,"UTF-8"):sign;
    }
    /** 仅由原生 SDK 授权完成后调用；授权码不持久化、不进入 JS。 */
    public synchronized JSONObject loginAlipay(String code) throws Exception {
        if(code==null||code.isEmpty()||code.length()>4096)throw new Exception("请重新进行支付宝授权");
        JSONObject body=obj("openCode",code);
        JSONObject r=json("POST",WBASE+"/acc/login?sign="+sign798(body),obj("VersionCode","3.1.9","User-Agent","iLife798/3.1.9 (iPhone; iOS 17.0; Scale/3.00) iOS_ilife798_3.1.9"),body);
        if(r.optInt("code",-1)==-98)throw new Exception("请先在慧生活798完成手机号绑定，再用支付宝登录");
        if(r.optInt("code",-1)!=0)throw new Exception("平台未接受支付宝登录（"+r.optInt("code",-1)+"），请重新授权");
        JSONObject saved=object(object(r.opt("data")).opt("al"));String token=saved.optString("token");
        if(token.isEmpty())throw new Exception("平台没有返回有效登录凭据");
        // 用候选令牌读取账户确认身份；验证完毕前保留旧会话和订单。
        JSONObject master=json("GET",WBASE+"/ui/app/master",obj("Authorization",token,"VersionCode","3.1.9","User-Agent","Android_ilife798_3.1.9"),null);
        if(master.optInt("code",-1)!=0)throw new Exception("支付宝已返回，平台账户尚未确认，请重试登录");
        JSONObject data=object(master.opt("data")),account=data.optJSONObject("account");
        if(account==null||account.optString("id").isEmpty())throw new Exception("平台账户未确认，请重新登录");
        String uid=account.get("id").toString();
        if(!saved.optString("uid").isEmpty()&&!uid.equals(saved.optString("uid")))throw new Exception("登录账户校验未通过");
        JSONObject old=state.optJSONObject(WATER);boolean same=old!=null&&uid.equals(old.optString("uid"));
        if((state.has("active_water")||state.has("payment_water"))&&!same)throw new Exception("还有待确认订单，请使用原支付宝账户登录");
        saved.put("uid",uid);saved.put("phone",account.optString("pn"));saved.put("loginMethod","alipay");saved.remove("expired");
        if(!same){state.remove("devices_water");state.remove("last_water");state.remove("selected_water");}
        JSONArray favos=data.optJSONArray("favos");
        if(favos!=null)mergeWaterFavorites(favos);
        prepared.remove(WATER);prepared.remove("bill");state.put(WATER,saved);save();return snapshot();
    }
    /** 每次从住理平台获取签名授权请求，只交给原生 SDK。 */
    public synchronized String zhuliAlipayAuthInfo() throws Exception {
        JSONObject response=json("GET",ZBASE+"/webapi/oauth/alipay/auth_info?"+query(sign(new JSONObject(),ZKEY)),new JSONObject(),null);
        if(!response.optBoolean("result",false))throw new Exception(response.optString("msg","住理生活授权参数获取失败"));
        Object data=decodeData(response.opt("data"));
        if(!(data instanceof String)||((String)data).isEmpty()||((String)data).length()>16000)throw new Exception("住理生活没有返回有效授权参数，请稍后重试");
        // 官方桥接传入 JSON.stringify(response)，保留解码后的响应封装。
        response.put("data",data);return response.toString();
    }
    /** 候选住理会话通过账户查询后才替换旧会话，授权码不保存。 */
    public synchronized JSONObject loginZhuliAlipay(String code,String os,String model) throws Exception {
        if(code==null||code.isEmpty()||code.length()>4096)throw new Exception("请重新进行支付宝授权");
        JSONObject params=obj("authInfo",obj("auth_code",code,"oauth_type","alipay_app","sign_type","RSA"),"systemInfo",obj("appVersion","3.12.11","systemType","Android","systemVersion",os,"deviceModel",model,"deviceToken",""),"app_version","3.12.11");
        JSONObject data;
        try {data=object(zhuli("/webapi/oauth/login",params,true,new JSONObject()));}
        catch(Exception e){if("授权信息未找到".equals(e.getMessage())||"openid错误".equals(e.getMessage()))throw new Exception("请先在住理生活用支付宝登录并完成手机号绑定");throw e;}
        JSONObject user=data.optJSONObject("user_info"),server=data.optJSONObject("server_info");
        if(user==null||server==null)throw new Exception("请先在住理生活完成手机号和学校绑定，再重新登录");
        if(user.has("is_pwd")&&user.optInt("is_pwd",1)==0)throw new Exception("请先在住理生活完成账户设置，再重新登录");
        String uid=user.optString("id"),phone=user.optString("phone");
        if(uid.isEmpty()||user.optString("identity_code").isEmpty()||data.optString("platform_token").isEmpty()||server.optString("server_id").isEmpty()||server.optString("server_appid").isEmpty())throw new Exception("住理生活登录信息不完整，请在官方应用核对账户");
        JSONObject candidate=obj("user_info",user,"server_info",server,"platform_token",data.getString("platform_token"),"phone",phone,"loginMethod","alipay");
        JSONObject detail=object(zhuli("/webapi/v1/staff/get_user_detail_by_id",obj("id",user.get("id")),false,candidate));
        if(!uid.equals(detail.optString("id")))throw new Exception("住理生活账户校验未通过，请重新登录");
        JSONObject old=state.optJSONObject(BATH);boolean same=sameZhuliAccount(old,candidate);
        if(state.has("active_bath")&&!same)throw new Exception("还有待确认洗浴订单，请使用原账户和原学校登录");
        for(String key:keys(detail))if(!key.equals("identity_code"))user.put(key,detail.get(key));
        Object balance=user.opt("total_balance");if(balance==null)balance=user.opt("balance");
        candidate.put("balance",balance instanceof Number||balance instanceof String?balance:JSONObject.NULL);
        if(!same){state.remove("devices_bath");state.remove("last_bath");state.remove("selected_bath");}
        prepared.remove(BATH);state.put(BATH,candidate);save();return snapshot();
    }
    private boolean sameZhuliAccount(JSONObject old,JSONObject candidate) throws Exception {
        if(old==null||old.optJSONObject("user_info")==null||old.optJSONObject("server_info")==null)return false;
        if(!old.getJSONObject("user_info").optString("id").equals(candidate.getJSONObject("user_info").optString("id")))return false;
        for(String key:Arrays.asList("server_id","server_appid","server_addr"))if(!old.getJSONObject("server_info").optString(key).equals(candidate.getJSONObject("server_info").optString(key)))return false;
        return true;
    }
    private JSONObject waterBill(String id) throws Exception {
        if(!id.matches("[A-Za-z0-9_-]{1,80}"))throw new Exception("账单号无效");
        JSONObject bill=water("/bill/view-full",obj("id",id)).getJSONObject("data").getJSONObject("bill");
        if(!id.equals(bill.optString("id")))throw new Exception("账单不匹配，请刷新");
        JSONObject owner=bill.optJSONObject("owner");String uid=session(WATER).optString("uid");
        if(owner!=null&&!uid.isEmpty()&&!uid.equals(owner.optString("id")))throw new Exception("账单不属于当前账户");
        return bill;
    }
    private String billAmount(JSONObject bill) throws Exception {
        try {java.math.BigDecimal amount=new java.math.BigDecimal(bill.get("payment").toString());if(amount.signum()<=0)throw new Exception();return amount.stripTrailingZeros().toPlainString();}
        catch(Exception e){throw new Exception("账单金额未确认，请在慧生活798核对");}
    }
    private JSONObject prepareBill(String id) throws Exception {
        if(state.has("payment_water"))throw new Exception("上一笔支付待确认，请先刷新账单");
        JSONObject bill=waterBill(id);int status=bill.optInt("status");
        if(status!=1&&status!=4)throw new Exception("这笔账单当前不需要支付");
        JSONArray channels=water("/bill/pay/channels",obj("id",id)).getJSONObject("data").optJSONArray("channels");
        boolean allowed=false;if(channels!=null)for(int i=0;i<channels.length();i++)if(channels.getJSONObject(i).optInt("type")==21)allowed=true;
        if(!allowed)throw new Exception("账单暂不支持支付宝支付");
        JSONObject ticket=obj("ticket",UUID.randomUUID().toString(),"id",id,"amount",billAmount(bill),"time",System.currentTimeMillis());
        prepared.put("bill",ticket);return new JSONObject(ticket.toString());
    }
    public synchronized JSONObject beginAlipayPayment(String ticketId) throws Exception {
        JSONObject ticket=prepared.remove("bill");
        if(ticket==null||!ticketId.equals(ticket.optString("ticket"))||System.currentTimeMillis()-ticket.getLong("time")>120000)throw new Exception("支付确认已过期，请重新查看账单");
        if(state.has("payment_water"))throw new Exception("上一笔支付待确认");
        JSONObject bill=waterBill(ticket.getString("id"));int status=bill.optInt("status");
        if((status!=1&&status!=4)||!billAmount(bill).equals(ticket.getString("amount")))throw new Exception("账单状态或金额已变化，请重新确认");
        JSONObject marker=obj("id",ticket.getString("id"),"amount",ticket.getString("amount"));state.put("payment_water",marker);save();
        // prepay 可能直接扣款，先落盘，任何异常都不重发。
        Object order=water("/trans/prepay/21",obj("id",ticket.getString("id"))).opt("data");
        if(order!=null&&order!=JSONObject.NULL&&!(order instanceof String))throw new Exception("支付结果待确认，请查询账单");
        return obj("id",ticket.getString("id"),"order",order instanceof String?(String)order:"");
    }
    public synchronized JSONObject completeAlipayPayment(String id) throws Exception {
        try{water("/trans/confirm",obj("id",id));}finally{syncPayment();}
        return obj("message",state.has("payment_water")?"支付待确认，请刷新账单":"请查看最新账单状态");
    }
    private void syncPayment() throws Exception {
        JSONObject pending=state.optJSONObject("payment_water");if(pending==null)return;
        JSONObject bill=waterBill(pending.getString("id"));int status=bill.optInt("status");
        pending.put("status",status);
        if(status==3||status==9||status==4)state.remove("payment_water");
        save();
    }
    public synchronized JSONObject dispatch(String action,JSONObject a) throws Exception {
        String p=a.optString("provider",WATER);
        if(!p.equals(WATER)&&!p.equals(BATH)) throw new Exception("未知服务");
        switch(action) {
            case "snapshot":return snapshot();
            case "captcha":return captcha(p,a.optString("phone"));
            case "sms": {
                if(System.currentTimeMillis()-smsSentAt<60000) throw new Exception("请等待一分钟后再获取短信验证码");
                if(captchaRandom.isEmpty()) throw new Exception("请先获取图形验证码");
                JSONObject r=json("POST",WBASE+"/acc/login/code",obj("VersionCode","3.1.8","User-Agent","Android_ilife798_3.1.8"),obj("s",captchaRandom,"authCode",a.getString("captcha"),"un",a.getString("phone")));
                checkWater(r);smsSentAt=System.currentTimeMillis();return obj("message","短信已发送");
            }
            case "login":return login(p,a);
            case "refresh":return refresh(p);
            case "prepare":return prepare(p,a.getString("device"));
            case "start":return start(p,a.getString("ticket"),a.optInt("paymentType",0));
            case "stop":return stop(p);
            case "sync":return sync(p);
            case "bills":return bills(p);
            case "prepareBill":if(!p.equals(WATER))throw new Exception("当前服务不支持此支付方式");return prepareBill(a.getString("id"));
            case "saveDevice":return saveDevice(p,a);
            case "savePreparedDevice":return savePreparedDevice(p,a.getString("ticket"));
            case "selectDevice": {
                session(p);String id=a.getString("id");if(findDevice(p,id)==null)throw new Exception("设备已移除，请重新选择");
                if(state.has("active_"+p))throw new Exception("请先结束当前设备，再更换常用设备");
                state.put("selected_"+p,id);prepared.remove(p);save();return snapshot();
            }
            case "removeDevice":return removeDevice(p,a.getString("id"));
            case "ackIdle": {
                JSONObject active=state.optJSONObject("active_"+p);
                if(!p.equals(WATER)||active==null) throw new Exception("没有需要确认的接水记录");
                if(!available798(water("/ui/app/dev/status",obj("did",active.getString("device"),"more",false)).getJSONObject("data"))) throw new Exception("设备还未确认空闲，请先结束接水");
                active.put("phase","idle_confirmed");finish(p,active);return snapshot();
            }
            case "ackNoOrder": {
                if(!p.equals(BATH))throw new Exception("不支持此操作");
                sync(p);JSONObject active=state.optJSONObject("active_"+p);
                if(active==null)return snapshot();
                if(!active.optString("orderId").isEmpty()||!active.optBoolean("emptyConfirmed",false))throw new Exception("仍存在订单，请先确认结束");
                active.put("phase","manually_confirmed");finish(p,active);return snapshot();
            }
            case "logout": {
                if(state.optJSONObject("active_"+p)!=null||p.equals(WATER)&&state.has("payment_water")) throw new Exception("请先确认当前订单与支付结果，再退出账户");
                state.remove(p);state.remove("devices_"+p);state.remove("last_"+p);state.remove("selected_"+p);prepared.remove(p);save();return snapshot();
            }
            default:throw new Exception("未知操作");
        }
    }
    private JSONObject captcha(String p,String phone) throws Exception {
        if(p.equals(WATER)) {
            captchaRandom=Double.toString(random.nextDouble());
            byte[] bytes=net.call("GET",WBASE+"/captcha/?"+query(obj("s",captchaRandom,"r",Long.toString(System.currentTimeMillis()))),new JSONObject(),null);
            return obj("image","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes));
        }
        if(!phone.matches("1[0-9]{10}")) throw new Exception("请先填写手机号");
        JSONObject d=object(zhuli("/webapi/users/get_login_code",obj("phone",phone),true));
        String img=d.getString("codeImg");
        if(img.trim().startsWith("<svg")) img="data:image/svg+xml;base64,"+Base64.getEncoder().encodeToString(img.getBytes(StandardCharsets.UTF_8));
        else if(!img.startsWith("data:image/")) throw new Exception("验证码图片格式未识别，请使用住理生活登录");
        return obj("image",img);
    }
    private JSONObject login(String p,JSONObject a) throws Exception {
        String phone=a.getString("phone").trim();
        if(!phone.matches("1[0-9]{10}")) throw new Exception("请输入正确的手机号");
        JSONObject old=state.optJSONObject(p);
        if((state.optJSONObject("active_"+p)!=null||p.equals(WATER)&&state.has("payment_water")) && old!=null&&!phone.equals(old.optString("phone"))) throw new Exception("存在未确认订单，请使用原账户登录处理");
        JSONObject saved;
        if(p.equals(WATER)) {
            JSONObject body=obj("authCode",a.getString("code").trim(),"un",phone);
            JSONObject r=json("POST",WBASE+"/acc/login?sign="+sign798(body),obj("VersionCode","3.1.9","User-Agent","iLife798/3.1.9 (iPhone; iOS 17.0; Scale/3.00) iOS_ilife798_3.1.9"),body);
            checkWater(r);saved=r.getJSONObject("data").getJSONObject("al");
            if(saved.optString("token").isEmpty()) throw new Exception("登录响应缺少凭据");
        } else {
            JSONObject params=obj("phone",phone,"pwd",a.getString("password"),"code",a.optString("captcha"),"appVersion","3.12.11","systemType","Android","systemVersion",a.optString("os","Android"),"deviceModel",a.optString("model","Android"),"deviceToken","","user_extends",obj("isAlipayAppExist",false));
            saved=object(zhuli("/webapi/users/login",params,true));
            if(saved.optJSONObject("user_info")==null || saved.optJSONObject("server_info")==null) throw new Exception("请先在住理生活完成学校绑定，再重新登录");
            saved.getJSONObject("user_info").getString("identity_code");
        }
        saved.put("phone",phone);
        boolean changed=old!=null&&(p.equals(BATH)?!sameZhuliAccount(old,saved):!phone.equals(old.optString("phone")));
        if(changed&&state.has("active_"+p))throw new Exception("还有待确认订单，请使用原账户和原学校登录");
        if(changed) {state.remove("devices_"+p);state.remove("last_"+p);state.remove("selected_"+p);}
        state.put(p,saved);save();return snapshot();
    }
    public synchronized JSONObject snapshot() throws Exception {
        JSONObject out=new JSONObject();
        for(String p:Arrays.asList(WATER,BATH)) {
            JSONObject s=state.optJSONObject(p),view=obj("loggedIn",s!=null&&!s.optBoolean("expired",false),"expired",s!=null&&s.optBoolean("expired",false),"devices",state.optJSONArray("devices_"+p)==null?new JSONArray():state.getJSONArray("devices_"+p));
            view.put("selected",state.optString("selected_"+p));
            if(s!=null) {
                String phone=s.optString("phone");view.put("phone",phone.length()==11?phone.substring(0,3)+"****"+phone.substring(7):"");
                view.put("balance",s.opt("balance"));
                if(p.equals(BATH)) view.put("project",s.getJSONObject("server_info").optString("projectname","住理生活"));
            }
            view.put("active",state.opt("active_"+p));view.put("last",state.opt("last_"+p));out.put(p,view);
            if(p.equals(WATER))view.put("payment",state.opt("payment_water"));
        }
        return out;
    }
    private JSONObject refresh(String p) throws Exception {
        JSONObject s=session(p);
        if(p.equals(WATER)) {
            JSONObject d=water("/ui/app/master",new JSONObject()).getJSONObject("data");
            if(d.optJSONObject("account")==null) {s.put("expired",true);save();throw new Exception("登录已失效，请重新登录");}
            JSONArray f=d.optJSONArray("favos");
            if(f!=null)mergeWaterFavorites(f);
            JSONObject wallets=water("/acc/wallet/owner",new JSONObject()).getJSONObject("data");
            List<JSONObject> candidates=new ArrayList<>();
            if(wallets.optJSONObject("aw")!=null)candidates.add(wallets.getJSONObject("aw"));
            JSONArray eps=wallets.optJSONArray("eps");if(eps!=null)for(int i=0;i<eps.length();i++)candidates.add(eps.getJSONObject(i));
            List<JSONObject> matches=new ArrayList<>();for(JSONObject c:candidates) if(!s.optString("eid").isEmpty()&&s.optString("eid").equals(c.optString("id")))matches.add(c);
            JSONObject chosen=matches.size()==1?matches.get(0):candidates.size()==1?candidates.get(0):null;
            s.put("balance",chosen==null?JSONObject.NULL:chosen.opt("balance"));
        } else {
            JSONObject u=s.getJSONObject("user_info"), detail=object(z("staff/get_user_detail_by_id",obj("id",u.get("id"))));
            for(String k:keys(detail))u.put(k,detail.get(k));
            // 不推测多钱包余额字段，展示服务器明确返回的 balance。
            Object balance=u.opt("total_balance");if(balance==null)balance=u.opt("balance");
            s.put("balance",balance instanceof Number||balance instanceof String?balance:JSONObject.NULL);
        }
        save();sync(p);return snapshot();
    }
    private JSONObject prepare(String p,String input) throws Exception {
        session(p);sync(p);
        if(p.equals(WATER)&&state.has("payment_water"))throw new Exception("上一笔支付待确认，请先刷新账单");
        if(state.optJSONObject("active_"+p)!=null) throw new Exception("请先处理正在进行或待确认的订单");
        String id=deviceId(input,p.equals(BATH));JSONObject d;JSONArray payments=new JSONArray();
        if(p.equals(WATER)) {
            if(input.contains("://")) {
                JSONObject resolved=water("/qr/use",obj("id",id)).getJSONObject("data");id=resolved.getJSONObject("dev").get("id").toString();
            }
            JSONObject data=water("/ui/app/dev/home/1",obj("did",id,"apply",6)).getJSONObject("data");
            JSONObject live=water("/ui/app/dev/status",obj("did",id,"more",false)).getJSONObject("data");
            if(!available798(live)) throw new Exception(unavailable798(live));
            payments=waterPayments(data);
            if(payments.length()==0)throw new Exception("这台设备没有返回可用的支付宝或钱包渠道，请在慧生活798使用");
            if(data.optBoolean("prepay",false))throw new Exception("这台设备需要预付费，请在慧生活798完成");
            d=data.getJSONObject("device");
        } else {
            d=object(z("device/get_by_id",obj("id",id)));bathNetwork(d);
            if(d.optInt("status",-1)==6)throw new Exception("设备正在维护中");
            id=d.get("device_id").toString();
        }
        JSONObject ticket=obj("ticket",UUID.randomUUID().toString(),"time",System.currentTimeMillis(),"device",id,"raw",d,"name",d.optString("device_name",d.optString("name",p.equals(WATER)?"饮水机":"洗浴水控机")));
        ticket.put("location",(d.optString("building_name","")+" "+d.optString("floor_name","")+" "+d.optString("room_name","")).trim());
        ticket.put("payments",payments);prepared.put(p,ticket);
        return obj("ticket",ticket.getString("ticket"),"id",id,"name",ticket.getString("name"),"payments",payments,"billing","按设备原费率计费","location",d.optString("building_name","")+" "+d.optString("floor_name","")+" "+d.optString("room_name",""));
    }
    private JSONArray waterPayments(JSONObject data) throws Exception {
        JSONArray result=new JSONArray(),items=data.optJSONArray("payItems");
        if(items!=null)for(int i=0;i<items.length();i++) {
            JSONObject item=items.optJSONObject(i);int type=item==null?0:item.optInt("type");
            if((type==21||type==91)&&!hasPayment(result,type))result.put(type);
        }
        return result;
    }
    private boolean hasPayment(JSONArray items,int type) {for(int i=0;i<items.length();i++)if(items.optInt(i)==type)return true;return false;}
    private JSONObject start(String p,String ticketId,int paymentType) throws Exception {
        JSONObject ticket=prepared.remove(p);
        if(ticket==null||!ticket.getString("ticket").equals(ticketId)||System.currentTimeMillis()-ticket.getLong("time")>120000) throw new Exception("设备确认已过期，请重新查询");
        sync(p);if(state.optJSONObject("active_"+p)!=null) throw new Exception("存在未完成订单，请先处理");
        String id=ticket.getString("device");
        JSONObject params=null;
        if(p.equals(WATER)) {
            if(!hasPayment(ticket.getJSONArray("payments"),paymentType))throw new Exception("请选择设备支持的支付方式");
            JSONObject detail=water("/ui/app/dev/home/1",obj("did",id,"apply",6)).getJSONObject("data");
            JSONObject live=water("/ui/app/dev/status",obj("did",id,"more",false)).getJSONObject("data");
            if(!available798(live))throw new Exception(unavailable798(live));
            if(detail.optBoolean("prepay",false)||!hasPayment(waterPayments(detail),paymentType))throw new Exception("设备支付方式已变化，请重新查询");
        } else {
            JSONObject d=object(z("device/get_by_id",obj("id",id)));
            params=obj("device_id",id,"send_to_device",1,"staff_id",session(p).getJSONObject("user_info").get("id"),"act","consume","net_type",bathNetwork(d),"port_name",0);
            z("device/collect_gprs_device_status",params);
        }
        JSONObject active=obj("device",id,"name",ticket.getString("name"),"location",ticket.optString("location"),"phase","starting","startedAt",System.currentTimeMillis());
        if(p.equals(WATER))active.put("paymentType",paymentType);
        state.put("active_"+p,active);save();
        try {
            if(p.equals(WATER)) {
                water("/dev/start",obj("did",id,"upgrade",true,"ptype",paymentType,"args","","rcp",false,"cnt",1));active.put("phase","starting");active.put("accepted",true);
            } else {
                JSONObject result=object(z("consume/create_order",params));
                active.put("orderId",result.get("order_id").toString());active.put("phase","starting");active.put("serverStatus",result.optString("status"));
            }
            rememberUse(p,active);save();
        } catch(Exception error) {
            active.put("phase","unknown");save();
            throw new Exception("开始结果未确认："+error.getMessage()+"。请刷新订单；不要重复开始。");
        }
        return snapshot();
    }
    private JSONObject sync(String p) throws Exception {
        if(state.optJSONObject(p)==null)return snapshot();
        JSONObject active=state.optJSONObject("active_"+p);
        if(p.equals(WATER)) {
            if(active==null)return snapshot();
            JSONObject d=water("/ui/app/dev/status",obj("did",active.getString("device"),"more",false)).getJSONObject("data");
            if(available798(d)) {
                if(active.optString("phase").equals("unknown")||active.optString("phase").equals("starting"))active.put("idle",true);
                else finish(p,active);
            } else {
                active.put("idle",false);JSONObject device=d.optJSONObject("device"),gene=device==null?null:device.optJSONObject("gene");
                if(device!=null&&device.optInt("status")>0&&gene!=null&&Arrays.asList(1,10,20,30).contains(gene.optInt("status"))) {
                    if(active.optBoolean("accepted",false)&&active.optString("phase").equals("starting"))active.put("phase","running");
                }
            }
        } else {
            if(active==null || active.optString("orderId").isEmpty()) {
                Object list=z("consume/get_staff_consumeing",obj("net_type","[1,5,6,16,32,64,80]"));
                if(!(list instanceof JSONArray)) throw new Exception("无法确认当前消费订单，请稍后刷新");
                JSONArray orders=(JSONArray)list;
                if(active!=null)active.put("emptyConfirmed",true);
                for(int i=0;i<orders.length();i++) {
                    JSONObject o=orders.getJSONObject(i);
                    if(!"bath".equals(o.optString("consume_type")))continue;
                    if(active!=null && !active.optString("device").equals(o.optString("device_id")))throw new Exception("有另一台设备正在洗浴，请在住理生活核对订单");
                    active=obj("device",o.get("device_id").toString(),"orderId",o.get("order_id").toString(),"name",o.optString("device_name","洗浴水控机"),"phase","running","startedAt",System.currentTimeMillis());
                    state.put("active_"+p,active);break;
                }
            }
            if(active!=null&&!active.optString("orderId").isEmpty()) {
                JSONObject bill=object(z("staff/get_bill_detail_by_id",obj("bill_id",active.getString("orderId"),"use_type","consume")));
                active.put("serverStatus",bill.optString("status"));
                if(bill.has("consume_money"))active.put("amount",bill.get("consume_money"));
                if(finished(bill))finish(p,active);
                else if(bill.optString("status").equals("consuming")){active.put("phase","running");rememberUse(p,active);}
                else active.put("phase","pending");
            }
        }
        save();return snapshot();
    }
    private void finish(String p,JSONObject active) throws Exception {if(confirmedUse(active))rememberUse(p,active);state.put("last_"+p,active);state.remove("active_"+p);save();}
    private JSONObject stop(String p) throws Exception {
        sync(p);JSONObject a=state.optJSONObject("active_"+p);
        if(a==null)throw new Exception("当前没有待结束订单");
        if(p.equals(BATH)&&a.optString("orderId").isEmpty())throw new Exception("订单号还未确认，请刷新或前往住理生活检查");
        a.put("phase","stopping");save();
        try {
            if(p.equals(WATER)){
                JSONObject detail=water("/ui/app/dev/home/1",obj("did",a.getString("device"),"apply",6)).getJSONObject("data");
                JSONObject user=detail.optJSONObject("user");String uid=session(WATER).optString("uid");
                if(user!=null&&!user.optString("id").isEmpty()&&!uid.equals(user.optString("id")))throw new Exception("设备当前由其他账户使用，请在官方应用核对");
                if(!a.optBoolean("accepted",false)&&(user==null||uid.isEmpty()||!uid.equals(user.optString("id"))))throw new Exception("无法确认设备归属，请在官方应用核对");
                water("/dev/end",obj("did",a.getString("device"),"rcp",false));
            }
            else z("consume/remote_end_order",obj("order_id",a.getString("orderId"),"device_id",a.getString("device")));
            // 收到结束请求回执不等于结算完成，保留订单直到服务器确认。
            save();return sync(p);
        } catch(Exception e) {a.put("phase","stop_unknown");save();throw new Exception("结束结果未确认，请刷新检查；设备可能仍在计费。"+e.getMessage());}
    }
    private JSONObject bills(String p) throws Exception {
        Object data;
        if(p.equals(WATER)) {
            syncPayment();
            data=water("/bill/lst-owner",obj("page",0,"size",20,"hasCount",false)).opt("data");
            if(data instanceof JSONObject) {
                JSONObject d=(JSONObject)data;data=d.opt("records");if(data==null)data=d.opt("items");if(data==null)data=d.opt("list");
            }
        } else {
            // 消费记录接口按起止时间查询，不使用充值记录的分页参数。
            TimeZone zone=TimeZone.getTimeZone("Asia/Shanghai");
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT);format.setTimeZone(zone);
            Calendar end=Calendar.getInstance(zone);end.set(Calendar.HOUR_OF_DAY,23);end.set(Calendar.MINUTE,59);end.set(Calendar.SECOND,59);end.set(Calendar.MILLISECOND,0);
            Calendar start=(Calendar)end.clone();start.add(Calendar.DAY_OF_MONTH,-30);
            data=z("consume/list_record_by_staffid",obj("staff_id",session(p).getJSONObject("user_info").get("id"),"start",format.format(start.getTime()),"end",format.format(end.getTime())));
        }
        return obj("records",data==null?new JSONArray():data);
    }
    private JSONObject saveDevice(String p,JSONObject a) throws Exception {
        session(p);
        String id=deviceId(a.getString("id"),p.equals(BATH)),name=a.optString("name",id);
        if(p.equals(WATER))water("/dev/favo",obj("did",id,"remove",0));
        rememberDevice(p,id,name,a.optString("location"),true);prepared.remove(p);save();return snapshot();
    }
    private JSONObject savePreparedDevice(String p,String ticketId) throws Exception {
        JSONObject ticket=prepared.get(p);
        if(ticket==null||!ticketId.equals(ticket.optString("ticket"))||System.currentTimeMillis()-ticket.getLong("time")>120000)throw new Exception("设备信息已过期，请重新查询");
        return saveDevice(p,obj("id",ticket.getString("device"),"name",ticket.getString("name"),"location",ticket.optString("location")));
    }
    private JSONObject removeDevice(String p,String id) throws Exception {
        JSONObject active=state.optJSONObject("active_"+p);if(active!=null&&id.equals(active.optString("device")))throw new Exception("请先结束正在使用的设备");
        if(p.equals(WATER))water("/dev/favo",obj("did",id,"remove",1));
        JSONArray list=state.optJSONArray("devices_"+p),fresh=new JSONArray();
        if(list!=null)for(int i=0;i<list.length();i++)if(!list.getJSONObject(i).optString("id").equals(id))fresh.put(list.get(i));
        state.put("devices_"+p,fresh);ensureSelected(p);prepared.remove(p);save();return snapshot();
    }
    private static class MessageDigestCompat {
        static boolean equal(String a,String b) {return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    }
    public static class HttpTransport implements Transport {
        public byte[] call(String method,String url,JSONObject headers,JSONObject body) throws Exception {
            HttpsGuard(url);
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestMethod(method);
            c.setRequestProperty("Accept","application/json,image/*");c.setRequestProperty("Cache-Control","no-store");
            for(String k:keys(headers))c.setRequestProperty(k,headers.getString(k));
            try {
                if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);try(OutputStream o=c.getOutputStream()){o.write(data);}}
                int status=c.getResponseCode();
                if(status<200||status>=300)throw new IOException("服务请求未完成（HTTP "+status+"）");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>4*1024*1024)throw new IOException("响应过大");}return out.toByteArray();
                }
            } catch(java.net.SocketTimeoutException e){throw new IOException("网络超时");}
            catch(javax.net.ssl.SSLException e){throw new IOException("无法建立安全连接，请检查网络");}
            catch(java.net.UnknownHostException e){throw new IOException("无法连接服务，请检查网络");}
            finally {c.disconnect();}
        }
        private void HttpsGuard(String url) throws Exception {if(!url.startsWith("https://"))throw new Exception("仅支持 HTTPS 服务");}
    }
}
