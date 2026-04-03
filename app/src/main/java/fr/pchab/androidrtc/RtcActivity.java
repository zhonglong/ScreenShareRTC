
package fr.pchab.androidrtc;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.VideoCapturer;

import static android.content.ContentValues.TAG;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class RtcActivity extends Activity implements WebRtcClient.RtcListener {
    private WebRtcClient mWebRtcClient;
    private static final int CAPTURE_PERMISSION_REQUEST_CODE = 1;
    //    private EglBase rootEglBase;
    private static Intent mMediaProjectionPermissionResultData;
    private static int mMediaProjectionPermissionResultCode;

    public static String STREAM_NAME_PREFIX = "android_device_stream";
    // List of mandatory application permissions.／
    private static final String[] MANDATORY_PERMISSIONS = {"android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET"};

    //    private SurfaceViewRenderer pipRenderer;
//    private SurfaceViewRenderer fullscreenRenderer;
    public static int sDeviceWidth;
    public static int sDeviceHeight;
    public static final int SCREEN_RESOLUTION_SCALE = 2;

    private Context mContext;
    private TextView mUserId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_rtc);
        mUserId = findViewById(R.id.user_id);
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        sDeviceWidth = metrics.widthPixels;
        sDeviceHeight = metrics.heightPixels;

//        pipRenderer = (SurfaceViewRenderer) findViewById(R.id.pip_video_view);
//        fullscreenRenderer = (SurfaceViewRenderer) findViewById(R.id.fullscreen_video_view);

//        EglBase rootEglBase = EglBase.create();
//        pipRenderer.init(rootEglBase.getEglBaseContext(), null);
//        pipRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
//        fullscreenRenderer.init(rootEglBase.getEglBaseContext(), null);
//        fullscreenRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);

//        pipRenderer.setZOrderMediaOverlay(true);
//        pipRenderer.setEnableHardwareScaler(true /* enabled */);
//        fullscreenRenderer.setEnableHardwareScaler(true /* enabled */);
        // Check for mandatory permissions.
        for (String permission : MANDATORY_PERMISSIONS) {
            if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                setResult(RESULT_CANCELED);
                finish();
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startScreenCapture();
        } else {
            init();
        }
    }

    @TargetApi(21)
    private void startScreenCapture() {
        MediaProjectionManager mediaProjectionManager =
                (MediaProjectionManager) getApplication().getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(), CAPTURE_PERMISSION_REQUEST_CODE);
    }

    @TargetApi(21)
    private VideoCapturer createScreenCapturer() {
        if (mMediaProjectionPermissionResultCode != Activity.RESULT_OK) {
            report("User didn't give permission to capture the screen.");
            return null;
        }
        return new ScreenCapturerAndroid(
                mMediaProjectionPermissionResultData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                report("User revoked permission to capture the screen.");
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != CAPTURE_PERMISSION_REQUEST_CODE)
            return;
        mMediaProjectionPermissionResultCode = resultCode;
        mMediaProjectionPermissionResultData = data;
        init();
    }

    private void init() {
        PeerConnectionClient.PeerConnectionParameters peerConnectionParameters =
                new PeerConnectionClient.PeerConnectionParameters(true, false,
                        true, sDeviceWidth / SCREEN_RESOLUTION_SCALE, sDeviceHeight / SCREEN_RESOLUTION_SCALE, 0,
                        0, "H264",
                        true,
                        true,
                        0,
                        "OPUS", false, false, false, false, false, false, false, false, null);
//        mWebRtcClient = new WebRtcClient(getApplicationContext(), this, pipRenderer, fullscreenRenderer, createScreenCapturer(), peerConnectionParameters);
        mWebRtcClient = new WebRtcClient(getApplicationContext(), this, createScreenCapturer(), peerConnectionParameters);

        String host = "wss://" + mContext.getString(R.string.host) + ":" + mContext.getString(R.string.port) + "/";
        mSocket = new OkHttpClient().newWebSocket(new Request.Builder().url(host).build(), wsListener);
        Log.d(TAG, "socket start connect");
        user_id = PreferenceManager.getDefaultSharedPreferences(mContext).getString("user_id", "android");
    }

    public void report(String info) {
        Log.e(TAG, info);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        if (mWebRtcClient != null) {
            mWebRtcClient.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onReady(String callId) {
        mWebRtcClient.start(STREAM_NAME_PREFIX);
    }

    @Override
    public void onCall(final String applicant) {
        try {
            JSONObject message = new JSONObject(applicant);
            message.put("transmission_id", transmission_id);
            message.put("user_id", user_id);
            mSocket.send(message.toString());
        } catch (JSONException e) {
            Log.e(TAG, "JSONException -> ", e);
        } catch (Exception e) {
            Log.e(TAG, "Exception -> ", e);
        }
    }

    @Override
    public void onHandup() {

    }

    @Override
    public void onStatusChanged(final String newStatus) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), newStatus, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private WebSocket mSocket;

    private String user_id, remote_user_id;
    private String transmission_id;
    private WebSocketListener wsListener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.i(TAG, "socket state connect");
            start(user_id);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(TAG, "socket received " + text);
            try {
                JSONObject payload = new JSONObject(text);
                switch (payload.getString("type")) {
                    case "login":
                        String id = payload.getString("user_id");
                        if (user_id.startsWith(id)) {
                            if (mWebRtcClient != null) {
                                mWebRtcClient.start(STREAM_NAME_PREFIX);
                            }
                            PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString("user_id", user_id).apply();
                            runOnUiThread(() -> {mUserId.setText(user_id);});
                        } else {
                            user_id = id;
                            start(user_id);
                        }
                        break;
                    case "user_join_transmission":
                        remote_user_id = payload.getString("user_id");
                        transmission_id = payload.getString("transmission_id");
                    case "answer":
                    case "new_candidate_mid":
                        if (mWebRtcClient != null) {
                            mWebRtcClient.onMessage(remote_user_id, payload);
                        }
                        break;
                    case "user_leave_transmission":
                        if (TextUtils.equals(payload.getString("transmission_id"), transmission_id)) {
                            finish();
                        }
                        break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "JSONException -> ", e);
            } catch (Exception e) {
                Log.e(TAG, "Exception -> ", e);
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.i(TAG, "socket state disconnect");
            finish();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "socket state error", t);
            finish();
        }
    };

    public void start(String name) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "login");
            message.put("user_id", name);
            mSocket.send(message.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
