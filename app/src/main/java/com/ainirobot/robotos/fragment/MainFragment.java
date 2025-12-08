/*
 * Copyright (C) 2017 OrionStar Technology Project
 */

package com.ainirobot.robotos.fragment;

// Android 核心 & UI
import android.app.AlertDialog;          // 彈窗
import android.content.DialogInterface;  // 彈窗介面 (包含按鈕監聽器)
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

// Java Net
import java.net.InetAddress;

// Modbus Library
import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

// 併發處理
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainFragment extends BaseFragment {

    private static final String TAG = "MainFragment";

    // ===== Modbus 設定 =====
    private static final String BUZZER_IP_1 = "192.168.162.101";
    private static final String BUZZER_IP_2 = "192.168.162.102";
    private static final int SLAVE_ID = 1;

    // ===== UI 元件 =====
    private Button mBtnStandby;
    private Button mBtnPoint1;
    private Button mBtnPoint2;
    private Button mBtnDoorControl;
    private Button mBtnCharging;
    private Button mExit;

    // Modbus 工人
    private PersistentModbus modbus1;
    private PersistentModbus modbus2;

    // 用來處理 30秒循環 的計時器
    private Handler mHandler = new Handler(Looper.getMainLooper());

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

    @Override
    public void onResume() {
        super.onResume();
        if (modbus1 == null) modbus1 = new PersistentModbus(BUZZER_IP_1);
        modbus1.start();
        if (modbus2 == null) modbus2 = new PersistentModbus(BUZZER_IP_2);
        modbus2.start();
    }

    @Override
    public void onPause() {
        super.onPause();
        // 離開畫面時，務必停止任何正在跑的計時器，以免背景一直響
        mHandler.removeCallbacksAndMessages(null);
        if (modbus1 != null) modbus1.stop();
        if (modbus2 != null) modbus2.stop();
    }

    private void bindViews(View root) {
        mBtnStandby = root.findViewById(R.id.btn_standby_point);
        mBtnPoint1 = root.findViewById(R.id.btn_injection_point_1);
        mBtnPoint2 = root.findViewById(R.id.btn_injection_point_2);
        mBtnDoorControl = root.findViewById(R.id.electric_door_control);
        mBtnCharging = root.findViewById(R.id.btn_Charging_pile);
        mExit = root.findViewById(R.id.exit);

        mBtnStandby.setOnClickListener(v -> {
            Log.i(TAG, "指令：前往待機點");
            RobotApi.getInstance().startNavigation(0, "待機點", 1.5, 10 * 1000, mSimpleNavListener);
        });

        mBtnPoint1.setOnClickListener(v -> {
            Log.i(TAG, "指令：前往1號注射點");
            RobotApi.getInstance().startNavigation(0, "待機點", 1.5, 10 * 1000, mBuzzer1NavListener);
        });

        mBtnPoint2.setOnClickListener(v -> {
            Log.i(TAG, "指令：前往2號注射點");
            RobotApi.getInstance().startNavigation(0, "充電樁", 1.5, 10 * 1000, mBuzzer2NavListener);
        });

        mBtnDoorControl.setOnClickListener(v -> switchFragment(ElectricDoorActionControlFragment.newInstance()));

        mBtnCharging.setOnClickListener(v -> {
            Log.i(TAG, "指令：開始自動回充");
            int i = RobotApi.getInstance().goCharging(0);
        });

        mExit.setOnClickListener(v -> {
            mHandler.removeCallbacksAndMessages(null); // 清除計時器
            if (modbus1 != null) modbus1.stop();
            if (modbus2 != null) modbus2.stop();
            if (getActivity() != null) {
                getActivity().onBackPressed();
                getActivity().finish();
            }
        });
    }

    // ==========================================
    // 重點功能：抵達確認視窗 (包含 30秒循環響鈴邏輯)
    // ==========================================
    private void showArrivedConfirmDialog(String pointName, PersistentModbus targetModbus) {
        // 確保在主執行緒執行 UI 更新
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            // 1. 定義一個「定期任務」：響鈴 -> 等30秒 -> 再響鈴
            Runnable beepLoopTask = new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "使用者尚未確認，觸發蜂鳴器響 3 秒提醒...");
                    if (targetModbus != null) {
                        targetModbus.beep3s();
                    }
                    // 設定 30秒 (30000ms) 後再執行一次這個 run()
                    mHandler.postDelayed(this, 30 * 1000);
                }
            };

            // 2. 建立彈跳視窗
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("已抵達：" + pointName);
            builder.setMessage("蜂鳴器已啟動。\n請領取物品，確認領取完畢後請點擊下方按鈕。");
            builder.setCancelable(false); // 禁止點擊空白處關閉，強迫按按鈕

            // 3. 設定按鈕動作
            builder.setPositiveButton("我已收到物品", (dialog, which) -> {
                Log.i(TAG, "使用者已確認收到物品，停止計時器。");
                // 關鍵：移除計時器，這樣就不會再響了
                mHandler.removeCallbacks(beepLoopTask);
            });

            // 4. 顯示視窗並「立即」開始第一次響鈴
            AlertDialog dialog = builder.create();
            dialog.show();

            // 馬上執行第一次響鈴
            mHandler.post(beepLoopTask);
        });
    }

    // ===== 導航監聽器 =====

    private final ActionListener mSimpleNavListener = new ActionListener() {
        @Override
        public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK) Log.i(TAG, "導航成功");
        }

        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {
        }

        @Override
        public void onStatusUpdate(int status, String data) {
        }
    };

    // 1號點監聽器
    private final ActionListener mBuzzer1NavListener = new ActionListener() {
        @Override
        public void onResult(int status, String response) throws RemoteException {
            // 成功抵達的 Log
            Log.i(TAG, "導航回傳 Result: " + status + ", Response: " + response);

            if (status == Definition.RESULT_OK && "true".equals(response)) {
                showArrivedConfirmDialog("1號注射點", modbus1);
            }
        }

        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {
            // ▼▼▼ 補上這行，才會知道為什麼不動！ ▼▼▼
            Log.e(TAG, "導航發生錯誤 (Code " + errorCode + "): " + errorString);
        }

        @Override
        public void onStatusUpdate(int status, String data) {
            // 也可以補上這個，觀察導航進度
            Log.d(TAG, "導航狀態更新: " + status);
        }
    };

    // 2號點監聽器
    private final ActionListener mBuzzer2NavListener = new ActionListener() {
        @Override
        public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK && "true".equals(response)) {
                // 抵達後，呼叫彈窗方法，傳入 2號 Modbus
                showArrivedConfirmDialog("2號注射點", modbus2);
            }
        }

        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {
        }

        @Override
        public void onStatusUpdate(int status, String data) {
        }
    };

    // 充電監聽器
    private final ActionListener mChargingListener = new ActionListener() {
        @Override
        public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK) Log.i(TAG, "回充成功");
        }

        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {
        }

        @Override
        public void onStatusUpdate(int status, String data) {
        }
    };


    // ===== Modbus 核心類別 (強化穩定版) =====
    private final class PersistentModbus {
        private final String ip;
        private final ExecutorService exec = Executors.newSingleThreadExecutor();
        private final Object lock = new Object();
        private TCPMasterConnection conn = null;
        private volatile boolean running = false;

        // Modbus 地址常數
        private static final int REG_ARMED = 0;
        private static final int REG_FORCED = 1;

        PersistentModbus(String ip) {
            this.ip = ip;
        }

        void start() {
            synchronized (lock) {
                if (running) return;
                running = true;
            }
            Log.d(TAG, "[診斷] 啟動 Modbus Worker: " + ip);
            // 啟動時執行一次連線測試
            exec.execute(() -> {
                checkNetworkReachability(); // 先檢查 Ping
                ensureConnected();          // 再嘗試 TCP 連線
            });
        }

        void stop() {
            Log.d(TAG, "[診斷] 停止 Modbus Worker: " + ip);
            enqueue(() -> safeWrite(REG_FORCED, 0)); // 嘗試關閉蜂鳴器
            enqueue(this::closeQuietly);

            synchronized (lock) {
                running = false;
            }
            exec.shutdown();
        }

        // 外部呼叫的響鈴方法
        void beep3s() {
            enqueue(() -> {
                Log.w(TAG, ">>> [診斷] 開始執行響鈴任務 (" + ip + ") <<<");

                // 步驟 0: 檢查網路是否活著
                if (!checkNetworkReachability()) {
                    Log.e(TAG, "[致命錯誤] Ping 失敗！設備可能斷電或 IP 錯誤: " + ip);
                    return; // 網路不通，直接放棄，不要浪費時間連線
                }

                // 步驟 1: 解除鎖定 (Armed)
                if (safeWrite(REG_ARMED, 1)) {
                    sleepMs(200);

                    // 步驟 2: 強制輸出 (開啟蜂鳴器)
                    if (safeWrite(REG_FORCED, 1)) {
                        Log.i(TAG, "[成功] 蜂鳴器應該正在響...");
                        sleepMs(6000); // 響鈴時間

                        // 步驟 3: 關閉輸出
                        safeWrite(REG_FORCED, 0);
                        Log.i(TAG, "[結束] 蜂鳴器關閉指令已發送");
                    } else {
                        Log.e(TAG, "[失敗] 無法寫入開啟指令 (REG_FORCED)");
                    }
                } else {
                    Log.e(TAG, "[失敗] 無法寫入準備指令 (REG_ARMED)");
                }
                Log.w(TAG, "<<< [診斷] 響鈴任務結束 (" + ip + ") <<<");
            });
        }

        // 加入排程
        private void enqueue(Runnable r) {
            if (!exec.isShutdown()) {
                exec.execute(() -> {
                    try {
                        r.run();
                    } catch (Exception e) {
                        Log.e(TAG, "[排程異常] " + e.getMessage());
                    }
                });
            }
        }

        private void sleepMs(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
            }
        }

        // ★ 新增：網路物理層檢測 (Ping) ★
        private boolean checkNetworkReachability() {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                // 嘗試在 2000ms 內 Ping 對方
                boolean reachable = addr.isReachable(2000);
                if (reachable) {
                    Log.d(TAG, "[網路檢測] Ping 成功: " + ip + " 是可達的");
                    return true;
                } else {
                    Log.e(TAG, "[網路檢測] Ping 失敗: " + ip + " 沒有回應 (Timeout)");
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "[網路檢測] Ping 發生錯誤: " + e.getMessage());
                return false;
            }
        }

        // 建立 TCP 連線
        private void ensureConnected() {
            synchronized (lock) {
                if (conn != null && conn.isConnected()) return; // 已經連線就跳過

                Log.d(TAG, "[連線] 嘗試建立 Modbus TCP 連線 -> " + ip);
                try {
                    InetAddress addr = InetAddress.getByName(ip);
                    conn = new TCPMasterConnection(addr);
                    conn.setPort(Modbus.DEFAULT_PORT); // 預設 502
                    conn.setTimeout(3000); // 3秒超時
                    conn.connect();
                    Log.i(TAG, "[連線] ★ TCP 連線成功建立 ★ (" + ip + ")");
                } catch (Exception e) {
                    Log.e(TAG, "[連線] 失敗! 無法連線到設備: " + e.getMessage());
                    closeQuietly();
                    // 這裡不 retry，交給下一次任務去處理，避免死迴圈
                }
            }
        }

        // 寫入暫存器 (回傳 boolean 代表成功或失敗)
        private boolean safeWrite(int address, int value) {
            synchronized (lock) {
                // 1. 檢查連線
                if (conn == null || !conn.isConnected()) {
                    Log.w(TAG, "[寫入] 發現未連線，嘗試重連...");
                    ensureConnected();
                    if (conn == null || !conn.isConnected()) {
                        Log.e(TAG, "[寫入] 重連失敗，放棄寫入 Address: " + address);
                        return false;
                    }
                }

                // 2. 執行寫入
                try {
                    Log.d(TAG, "[寫入] 發送: Address=" + address + ", Value=" + value);

                    WriteSingleRegisterRequest req = new WriteSingleRegisterRequest(address, new SimpleRegister(value));
                    req.setUnitID(SLAVE_ID);

                    ModbusTCPTransaction tx = new ModbusTCPTransaction(conn);
                    tx.setRequest(req);
                    tx.execute(); // 執行交易

                    Log.d(TAG, "[寫入] 交易成功！");
                    sleepMs(50); // 緩衝
                    return true;

                } catch (Exception e) {
                    Log.e(TAG, "[寫入] 交易發生例外: " + e.getMessage());
                    // 如果出現 Broken pipe 或 Connection reset，代表 Socket 壞了
                    closeQuietly();
                    return false;
                }
            }
        }

        private void closeQuietly() {
            synchronized (lock) {
                try {
                    if (conn != null) {
                        Log.d(TAG, "[資源] 關閉 Socket 連線");
                        conn.close();
                    }
                } catch (Exception ignored) {
                } finally {
                    conn = null;
                }
            }
        }
    }
}