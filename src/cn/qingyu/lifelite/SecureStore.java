package cn.qingyu.lifelite;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** 凭据和未结算记录一起加密，写入完成后才允许发送开始请求。 */
public final class SecureStore implements Api.Store {
    private final SharedPreferences prefs;
    private static final String ALIAS="lifelite.v1";
    public SecureStore(Context context){prefs=context.getSharedPreferences("vault",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)) {
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (SecretKey)ks.getKey(ALIAS,null);
    }
    public JSONObject read() throws Exception {
        String saved=prefs.getString("state",null);if(saved==null)return new JSONObject();
        JSONObject envelope=new JSONObject(saved);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(envelope.getString("iv"))));
        return new JSONObject(new String(c.doFinal(Base64.getDecoder().decode(envelope.getString("data"))),StandardCharsets.UTF_8));
    }
    public void write(JSONObject state) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        JSONObject out=Protocol.obj("iv",Base64.getEncoder().encodeToString(c.getIV()),"data",Base64.getEncoder().encodeToString(c.doFinal(state.toString().getBytes(StandardCharsets.UTF_8))));
        if(!prefs.edit().putString("state",out.toString()).commit())throw new Exception("本地保存失败，操作没有继续，请检查存储空间");
    }
}
