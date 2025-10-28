/*
 * Copyright (C) 2017 OrionStar Technology Project
 */

package com.ainirobot.robotos.fragment;

// Android 核心 & UI
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.fragment.app.Fragment;

// OrionStar Robot SDK
import com.ainirobot.coreservice.client.Definition;
import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.listener.ActionListener;
import com.ainirobot.robotos.R;

// Java I/O (用於 Ping)
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

// Java Net (用於 Modbus)
import java.net.InetAddress;

// j2mod Modbus Library
import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

// 併發
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainFragment extends BaseFragment {

    // ===== 常數設定（依你的設備手冊調整）=====
    private static final String TAG = "MainFragment";

    // 蜂鳴器 IP（請改成你的實際 IP）
    private static final String BUZZER_IP = "192.168.0.101";

    // 暫存器位址
    private static final int REG_ARMED  = 0;  // Alarm ARMED（保持暫存器）
    private static final int REG_FORCED = 1;  // Forced Alarm Index（保持暫存器）

    // Modbus Unit ID（從手冊確認，若不是 1 請修改）
    private static final int SLAVE_ID = 1;

    // ===== 成員欄位 =====
    private Handler mDelayHandler;

    // UI 元件
    private Button mLead_scene;
    private Button mSport_scene;
    private Button mSpeech_scene;
    private Button mVision_scene;
    private Button mCharge_scene;
    private Button mLocation_scene;
    private Button mNavigation_scene;
    private Button mAudio_scene;
    private Button mExit;

    private Button mBtnTestPingRestroom;
    private Button mBtnNavSequence;
    private Button mBtnTriggerBuzzer;
    private Button mBtnModbusTest;

    // 持久 Modbus 工人
    private PersistentModbus modbus;

    // ===== Fragment 生命週期 =====
    public static Fragment newInstance() {
        return new MainFragment();
    }

    @Override
    public View onCreateView(Context context) {
        View root = mInflater.inflate(R.layout.fragment_main_layout, null, false);
        bindViews(root);
        hideBackView();
        hideResultView();
        return root;
    }
//呼叫呼叫 modbus.start()，建立 TCP 連線並保持住。
    @Override
    public void onResume() {
        super.onResume();
        if (modbus == null) modbus = new PersistentModbus(BUZZER_IP);
        modbus.start(); // 建立持久連線（背景單工緒）
    }
//EXIT 時呼叫 modbus.stop()
    @Override
    public void onPause() {
        super.onPause();
        if (modbus != null) modbus.stop(); // 先 FORCED=0，再優雅關閉連線
    }

    // ===== 綁定 UI 與按鈕事件 =====
    private void bindViews(View root) {
        mDelayHandler = new Handler(Looper.getMainLooper());

        // 標準功能按鈕
        mLead_scene      = root.findViewById(R.id.lead_scene);
        mSport_scene     = root.findViewById(R.id.sport_scene);
        mSpeech_scene    = root.findViewById(R.id.speech_scene);
        mVision_scene    = root.findViewById(R.id.vision_scene);
        mCharge_scene    = root.findViewById(R.id.charge_scene);
        mLocation_scene  = root.findViewById(R.id.location_scene);
        mNavigation_scene= root.findViewById(R.id.navigation_scene);
        mAudio_scene     = root.findViewById(R.id.audio_scene);
        mExit            = root.findViewById(R.id.exit);

        // 自訂功能按鈕
        mBtnTestPingRestroom = root.findViewById(R.id.btn_test_ping_restroom);
        mBtnNavSequence      = root.findViewById(R.id.btn_nav_sequence);
        mBtnTriggerBuzzer    = root.findViewById(R.id.btn_trigger_buzzer);
        mBtnModbusTest       = root.findViewById(R.id.btn_modbus_test);

        // 切換 Fragment
        mLead_scene.setOnClickListener(v -> switchFragment(LeadFragment.newInstance()));
        mSport_scene.setOnClickListener(v -> switchFragment(SportFragment.newInstance()));
        mSpeech_scene.setOnClickListener(v -> switchFragment(SpeechFragment.newInstance()));
        mVision_scene.setOnClickListener(v -> switchFragment(VisionFragment.newInstance()));
        mCharge_scene.setOnClickListener(v -> switchFragment(ChargeFragment.newInstance()));
        mLocation_scene.setOnClickListener(v -> switchFragment(LocationFragment.newInstance()));
        mNavigation_scene.setOnClickListener(v -> switchFragment(NavigationFragment.newInstance()));
        mAudio_scene.setOnClickListener(v -> switchFragment(AudioFragment.newInstance()));

        root.findViewById(R.id.electric_door_control)
                .setOnClickListener(v -> switchFragment(ElectricDoorActionControlFragment.newInstance()));
        root.findViewById(R.id.body_follow)
                .setOnClickListener(v -> switchFragment(BodyFollowFragment.newInstance()));

        // EXIT：先關蜂鳴器/連線，再退出
        mExit.setOnClickListener(v -> {
            if (modbus != null) modbus.stop();
            if (getActivity() != null) {
                getActivity().onBackPressed();
                getActivity().finish();
            }
        });

        // Ping 測試：抵達洗手間後 Ping 指定 IP（示例）
        mBtnTestPingRestroom.setOnClickListener(v -> {
            String targetPoint = "洗手間";
            Log.d(TAG, "指令：開始導航 -> " + targetPoint + " (Ping 測試)");
            RobotApi.getInstance().startNavigation(
                    0, targetPoint, 1.5, 10 * 1000, mPingNavListener);
        });

        // 序列任務示例：洗手間 -> 待機點
        mBtnNavSequence.setOnClickListener(v -> {
            Log.i(TAG, "========= 序列任務開始！ ==========");
            Log.i(TAG, "第一步：前往「洗手間」...");
            RobotApi.getInstance().startNavigation(
                    0, "洗手間", 1.5, 10 * 1000, mRestroomListener);
        });

        // 抵達洗手間後鳴叫 3 秒
        mBtnTriggerBuzzer.setOnClickListener(v -> {
            Log.i(TAG, "========= 蜂鳴器觸發任務開始！ ==========");
            RobotApi.getInstance().startNavigation(
                    0, "洗手間", 1.5, 10 * 1000, mBuzzerNavListener);
        });

        // 直接測試蜂鳴器 3 秒
        mBtnModbusTest.setOnClickListener(v -> {
            if (modbus != null) modbus.beep3s();
        });
    }

    // ===== 導航回報器（保持你原來的行為）=====
    private final ActionListener mPingNavListener = new ActionListener() {
        @Override public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK && "true".equals(response)) {
                Log.i(TAG, "========= 導航成功! (Ping 測試) ==========");
                executePing("192.168.0.21");
            } else {
                Log.e(TAG, "========= 導航失敗! (Ping 測試) ==========");
                Log.e(TAG, "startNavigation result: " + status + " message: " + response);
            }
        }
        @Override public void onError(int errorCode, String errorString) throws RemoteException {
            Log.e(TAG, "========= 導航出錯! (Ping 測試) ==========");
            Log.e(TAG, "onError result: " + errorCode + " message: " + errorString);
        }
        @Override public void onStatusUpdate(int status, String data) {}
    };

    private final ActionListener mRestroomListener = new ActionListener() {
        @Override public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK && "true".equals(response)) {
                Log.i(TAG, "========= 序列步驟 1/2 成功! (抵達洗手間) ==========");
                executePing("192.168.0.21");
                Log.i(TAG, "準備執行下一步：前往「待機點」...");
                RobotApi.getInstance().startNavigation(
                        0, "待機點", 1.5, 10 * 1000, mMeetingRoomListener);
            } else {
                Log.e(TAG, "========= 序列步驟 1/2 失敗! (洗手間) ==========");
                Log.e(TAG, "startNavigation result: " + status + " message: " + response);
            }
        }
        @Override public void onError(int errorCode, String errorString) throws RemoteException {
            Log.e(TAG, "========= 序列步驟 1/2 出錯! (洗手間) ==========");
            Log.e(TAG, "onError result: " + errorCode + " message: " + errorString);
        }
        @Override public void onStatusUpdate(int status, String data) {}
    };

    private final ActionListener mMeetingRoomListener = new ActionListener() {
        @Override public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK && "true".equals(response)) {
                Log.i(TAG, "========= 序列步驟 2/2 成功! (抵達待機點) ==========");
                executePing("192.168.0.21");
            } else {
                Log.e(TAG, "========= 序列步驟 2/2 失敗! (待機點) ==========");
                Log.e(TAG, "startNavigation result: " + status + " message: " + response);
            }
        }
        @Override public void onError(int errorCode, String errorString) throws RemoteException {
            Log.e(TAG, "========= 序列步驟 2/2 出錯! (待機點) ==========");
            Log.e(TAG, "onError result: " + errorCode + " message: " + errorString);
        }
        @Override public void onStatusUpdate(int status, String data) {}
    };

    private final ActionListener mBuzzerNavListener = new ActionListener() {
        @Override public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK && "true".equals(response)) {
                Log.i(TAG, "========= 抵達洗手間，觸發蜂鳴器 3 秒 ==========");
                if (modbus != null) modbus.beep3s();
            } else {
                Log.e(TAG, "========= 導航至洗手間失敗，任務取消 ==========");
                Log.e(TAG, "startNavigation result: " + status + " message: " + response);
            }
        }
        @Override public void onError(int errorCode, String errorString) throws RemoteException {
            Log.e(TAG, "========= 導航至洗手間出錯! ==========");
            Log.e(TAG, "onError result: " + errorCode + " message: " + errorString);
        }
        @Override public void onStatusUpdate(int status, String data) {}
    };

    // ===== Ping 工具（保留你的需求）=====
    private void executePing(final String ipAddress) {
        new Thread(() -> {
            String cmd = "/system/bin/ping -c 1 " + ipAddress;
            Log.d(TAG, "Ping: " + cmd);
            try {
                Process p = Runtime.getRuntime().exec(cmd);
                StringBuilder out = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.append(line).append("\n");
                    }
                }
                int code = p.waitFor();
                if (code == 0) {
                    Log.i(TAG, "PING 成功: " + ipAddress);
                } else {
                    Log.e(TAG, "PING 失敗: " + ipAddress + " (exit " + code + ")");
                }
                Log.d(TAG, "Ping output:\n" + out);
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "Ping 例外: " + e.getMessage(), e);
            }
        }).start();
    }

    // ====== 單執行緒＋持久連線的 Modbus 工人 ======
    private final class PersistentModbus {
        private final String ip;
        //建一條專屬 Modbus 工作緒。
        //核心中的核心。這是一個 SingleThreadExecutor，它保證了所有 Modbus 操作都在同一個背景執行緒上「排隊執行」。
        private final ExecutorService exec = Executors.newSingleThreadExecutor();
        //: 一個 volatile 旗標，用於控制這個背景執行緒是否應該繼續接受新任務。
        private volatile boolean running = false;
        private final Object lock = new Object();
        //一個同步鎖，用來保護對 conn (連線物件) 和 running 狀態的存取，避免多執行緒衝突。
        private TCPMasterConnection conn = null;
        //: j2mod 函式庫中的 TCPMasterConnection 物件，代表與蜂鳴器（Slave 設備）的實際 TCP 連線。

        PersistentModbus(String ip) {
            this.ip = ip;
        }

        void start() {
            synchronized (lock) {
                if (running) return;
                running = true;
            }
            // 預先連線一次；後續每個任務也會自檢
            exec.execute(this::ensureConnected);
        }

        void stop() {
            // 停止前確保關蜂鳴器，再關連線,「關閉蜂鳴器」
            enqueue(() -> safeWrite(REG_FORCED, 0));
            //接著提交一個「關閉連線」的任務。
            enqueue(this::closeQuietly);
            synchronized (lock) {
                running = false;
            }
            exec.shutdown(); // 停止接新任務（既有任務會跑完）
        }

        /** 一鍵鳴叫 3 秒 */
        void beep3s() {
            enqueue(() -> safeWrite(REG_ARMED, 1));   // ARMED=1
            enqueueSleep(200);                         // 200ms
            enqueue(() -> safeWrite(REG_FORCED, 1));  // FORCED=1
            enqueueSleep(5000);                        // 3s
            enqueue(() -> safeWrite(REG_FORCED, 1));  // FORCED=0
            enqueueSleep(5000);                        // 3s
            enqueue(() -> safeWrite(REG_FORCED, 0));  // FORCED=0
        }

        // ===== 任務排程 =====
        private void enqueue(Runnable r) {
            synchronized (lock) {
                //先檢查 running 旗標。
                if (!running) {
                    Log.w(TAG, "Modbus worker not running; ignore task");
                    return;
                }
            }
            exec.execute(() -> {
                try {
                    ensureConnected();
                    //先檢查連線
                    r.run();
                } catch (Exception e) {
                    Log.e(TAG, "Modbus task failed: " + e.getMessage(), e);
                    closeQuietly(); // 連線可能壞了，關閉以便下次自動重連
                }
            });
        }

        private void enqueueSleep(long ms) {
            enqueue(() -> {
                try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
            });
        }

        // ===== 連線與寫入 =====
        private void ensureConnected() {
            synchronized (lock) {
                if (conn != null && conn.isConnected()) return;
                try {
                    Log.i(TAG, "Modbus: connecting to " + ip + " ...");
                    InetAddress addr = InetAddress.getByName(ip);
                    conn = new TCPMasterConnection(addr);
                    conn.setPort(Modbus.DEFAULT_PORT); // 502
                    conn.setTimeout(6000);             // 逾時可調
                    conn.connect();
                    Log.i(TAG, "Modbus: connected.");
                } catch (Exception e) {
                    closeQuietly();
                    throw new RuntimeException("connect failed: " + e.getMessage(), e);
                }
            }
        }

        private void safeWrite(int address, int value) {
            synchronized (lock) {
                if (conn == null || !conn.isConnected()) {
                    throw new RuntimeException("connection not ready");
                }
                try {
                    WriteSingleRegisterRequest req =
                            new WriteSingleRegisterRequest(address, new SimpleRegister(value));
                    req.setUnitID(SLAVE_ID);

                    ModbusTCPTransaction tx = new ModbusTCPTransaction(conn);
                    tx.setRequest(req);
                    tx.execute();

                    Log.i(TAG, "Modbus write OK (addr " + address + ", val " + value + ")");
                } catch (Exception e) {
                    throw new RuntimeException("write failed (addr " + address + ", val " + value + "): "
                            + e.getMessage(), e);
                } finally {
                    // 小間隔，避免把從站塞爆
                    try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                }
            }
        }

        private void closeQuietly() {
            synchronized (lock) {
                try {
                    if (conn != null) {
                        conn.close();
                        Log.i(TAG, "Modbus: connection closed.");
                    }
                } catch (Exception ignore) {
                } finally {
                    conn = null;
                }
            }
        }
    }
}
