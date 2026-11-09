package cn.qingyu.lifelite;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** 两个平台的协议转换；不持有账户凭据，不执行网络请求。 */
public final class Protocol {
    private Protocol() {}
    public static JSONObject obj(Object... pairs) throws JSONException {
        JSONObject o = new JSONObject();
        for (int i=0;i<pairs.length;i+=2) o.put((String)pairs[i],pairs[i+1]);
        return o;
    }
    public static String md5(String text) throws Exception {
        StringBuilder b = new StringBuilder();
        for(byte v: MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) b.append(String.format(Locale.ROOT,"%02x",v & 255));
        return b.toString();
    }
    public static List<String> keys(JSONObject o) {
        List<String> keys = new ArrayList<>(); o.keys().forEachRemaining(keys::add); Collections.sort(keys); return keys;
    }
    public static String signZhuli(JSONObject params,String secret) throws Exception {
        List<String> pairs = new ArrayList<>();
        for(String k:keys(params)) {
            Object v=params.get(k);
            if(k.equals("sign") || v==JSONObject.NULL || v.equals("")) continue;
            String encoded=v instanceof String ? JSONObject.quote((String)v) : v.toString();
            pairs.add(k+"="+encoded);
        }
        String raw=String.join("&",pairs).replace("\"", "").replace(" ", "")+"&key="+secret;
        return md5(raw).toUpperCase(Locale.ROOT);
    }
    public static String sign798(JSONObject params) throws Exception {
        List<String> pairs=new ArrayList<>();
        for(String k:keys(params)) if(!params.isNull(k)&&!params.optString(k).isEmpty()) pairs.add(k+"="+params.get(k));
        String s=String.join("&",pairs)+"aB3dF6hJ9kL2nP5rT8wY1eG4iM7oQ0v";
        StringBuilder encoded=new StringBuilder();
        for(byte v:s.getBytes(StandardCharsets.UTF_8)) {
            int c=v&255;
            if(c>=48&&c<=57 || c>=65&&c<=90 || c>=97&&c<=122 || "-._~!$&'()*+;=?@/".indexOf(c)>=0) encoded.append((char)c);
            else encoded.append(String.format(Locale.ROOT,"%%%02X",c));
        }
        return md5(encoded.toString());
    }
    public static String query(JSONObject o) throws Exception {
        List<String> pairs=new ArrayList<>();
        for(String k:keys(o)) if(!o.isNull(k)) pairs.add(URLEncoder.encode(k,"UTF-8")+"="+URLEncoder.encode(o.get(k).toString(),"UTF-8"));
        return String.join("&",pairs);
    }
    /** 只提取 SDK 成功回调的一次性授权码，不将完整回调交给页面或日志。 */
    public static String alipayAuthCode(Map<String,String> result) throws Exception {
        String status=result==null?"":result.get("resultStatus");
        if("6001".equals(status))throw new Exception("已取消支付宝登录");
        if(!"9000".equals(status))throw new Exception("支付宝授权未完成，请重试");
        String raw=result.get("result");if(raw==null||raw.length()>16000)throw new Exception("支付宝授权结果不完整");
        Map<String,String> values=new HashMap<>();
        for(String pair:raw.split("&")) {
            String[] kv=pair.split("=",2);if(kv.length!=2)continue;
            if(!kv[0].equals("auth_code")&&!kv[0].equals("result_code"))continue;
            if(values.containsKey(kv[0]))throw new Exception("支付宝授权结果异常");
            String v=kv[1];if(v.startsWith("\"")&&v.endsWith("\"")&&v.length()>=2)v=v.substring(1,v.length()-1);
            // SDK 文本不是表单；原始加号不能转换为空格。
            v=URLDecoder.decode(v.replace("+","%2B"),"UTF-8");values.put(kv[0],v);
        }
        String code=values.get("auth_code");
        if(!"200".equals(values.get("result_code"))||code==null||code.isEmpty()||code.length()>4096)throw new Exception("支付宝没有返回有效授权码，请重新授权");
        return code;
    }
    public static Object decodeData(Object data) throws Exception {
        if(!(data instanceof String)) return data;
        String s=(String)data;
        try {
            s=new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);
        } catch(IllegalArgumentException bad) {
            try {s=new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);} catch(IllegalArgumentException ignored) {return data;}
        }
        try {
            String trimmed=s.trim();
            if(trimmed.startsWith("{"))return new JSONObject(trimmed);
            if(trimmed.startsWith("["))return new JSONArray(trimmed);
            return s;
        } catch(JSONException ignored) {return s;}
    }
    public static String deviceId(String input,boolean zhuli) throws Exception {
        String s=input.trim();
        if(s.isEmpty()||s.length()>2048) throw new Exception("请输入设备号或设备二维码内容");
        if(s.matches("[A-Za-z0-9_-]{1,80}")) {
            if(zhuli && !s.matches("[0-9]{1,20}")) throw new Exception("住理生活设备号应为数字");
            return s;
        }
        URI u=new URI(s);
        String host=u.getHost();
        if(host==null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))) throw new Exception("无法识别设备二维码，请手动输入设备号");
        host=host.toLowerCase(Locale.ROOT);
        String domain=zhuli?"whxinna.com":"ilife798.com";
        if(!(host.equals(domain)||host.endsWith("."+domain)||!zhuli&&host.equals("i.hnkzy.com"))) throw new Exception("这不是所选平台的设备二维码，请手动输入设备号");
        String query=u.getQuery();
        if(query!=null) for(String part:query.split("&")) {
            String[] p=part.split("=",2);
            if(p.length==2 && Arrays.asList("device_id","deviceId","did","id","d").contains(p[0])) return deviceId(URLDecoder.decode(p[1],"UTF-8"),zhuli);
        }
        String path=u.getPath().replaceAll("/+$","");
        return deviceId(path.substring(path.lastIndexOf('/')+1),zhuli);
    }
    public static int bathNetwork(JSONObject device) throws Exception {
        if(device.optInt("device_type",-1)!=0) throw new Exception("该设备不是洗浴水控机，请核对设备号");
        if(device.optBoolean("change_network",false)) throw new Exception("这台设备要求蓝牙切换，请在住理生活中使用");
        int primary=device.optInt("primary_consume_net_type",0);
        int selected=primary>0?primary:device.optInt("net_type",0);
        if(Arrays.asList(1,2,5,6,7).contains(selected)) return selected;
        if(primary>0) throw new Exception("设备要求的通信方式暂不支持，请使用住理生活");
        for(String n:device.optString("net_module","").split(",")) {
            try {int v=Integer.parseInt(n); if(Arrays.asList(1,2,5,6,7).contains(v)) return v;} catch(NumberFormatException ignored) {}
        }
        throw new Exception("此设备需要蓝牙或离线通信，首版仅支持联网洗浴设备");
    }
    public static boolean finished(JSONObject order) {
        return Arrays.asList("finished","refund","cancel","cancelled","canceled","closed").contains(order.optString("status"));
    }
    public static boolean available798(JSONObject data) {
        JSONObject d=data.optJSONObject("device");
        JSONObject g=d==null?null:d.optJSONObject("gene");
        return d!=null && d.optInt("status",-1)>0 && g!=null && g.optInt("status",-1)==99;
    }
    public static String unavailable798(JSONObject data) {
        JSONObject d=data.optJSONObject("device"),g=d==null?null:d.optJSONObject("gene");
        if(d!=null&&d.optInt("status",-1)==0)return "设备离线，请稍后再试";
        if(g!=null){int s=g.optInt("status",-1);if(s==98)return "设备已停用";if(s==2)return "设备异常，请联系运营方";if(Arrays.asList(1,10,20,30).contains(s))return "设备正在使用或启动中，请稍候";}
        return "设备状态待确认，请稍后查询";
    }
}
