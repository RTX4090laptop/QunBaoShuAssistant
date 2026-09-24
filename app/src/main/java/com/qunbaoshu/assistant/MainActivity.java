package com.qunbaoshu.assistant;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etName, etFormId, etToken;
    private Button btnClockIn, btnPasteToken;
    private TextView tvLog, tvStatus, tvResultTitle, tvResultSeq, tvResultFid;
    private LinearLayout cardResult;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final String APP_ID = "wx6b67694378f555b6";
    private static final String BASE_URL = "https://form.qun100.com";

    // 规定目标坐标 (汕头大学新医学楼)
    private static final double SETUP_LNG = 116.63358306884766;
    private static final double SETUP_LAT = 23.409648895263672;
    private static final String SPECIFIED_ADDR = "汕头大学新医学楼";
    private static final String DETAIL_ADDR = "广东省汕头市金平区大学路243号";

    // 最新有效的默认凭据
    private static final String LATEST_VALID_TOKEN = "KezNfDSR4QtHN3asV1qN7pKxD45KYWmHeO23LldAMfX89yY4ojaCKDKioAAwfyXna3k0nQ";

    // 兜底图片 URL (群报数已有的公开打卡图)
    private static final String FALLBACK_IMAGE_URL = "https://oss2.qun100.com/F2p65x29/V2/formData2/qk9K2UCBZGVe37f1d39.jpg";

    // 纯黑 JPEG 图片 Base64
    private static final String BLACK_JPG_B64 = 
        "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////" +
        "wgALCAAKAAoBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etName = findViewById(R.id.etName);
        etFormId = findViewById(R.id.etFormId);
        etToken = findViewById(R.id.etToken);
        btnClockIn = findViewById(R.id.btnClockIn);
        btnPasteToken = findViewById(R.id.btnPasteToken);
        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        cardResult = findViewById(R.id.cardResult);
        tvResultTitle = findViewById(R.id.tvResultTitle);
        tvResultSeq = findViewById(R.id.tvResultSeq);
        tvResultFid = findViewById(R.id.tvResultFid);

        etToken.setText(LATEST_VALID_TOKEN);

        btnPasteToken.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null && text.length() > 0) {
                    etToken.setText(text.toString().trim());
                    Toast.makeText(this, "已粘贴剪贴板 Token", Toast.LENGTH_SHORT).show();
                    log("已粘贴新 Token: " + text.subSequence(0, Math.min(10, text.length())) + "...");
                }
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            }
        });

        btnClockIn.setOnClickListener(v -> startClockIn());
    }

    private void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        mainHandler.post(() -> {
            tvLog.append("[" + time + "] " + msg + "\n");
        });
    }

    private void startClockIn() {
        btnClockIn.setEnabled(false);
        btnClockIn.setText("⏳ 正在打卡中...");
        cardResult.setVisibility(View.GONE);

        String formId = etFormId.getText().toString().trim();
        String token = etToken.getText().toString().trim();
        String name = etName.getText().toString().trim();

        if (token.isEmpty()) {
            Toast.makeText(this, "Token 不能为空！", Toast.LENGTH_SHORT).show();
            btnClockIn.setEnabled(true);
            btnClockIn.setText("🚀 立即一键打卡");
            return;
        }

        log("-----------------------------------------");
        log("开始打卡流程: 填报人 [" + name + "]");

        executor.execute(() -> {
            try {
                // 1. 动态获取表单最新版本 (解决“被发布人修改”报错)
                log("[1/4] 同步活动最新配置与版本号...");
                int latestVersion = fetchLatestFormVersion(formId, token);
                log("[+] 获取到��新表单版本: v" + latestVersion);

                // 2. 获取并直传纯黑图（有容错保护）
                log("[2/4] 上传纯黑图片至阿里云 OSS...");
                String imageUrl = null;
                try {
                    imageUrl = uploadBlackImage(formId, token);
                    log("[+] OSS 纯黑图上传成功");
                } catch (Exception e) {
                    log("[!] 自动上传异常(" + e.getMessage() + ")，启用备用图片链路...");
                    imageUrl = FALLBACK_IMAGE_URL;
                }

                // 3. 检查会话状态与历史记录 FID
                log("[3/4] 查询会话历史与打卡槽位...");
                String lastFid = fetchLastFid(formId, token);

                // 4. 构造 GeoJSON 与 Catalogs 报文
                log("[4/4] 组装范围坐标 (偏差 0 米)，提交打卡...");
                JSONObject submitRes = submitForm(formId, token, name, imageUrl, lastFid, latestVersion);

                int code = submitRes.optInt("code", -1);
                if (code == 0) {
                    JSONObject data = submitRes.optJSONObject("data");
                    String fid = data != null ? data.optString("fid", lastFid) : lastFid;
                    log("🎉 打卡入库成功！HTTP 200 / code: 0");
                    log("记录 FID: " + fid);

                    mainHandler.post(() -> {
                        cardResult.setVisibility(View.VISIBLE);
                        tvResultTitle.setText("✅ 打卡成功 (入库完成)");
                        tvResultSeq.setText("提交成功！已完成签到");
                        tvResultFid.setText("FID: " + fid);
                        Toast.makeText(MainActivity.this, "🎉 打卡成功！", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    String msg = submitRes.optString("message", "未知错误");
                    log("❌ 提交被拒: " + msg);
                    if (code == 401 || msg.contains("401") || msg.contains("登录") || msg.contains("token")) {
                        log("⚠️ 提示: 当前 Token 已过期，请在微信重新打开一次小程序获取新 Token！");
                    }
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "打卡失败: " + msg, Toast.LENGTH_LONG).show());
                }

            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                log("❌ 流程异常: " + errMsg);
                mainHandler.post(() -> Toast.makeText(MainActivity.this, errMsg, Toast.LENGTH_LONG).show());
            } finally {
                mainHandler.post(() -> {
                    btnClockIn.setEnabled(true);
                    btnClockIn.setText("🚀 立即一键打卡");
                });
            }
        });
    }

    private int fetchLatestFormVersion(String formId, String token) {
        try {
            URL url = new URL(BASE_URL + "/v3/form/" + formId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("Client-App-Id", APP_ID);
            conn.setRequestProperty("Client-Form-Id", formId);
            conn.setRequestProperty("ver", "3.70.2");
            conn.setConnectTimeout(6000);

            String jsonStr = readResponse(conn);
            JSONObject root = new JSONObject(jsonStr);
            JSONObject data = root.optJSONObject("data");
            if (data != null && data.has("version")) {
                return data.getInt("version");
            }
        } catch (Exception e) {
            log("[!] 获取版本异常: " + e.getMessage());
        }
        return 5; // 默认最新版本
    }

    private String uploadBlackImage(String formId, String token) throws Exception {
        URL url = new URL(BASE_URL + "/v2/image/pre_upload?fileNum=1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", token);
        conn.setRequestProperty("Client-App-Id", APP_ID);
        conn.setRequestProperty("Client-Form-Id", formId);
        conn.setRequestProperty("ver", "3.70.2");
        conn.setConnectTimeout(8000);

        int code = conn.getResponseCode();
        String jsonStr = readResponse(conn);

        if (code == 401) {
            throw new RuntimeException("登录凭据 (Token) 已过期失效，请填入最新 Token！");
        }

        JSONObject root = new JSONObject(jsonStr);
        if (!root.has("data") || root.isNull("data")) {
            String msg = root.optString("message", "预上传接口未返回 data");
            throw new RuntimeException("图片接口提示: " + msg);
        }

        JSONObject data = root.getJSONObject("data");
        JSONObject ali = data.getJSONObject("aliSign");

        String host = ali.getString("host");
        String prefix = ali.getString("prefix");
        String filename = data.getJSONArray("filenames").getString(0) + ".jpg";
        String ossKey = prefix + filename;

        byte[] imgBytes = Base64.decode(BLACK_JPG_B64, Base64.DEFAULT);
        String boundary = "----AndroidFormBoundary" + UUID.randomUUID().toString().replace("-", "");

        URL uploadUrl = new URL(host);
        HttpURLConnection upConn = (HttpURLConnection) uploadUrl.openConnection();
        upConn.setRequestMethod("POST");
        upConn.setDoOutput(true);
        upConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        upConn.setConnectTimeout(10000);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeField(baos, boundary, "key", ossKey);
        writeField(baos, boundary, "policy", ali.getString("policy"));
        writeField(baos, boundary, "OSSAccessKeyId", ali.getString("accessid"));
        writeField(baos, boundary, "signature", ali.getString("signature"));
        writeField(baos, boundary, "success_action_status", "200");

        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(imgBytes);
        baos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        OutputStream os = upConn.getOutputStream();
        os.write(baos.toByteArray());
        os.flush();
        os.close();

        int respCode = upConn.getResponseCode();
        if (respCode == 200 || respCode == 204) {
            return ali.getString("cdn") + "/" + ossKey;
        } else {
            throw new RuntimeException("OSS Upload HTTP " + respCode);
        }
    }

    private String fetchLastFid(String formId, String token) {
        try {
            URL url = new URL(BASE_URL + "/v1/" + formId + "/form_data/last");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("Client-App-Id", APP_ID);
            conn.setRequestProperty("Client-Form-Id", formId);
            conn.setRequestProperty("ver", "3.70.2");
            conn.setConnectTimeout(6000);

            String jsonStr = readResponse(conn);
            JSONObject root = new JSONObject(jsonStr);
            JSONObject dto = root.optJSONObject("data");
            if (dto != null && dto.has("formDataDto")) {
                return dto.getJSONObject("formDataDto").optString("fid", "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    private JSONObject submitForm(String formId, String token, String name, String imageUrl, String lastFid, int formVersion) throws Exception {
        boolean isPut = lastFid != null && !lastFid.isEmpty();
        String method = isPut ? "PUT" : "POST";
        String path = isPut ? "/v2/" + formId + "/form_data" : "/v1/" + formId + "/form_data";

        JSONArray catalogs = new JSONArray();

        // 1. 姓名
        JSONObject q1 = new JSONObject();
        q1.put("cid", "1851089034359558144");
        q1.put("type", "WORD");
        q1.put("value", name);
        q1.put("fileAuditList", new JSONArray());
        catalogs.put(q1);

        // 2. 图片
        JSONObject q2 = new JSONObject();
        q2.put("cid", "1851089034359558146");
        q2.put("type", "IMAGE");
        JSONArray imgVals = new JSONArray();
        imgVals.put(imageUrl);
        q2.put("value", imgVals);
        JSONArray imgDetails = new JSONArray();
        JSONObject det = new JSONObject();
        det.put("url", imageUrl);
        det.put("fileName", "black.jpg");
        imgDetails.put(det);
        q2.put("imageDetails", imgDetails);
        JSONArray audits = new JSONArray();
        JSONObject aud = new JSONObject();
        aud.put("fileUrl", imageUrl);
        aud.put("auditStatus", 10);
        audits.put(aud);
        q2.put("fileAuditList", audits);
        catalogs.put(q2);

        // 3. 定位 (GeoJSON Point)
        JSONObject q3 = new JSONObject();
        q3.put("cid", "1851089034359558148");
        q3.put("type", "LOCATION");
        JSONObject locVal = new JSONObject();
        locVal.put("address", DETAIL_ADDR);
        locVal.put("title", SPECIFIED_ADDR);
        JSONObject geo = new JSONObject();
        geo.put("type", "Point");
        JSONArray coords = new JSONArray();
        coords.put(SETUP_LNG);
        coords.put(SETUP_LAT);
        geo.put("coordinates", coords);
        locVal.put("location", geo);
        locVal.put("specifiedAddress", SPECIFIED_ADDR);
        locVal.put("setupLongitude", SETUP_LNG);
        locVal.put("setupLatitude", SETUP_LAT);
        locVal.put("setupAddress", "");
        q3.put("value", locVal);
        q3.put("fileAuditList", new JSONArray());
        catalogs.put(q3);

        JSONObject payload = new JSONObject();
        payload.put("fid", lastFid);
        payload.put("subscribe", new JSONObject());
        payload.put("catalogs", catalogs);
        JSONArray showQ = new JSONArray();
        showQ.put("1851089034359558144");
        showQ.put("1851089034359558146");
        showQ.put("1851089034359558148");
        payload.put("showQuestions", showQ);
        payload.put("examUsedTime", JSONObject.NULL);
        payload.put("formVersion", formVersion);

        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", token);
        conn.setRequestProperty("Client-App-Id", APP_ID);
        conn.setRequestProperty("Client-Form-Id", formId);
        conn.setRequestProperty("ver", "3.70.2");
        conn.setConnectTimeout(10000);

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        os.close();

        return new JSONObject(readResponse(conn));
    }

    private void writeField(ByteArrayOutputStream baos, String boundary, String name, String value) throws Exception {
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        int respCode = conn.getResponseCode();
        InputStream is = respCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) {
            return "{\"code\":" + respCode + ",\"message\":\"HTTP " + respCode + "\"}";
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        return baos.toString("UTF-8");
    }
}
