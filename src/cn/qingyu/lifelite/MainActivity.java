package cn.qingyu.lifelite;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.widget.TextView;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
    private WebView web;
    private Api api;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String scanRequest;
    private final java.util.concurrent.atomic.AtomicBoolean loginInFlight=new java.util.concurrent.atomic.AtomicBoolean();
    private static final String ORIGIN="https://appassets.androidplatform.net/";
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xfff4f6f3);getWindow().setNavigationBarColor(0xfff4f6f3);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        try {api=new Api(new SecureStore(this),new Api.HttpTransport());} catch(Exception e){TextView t=new TextView(this);t.setPadding(32,80,32,32);t.setText("加密存储暂时无法读取。请先在官方应用确认没有未结束订单，再处理本应用的数据。请勿直接卸载以免丢失待确认记录。");setContentView(t);return;}
        web=new WebView(this);web.setBackgroundColor(0xfff4f6f3);setContentView(web);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSaveFormData(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                String url=request.getUrl().toString();
                if(!url.startsWith(ORIGIN))return new WebResourceResponse("text/plain","utf-8",new java.io.ByteArrayInputStream(new byte[0]));
                String name=url.substring(ORIGIN.length());
                if(!name.matches("[A-Za-z0-9_.-]+"))return null;
                try {String type=name.endsWith(".css")?"text/css":name.endsWith(".js")?"application/javascript":name.endsWith(".svg")?"image/svg+xml":"text/html";return new WebResourceResponse(type,"utf-8",getAssets().open(name));} catch(Exception e){return null;}
            }
        });
        web.setWebChromeClient(new WebChromeClient());web.addJavascriptInterface(new Bridge(),"NativeLife");web.loadUrl(ORIGIN+"index.html");
    }
    private void reply(String id,JSONObject response) {
        runOnUiThread(()->{if(web!=null&&!isDestroyed())web.evaluateJavascript("window.nativeResult("+JSONObject.quote(id)+","+response.toString()+")",null);});
    }
    private void error(String id,String message) {try {reply(id,Protocol.obj("ok",false,"error",message));}catch(Exception ignored){}}
    public final class Bridge {
        @JavascriptInterface public void call(String id,String action,String payload) {
            if(id==null||!id.matches("r[0-9]+")||payload==null||payload.length()>12000)return;
            if(action.equals("scan")) {runOnUiThread(()->{if(scanRequest!=null){error(id,"扫码正在进行");return;}scanRequest=id;if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.CAMERA},11);else launchScan();});return;}
            if(action.equals("official")){runOnUiThread(()->{try {String p=new JSONObject(payload).optString("provider");String pkg=p.equals(Api.BATH)?"com.whxinna.userplatform":"com.cloudora.android";Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i==null)throw new Exception("手机上没有找到对应应用");startActivity(i);reply(id,Protocol.obj("ok",true,"data",new JSONObject()));}catch(Exception e){error(id,e.getMessage());}});return;}
            if(action.equals("project")){runOnUiThread(()->{try {startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/qinghuan-yu/life-lite")));reply(id,Protocol.obj("ok",true,"data",new JSONObject()));}catch(Exception e){error(id,"无法打开项目链接，请检查浏览器");}});return;}
            if(action.equals("loginAlipay")&&!loginInFlight.compareAndSet(false,true)){error(id,"支付宝登录正在进行");return;}
            worker.execute(()->{try {
                JSONObject a=new JSONObject(payload);
                if(action.equals("loginAlipay")) {
                    try {
                        String provider=a.optString("provider");
                        if(!provider.equals(Api.WATER)&&!provider.equals(Api.BATH))throw new Exception("未知服务");
                        try{getPackageManager().getPackageInfo("com.eg.android.AlipayGphone",0);}catch(PackageManager.NameNotFoundException missing){throw new Exception("请先安装支付宝");}
                        String authInfo;
                        if(provider.equals(Api.BATH))authInfo=api.zhuliAlipayAuthInfo();
                        else try(java.io.InputStream input=getResources().openRawResource(R.raw.alipay_login_request);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                            byte[] buffer=new byte[1024];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);authInfo=new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
                        }
                        java.util.Map<String,String> result;
                        try{result=new com.alipay.sdk.app.AuthTask(MainActivity.this).authV2(authInfo,true);}catch(Exception sdkError){throw new Exception("无法完成支付宝授权，请返回重试");}
                        String code=Protocol.alipayAuthCode(result);
                        JSONObject data=provider.equals(Api.BATH)?api.loginZhuliAlipay(code,Build.VERSION.RELEASE,Build.MODEL):api.loginAlipay(code);
                        reply(id,Protocol.obj("ok",true,"data",data));return;
                    } finally {loginInFlight.set(false);}
                }
                if(action.equals("signAlipay")) {
                    if(!a.optString("provider").equals(Api.WATER))throw new Exception("当前服务不支持此签约方式");
                    try{getPackageManager().getPackageInfo("com.eg.android.AlipayGphone",0);}catch(PackageManager.NameNotFoundException missing){throw new Exception("请先安装支付宝");}
                    String sign=api.alipaySign();runOnUiThread(()->openAlipaySign(id,sign));return;
                }
                if(action.equals("payAlipay")) {
                    if(!a.optString("provider").equals(Api.WATER))throw new Exception("当前服务不支持此支付方式");
                    try{getPackageManager().getPackageInfo("com.eg.android.AlipayGphone",0);}catch(PackageManager.NameNotFoundException missing){throw new Exception("请先安装支付宝");}
                    JSONObject payment=api.beginAlipayPayment(a.getString("ticket"));
                    if(!payment.getString("order").isEmpty()) {
                        try{new com.alipay.sdk.app.PayTask(MainActivity.this).payV2(payment.getString("order"),true);}catch(Exception ignored){}
                    }
                    JSONObject result=api.completeAlipayPayment(payment.getString("id"));reply(id,Protocol.obj("ok",true,"data",result));return;
                }
                if(action.equals("login")){a.put("os","Android "+Build.VERSION.RELEASE);a.put("model",Build.MODEL);}
                JSONObject data=api.dispatch(action,a);reply(id,Protocol.obj("ok",true,"data",data));
            }catch(Exception e){if(action.equals("loginAlipay"))loginInFlight.set(false);String msg=e.getMessage();error(id,msg==null?"操作未完成，请刷新状态":msg);}});
        }
    }
    private void openAlipaySign(String id,String sign) {
        if(isDestroyed())return;
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        Handler handler=new Handler(Looper.getMainLooper());
        Runnable timeout=()->{if(completed.compareAndSet(false,true))error(id,"签约结果待确认，请在支付宝查看协议状态");};
        handler.postDelayed(timeout,120000);
        try {
            java.util.Map<String,String> params=new java.util.HashMap<>();params.put("sign_params",sign);
            new com.alipay.sdk.app.OpenAuthTask(this).execute("lifelitealipay",com.alipay.sdk.app.OpenAuthTask.BizType.Deduct,params,(code,memo,bundle)->{
                if(!completed.compareAndSet(false,true))return;handler.removeCallbacks(timeout);
                try{reply(id,Protocol.obj("ok",true,"data",Protocol.obj("message",code==9000?"已从支付宝返回，请核对协议状态":"签约未确认，请在支付宝查看")));}catch(Exception ignored){}
            },true);
        }catch(Exception e){handler.removeCallbacks(timeout);if(completed.compareAndSet(false,true))error(id,"无法打开支付宝签约，请在官方应用核对");}
    }
    private void launchScan(){startActivityForResult(new Intent(this,ScannerActivity.class),12);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==11){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)launchScan();else{error(scanRequest,"未开启相机权限，可以手动输入设备号");scanRequest=null;}}}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(request==12&&scanRequest!=null){try{if(result==RESULT_OK)reply(scanRequest,Protocol.obj("ok",true,"data",Protocol.obj("text",intent.getStringExtra("text"))));else error(scanRequest,"已取消扫码，可手动输入设备号");}catch(Exception e){error(scanRequest,"扫码结果无法读取");}scanRequest=null;}}
    @Override protected void onResume(){super.onResume();if(web!=null)web.evaluateJavascript("window.appResume&&window.appResume()",null);}
    @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("window.appBack&&window.appBack()",null);else super.onBackPressed();}
    @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("NativeLife");web.destroy();web=null;}worker.shutdown();super.onDestroy();}
}
