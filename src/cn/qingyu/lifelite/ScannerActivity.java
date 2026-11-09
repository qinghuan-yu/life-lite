package cn.qingyu.lifelite;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;

/** 相机帧只在手机内解码，不上传、不存储。 */
@SuppressWarnings("deprecation")
public final class ScannerActivity extends Activity implements SurfaceHolder.Callback {
    private Camera camera;
    private SurfaceView preview;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor();
    private volatile boolean reading=false,done=false;
    private long lastFrame=0;
    @Override public void onCreate(Bundle bundle){super.onCreate(bundle);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);FrameLayout root=new FrameLayout(this);preview=new SurfaceView(this);root.addView(preview);TextView tip=new TextView(this);tip.setText("将设备二维码放入画面\n识别后仅填写设备，不会自动开始计费");tip.setTextColor(0xffffffff);tip.setTextSize(17);tip.setGravity(Gravity.CENTER);tip.setPadding(20,30,20,30);tip.setBackgroundColor(0xbb162f29);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);root.addView(tip,lp);setContentView(root);preview.getHolder().addCallback(this);}
    @Override public void surfaceCreated(SurfaceHolder holder){try{
        camera=Camera.open();Camera.Parameters p=camera.getParameters();List<Camera.Size> sizes=p.getSupportedPreviewSizes();Camera.Size best=sizes.get(0);for(Camera.Size s:sizes)if(s.width<=1280&&s.width>=640){best=s;break;}p.setPreviewSize(best.width,best.height);if(p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);camera.setParameters(p);camera.setDisplayOrientation(90);camera.setPreviewDisplay(holder);camera.setPreviewCallback((bytes,c)->{
            if(done||reading||System.currentTimeMillis()-lastFrame<250)return;reading=true;lastFrame=System.currentTimeMillis();Camera.Size size=c.getParameters().getPreviewSize();byte[] frame=bytes.clone();
            decoder.execute(()->{try{MultiFormatReader reader=new MultiFormatReader();Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));hints.put(DecodeHintType.TRY_HARDER,true);Result result=reader.decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(frame,size.width,size.height,0,0,size.width,size.height,false))),hints);done=true;runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("text",result.getText()));finish();});}catch(Exception ignored){}finally{reading=false;}});
        });camera.startPreview();
    }catch(Exception e){Toast.makeText(this,"相机暂不可用，请手动输入设备号",Toast.LENGTH_LONG).show();finish();}}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){release();}
    private void release(){if(camera!=null){camera.setPreviewCallback(null);camera.stopPreview();camera.release();camera=null;}}
    @Override protected void onPause(){super.onPause();done=true;release();if(!isFinishing())finish();}
    @Override protected void onDestroy(){decoder.shutdown();super.onDestroy();}
}
