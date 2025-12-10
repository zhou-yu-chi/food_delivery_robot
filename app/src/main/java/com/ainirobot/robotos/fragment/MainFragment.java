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

import android.text.TextUtils;
import com.ainirobot.coreservice.bean.CanElectricDoorBean;
import com.ainirobot.coreservice.client.listener.CommandListener;
import com.ainirobot.robotos.maputils.GsonUtil;


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


    private void bindViews(View root) {
        mBtnStandby = root.findViewById(R.id.btn_standby_point);
        mBtnPoint1 = root.findViewById(R.id.btn_injection_point_1);
        mBtnPoint2 = root.findViewById(R.id.btn_injection_point_2);
        mBtnDoorControl = root.findViewById(R.id.electric_door_control);
        mBtnCharging = root.findViewById(R.id.btn_Charging_pile);
        mExit = root.findViewById(R.id.exit);

        // 修改 1: 待機點
        mBtnStandby.setOnClickListener(v -> {
            checkDoorsAndAction(() -> {
                Log.i(TAG, "檢查通過，執行指令：前往待機點");
                RobotApi.getInstance().startNavigation(0, "待機點", 1.5, 600 * 1000, mSimpleNavListener);
            });
        });

        // 修改 2: 1號注射點6
        mBtnPoint1.setOnClickListener(v -> {
            checkDoorsAndAction(() -> {
                Log.i(TAG, "檢查通過，執行指令：前往1號注射點");
                RobotApi.getInstance().startNavigation(0, "風機", 1.5, 600 * 1000, mBuzzer1NavListener);
            });
        });

        // 修改 3: 2號注射點
        mBtnPoint2.setOnClickListener(v -> {
            checkDoorsAndAction(() -> {
                Log.i(TAG, "檢查通過，執行指令：前往2號注射點");
                RobotApi.getInstance().startNavigation(0, "洗手間", 1.5, 600 * 1000, mBuzzer2NavListener);
            });
        });

        // 門控按鈕不需要檢查 (維持原樣)
        mBtnDoorControl.setOnClickListener(v -> switchFragment(ElectricDoorActionControlFragment.newInstance()));

        // 修改 4: 自動回充 (強烈建議回充也要檢查門)
        mBtnCharging.setOnClickListener(v -> {
            checkDoorsAndAction(() -> {
                Log.i(TAG, "檢查通過，執行指令：開始自動回充");
                RobotApi.getInstance().goCharging(0);
                if (getActivity() != null) {
                    // 為了讓使用者看到「開始自動回充」的提示，可以延遲 1 秒再關閉
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (getActivity() != null) {
                            getActivity().finish();
                        }
                    }, 0);
                }
            });
        });


        // 離開按鈕維持原樣
        mExit.setOnClickListener(v -> {
            mHandler.removeCallbacksAndMessages(null);
            if (getActivity() != null) {
                getActivity().onBackPressed();
                getActivity().finish();
            }
        });
    }

    /**
     * 短連線模式核心：建立連線 -> 開啟蜂鳴器 -> 等待 -> 關閉蜂鳴器 -> 斷線
     * 每次執行都是獨立的，不會佔用連線資源。
     */
    private void executeOneShotBeep(String targetIp) {
        // 開啟一個新執行緒來執行網路動作，避免卡住畫面
        new Thread(() -> {
            TCPMasterConnection conn = null;
            try {
                Log.i(TAG, "[短連線] 開始任務，目標 IP: " + targetIp);
                InetAddress addr = InetAddress.getByName(targetIp);

                // 1. 建立連線
                conn = new TCPMasterConnection(addr);
                conn.setPort(Modbus.DEFAULT_PORT); // 502
                conn.setTimeout(3000); // 設定 3秒超時，連不上就放棄，不要卡住
                conn.connect();


                Log.d(TAG, "[短連線] 連線成功，準備發送指令...");

                // 2. 準備交易物件
                ModbusTCPTransaction tx = new ModbusTCPTransaction(conn);

                // 3. 步驟 A: 解鎖 (Armed = 1)
                // 假設你的蜂鳴器 Address 0 是 Armed (依據你原本的程式碼)
                WriteSingleRegisterRequest reqArm = new WriteSingleRegisterRequest(0, new SimpleRegister(1));
                reqArm.setUnitID(1); // Slave ID
                tx.setRequest(reqArm);
                tx.execute();

                // 4. 步驟 B: 開啟聲音 (Forced = 1)
                WriteSingleRegisterRequest reqOn = new WriteSingleRegisterRequest(1, new SimpleRegister(1));
                reqOn.setUnitID(1);
                tx.setRequest(reqOn);
                tx.execute();

                Log.i(TAG, "[短連線] 蜂鳴器已開啟，響鈴 3 秒...");

                // 5. 讓它響 3 秒 (Thread sleep)
                try {
                    Thread.sleep(6000); // 這裡控制響多久
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // 6. 步驟 C: 關閉聲音 (Forced = 0)
                WriteSingleRegisterRequest reqOff = new WriteSingleRegisterRequest(1, new SimpleRegister(0));
                reqOff.setUnitID(1);
                tx.setRequest(reqOff);
                tx.execute();

                Log.i(TAG, "[短連線] 蜂鳴器關閉指令已發送");

            } catch (Exception e) {
                // 如果連線失敗或寫入失敗，會跑到這裡
                Log.e(TAG, "[短連線] 發生錯誤 (可能網路不通或IP錯誤): " + e.getMessage());
            } finally {
                // ★★★ 最重要的一步：無論成功失敗，絕對要關閉連線 ★★★
                if (conn != null && conn.isConnected()) {
                    conn.close();
                    Log.d(TAG, "[短連線] 連線已徹底斷開 (資源釋放)");
                }
            }
        }).start();
    }
    // ==========================================
    // 新增功能：移動前檢查艙門狀態
    // ==========================================

    /**
     * 檢查門是否全關：
     * 1. 呼叫 API 取得狀態
     * 2. 如果全關 -> 執行 navigationTask (移動)
     * 3. 如果沒關 -> 跳出警告
     */
    private void checkDoorsAndAction(Runnable actionTask) {
        // 呼叫 SDK 獲取艙門狀態
        RobotApi.getInstance().getElectricDoorStatus(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                // Log.d(TAG, "檢查艙門結果: " + result + ", msg: " + message);

                boolean isSafe = false;

                if (result == 1 && !TextUtils.isEmpty(message)) {
                    try {
                        // 使用 Gson 解析回傳的 JSON
                        CanElectricDoorBean doorBean = GsonUtil.fromJson(message, CanElectricDoorBean.class);

                        // 檢查所有門是否都是「關閉 (CLOSE)」狀態
                        if (doorBean != null && isAllDoorsClosed(doorBean)) {
                            isSafe = true;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析艙門狀態失敗: " + e.getMessage());
                    }
                }

                if (isSafe) {
                    // 安全：門都關好了，執行傳入的動作 (例如導航)
                    // 注意：API 回調可能在背景執行緒，操作 UI 或導航建議切回主執行緒
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(actionTask);
                    }
                } else {
                    // 危險：門沒關，或者讀取失敗，顯示警告
                    showDoorOpenWarningDialog();
                }
            }
        });
    }

    /**
     * 判斷 4 扇門是否都處於關閉狀態
     */
    private boolean isAllDoorsClosed(CanElectricDoorBean doorBean) {
        // 根據 ElectricDoorControlFragment 的邏輯：
        // Door 1 & 2 是上艙門
        // Door 3 & 4 是下艙門
        boolean door1Closed = (doorBean.getDoor1() == Definition.CAN_DOOR_STATUS_CLOSE);
        boolean door2Closed = (doorBean.getDoor2() == Definition.CAN_DOOR_STATUS_CLOSE);
        boolean door3Closed = (doorBean.getDoor3() == Definition.CAN_DOOR_STATUS_CLOSE);
        boolean door4Closed = (doorBean.getDoor4() == Definition.CAN_DOOR_STATUS_CLOSE);

        return door1Closed && door2Closed && door3Closed && door4Closed;
    }

    /**
     * 顯示警告視窗 (必須在 UI Thread 執行)
     */
    private void showDoorOpenWarningDialog() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            new AlertDialog.Builder(getContext())
                    .setTitle("無法移動")
                    .setMessage("檢測到艙門未完全關閉！\n\n請確認所有艙門皆已關閉後再試一次。")
                    .setPositiveButton("好，我去關門", (dialog, which) -> dialog.dismiss())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        });
    }

    // ==========================================
    // 抵達確認視窗 (修改版：支援短連線與循環響鈴)
    // ==========================================
    private void showArrivedConfirmDialog(String pointName, String targetIp) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            // 1. 定義 30秒 循環任務
            Runnable beepLoopTask = new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "循環計時到：觸發一次短連線響鈴 (" + targetIp + ")");

                    // 呼叫短連線方法 (這會開新執行緒去跑，不會卡住 UI)
                    executeOneShotBeep(targetIp);

                    // 設定 30秒後再次執行這個 run()
                    mHandler.postDelayed(this, 30 * 1000);
                }
            };

            // 2. 建立彈跳視窗
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("已抵達：" + pointName);
            builder.setMessage("蜂鳴器已啟動。\n請領取物品，確認領取完畢後請點擊下方按鈕。");
            builder.setCancelable(false); // 禁止點空白關閉

            // 3. 設定按鈕動作
            builder.setPositiveButton("我已收到物品", (dialog, which) -> {
                Log.i(TAG, "使用者確認收到，停止循環響鈴。");
                // 移除計時器，就不會再響下一次了
                mHandler.removeCallbacks(beepLoopTask);
            });

            // 4. 顯示視窗
            AlertDialog dialog = builder.create();
            dialog.show();

            // 5. 視窗跳出後，馬上執行第一次響鈴
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
    // 1號點監聽器
    private final ActionListener mBuzzer1NavListener = new ActionListener() {
        @Override
        public void onResult(int status, String response) throws RemoteException {
            Log.i(TAG, "1號點導航結束 Result: " + status);

            // 只要 Status 是 OK 就執行 (拿掉 response 文字檢查)
            if (status == Definition.RESULT_OK) {
                // 改成傳入 IP 字串
                showArrivedConfirmDialog("1號注射點", BUZZER_IP_1);
            }
        }

        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {
            Log.e(TAG, "1號點導航錯誤: " + errorString);
        }

        @Override
        public void onStatusUpdate(int status, String data) {
        }
    };

    // 2號點監聽器
    private final ActionListener mBuzzer2NavListener = new ActionListener() {
        @Override
        public void onResult(int status, String response) throws RemoteException {
            Log.i(TAG, "2號點導航結束 Result: " + status);

            if (status == Definition.RESULT_OK) {
                // 改成傳入 IP 字串
                showArrivedConfirmDialog("2號注射點", BUZZER_IP_2);
            }
        }

        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {
            Log.e(TAG, "2號點導航錯誤: " + errorString);
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
}