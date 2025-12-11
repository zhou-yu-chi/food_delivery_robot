package com.ainirobot.robotos.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.InputType;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.ainirobot.coreservice.bean.CanElectricDoorBean;
import com.ainirobot.coreservice.client.Definition;
import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.listener.ActionListener;
import com.ainirobot.coreservice.client.listener.CommandListener;
import com.ainirobot.robotos.R;
import com.ainirobot.robotos.maputils.GsonUtil;

// ★★★ 新增：Modbus 與網路相關引用 (移植自 MainFragment) ★★★
import java.net.InetAddress;
import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

public class MultiDeliveryFragment extends BaseFragment {

    private static final String TAG = "MultiDeliveryFragment";

    // ★★★ 新增：Modbus 設定 (移植自 MainFragment) ★★★
    private static final String BUZZER_IP_1 = "192.168.162.101"; // 風機對應 IP
    private static final String BUZZER_IP_2 = "192.168.162.102"; // 洗手間對應 IP
    private Runnable mCurrentBeepTask = null; // 用來控制循環響鈴的任務

    // ===== UI 元件 =====
    private TextView mTvDest1, mTvDest2;
    private Button mBtnFan, mBtnWashroom;
    private Button mBtnReset, mBtnStart;
    private Button mBtnOpenDoor1, mBtnOpenDoor2;

    // ===== 任務資料 =====
    private String mDestName1 = null;
    private String mDestName2 = null;

    // ===== 任務狀態管理 =====
    private int mCurrentStage = 0;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    public static Fragment newInstance() {
        return new MultiDeliveryFragment();
    }

    @Override
    public View onCreateView(Context context) {
        View root = mInflater.inflate(R.layout.fragment_multi_delivery, null, false);
        bindViews(root);
        return root;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // ★★★ 安全機制：離開頁面時，務必停止蜂鳴器循環
        stopBuzzerLoop();
    }

    private void bindViews(View root) {
        mTvDest1 = root.findViewById(R.id.tv_dest_door_1);
        mTvDest2 = root.findViewById(R.id.tv_dest_door_2);
        mBtnOpenDoor1 = root.findViewById(R.id.btn_open_door_1);
        mBtnOpenDoor2 = root.findViewById(R.id.btn_open_door_2);
        mBtnFan = root.findViewById(R.id.btn_loc_fan);
        mBtnWashroom = root.findViewById(R.id.btn_loc_washroom);
        mBtnReset = root.findViewById(R.id.btn_reset);
        mBtnStart = root.findViewById(R.id.btn_start_delivery);

        // 開門按鈕功能
        mBtnOpenDoor1.setOnClickListener(v -> {
            Toast.makeText(getContext(), "正在開啟 1號門...", Toast.LENGTH_SHORT).show();
            controlElectricDoor(Definition.CAN_DOOR_DOOR1_DOOR2_OPEN);
        });
        mBtnOpenDoor2.setOnClickListener(v -> {
            Toast.makeText(getContext(), "正在開啟 2號門...", Toast.LENGTH_SHORT).show();
            controlElectricDoor(Definition.CAN_DOOR_DOOR3_DOOR4_OPEN);
        });

        // 地點選擇按鈕
        View.OnClickListener locListener = v -> {
            Button btn = (Button) v;
            String locationName = btn.getText().toString();
            selectLocation(locationName);
        };
        mBtnFan.setOnClickListener(locListener);
        mBtnWashroom.setOnClickListener(locListener);

        // 功能按鈕
        mBtnReset.setOnClickListener(v -> resetSelection());

        mBtnStart.setOnClickListener(v -> {
            if (mDestName1 == null && mDestName2 == null) {
                Toast.makeText(getContext(), "請至少選擇一個目的地", Toast.LENGTH_SHORT).show();
                return;
            }
            attemptAutoCloseAndStart();
        });
    }

    // ==========================================
    // Modbus 蜂鳴器控制核心 (移植區)
    // ==========================================

    /**
     * 啟動循環響鈴任務
     * @param locationName 地點名稱，用來判斷 IP
     */
    private void startBuzzerLoop(String locationName) {
        // 1. 判斷 IP
        String targetIp = null;
        if (locationName.contains("風機")) {
            targetIp = BUZZER_IP_1;
        } else if (locationName.contains("洗手間")) {
            targetIp = BUZZER_IP_2;
        }

        if (targetIp == null) {
            Log.w(TAG, "未知地點，無法啟動蜂鳴器: " + locationName);
            return;
        }

        // 2. 停止舊任務 (如果有)
        stopBuzzerLoop();

        Log.i(TAG, "啟動蜂鳴器循環 -> " + locationName + " (" + targetIp + ")");
        final String finalIp = targetIp;

        // 3. 定義新任務：響鈴 -> 等30秒 -> 再響鈴
        mCurrentBeepTask = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "執行一次響鈴...");
                executeOneShotBeep(finalIp);
                // 30秒後再次執行自己
                mHandler.postDelayed(this, 30 * 1000);
            }
        };

        // 4. 馬上執行第一次
        mHandler.post(mCurrentBeepTask);
    }

    /**
     * 停止蜂鳴器循環
     */
    private void stopBuzzerLoop() {
        if (mCurrentBeepTask != null) {
            Log.i(TAG, "停止蜂鳴器循環");
            mHandler.removeCallbacks(mCurrentBeepTask);
            mCurrentBeepTask = null;
        }
    }

    /**
     * 執行單次響鈴流程：開 -> 等6秒 -> 關
     */
    private void executeOneShotBeep(String targetIp) {
        new Thread(() -> {
            boolean openSuccess = sendModbusCommand(targetIp, true);
            if (!openSuccess) return;

            try { Thread.sleep(6000); } catch (InterruptedException e) { e.printStackTrace(); }

            sendModbusCommand(targetIp, false);
        }).start();
    }

    /**
     * Modbus TCP 發送指令
     */
    private boolean sendModbusCommand(String ip, boolean turnOn) {
        TCPMasterConnection conn = null;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            conn = new TCPMasterConnection(addr);
            conn.setPort(Modbus.DEFAULT_PORT);
            conn.setTimeout(3000);
            conn.connect();

            ModbusTCPTransaction tx = new ModbusTCPTransaction(conn);
            int value = turnOn ? 1 : 0;

            if (turnOn) {
                WriteSingleRegisterRequest reqArm = new WriteSingleRegisterRequest(0, new SimpleRegister(1));
                reqArm.setUnitID(1);
                tx.setRequest(reqArm);
                tx.execute();
            }

            WriteSingleRegisterRequest reqSwitch = new WriteSingleRegisterRequest(1, new SimpleRegister(value));
            reqSwitch.setUnitID(1);
            tx.setRequest(reqSwitch);
            tx.execute();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Modbus 發送失敗: " + e.getMessage());
            return false;
        } finally {
            if (conn != null && conn.isConnected()) conn.close();
        }
    }

    // ==========================================
    // 自動關門與出發邏輯
    // ==========================================
    private void attemptAutoCloseAndStart() {
        Toast.makeText(getContext(), "準備出發：正在自動關閉艙門...", Toast.LENGTH_SHORT).show();
        RobotApi.getInstance().startControlElectricDoor(0, Definition.CAN_DOOR_ALL_CLOSE, new ActionListener() {
            @Override
            public void onResult(int result, String message, String extraData) {}
            @Override
            public void onError(int errorCode, String errorString, String extraData) throws RemoteException {}
            @Override
            public void onStatusUpdate(int status, String data, String extraData) throws RemoteException {}
        });

        mHandler.postDelayed(() -> {
            checkDoorsAndAction(() -> startDeliverySequence());
        }, 3000);
    }

    // ==========================================
    // 選單邏輯
    // ==========================================
    private void selectLocation(String locName) {
        if (mDestName1 == null) {
            mDestName1 = locName;
            mTvDest1.setText(mDestName1);
            mTvDest1.setTextColor(getResources().getColor(android.R.color.holo_green_light));
        } else if (mDestName2 == null) {
            mDestName2 = locName;
            mTvDest2.setText(mDestName2);
            mTvDest2.setTextColor(getResources().getColor(android.R.color.holo_green_light));
        } else {
            Toast.makeText(getContext(), "艙位已滿，請先清除重設", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetSelection() {
        stopBuzzerLoop(); // 重設時也要停止響鈴
        mDestName1 = null;
        mDestName2 = null;
        mTvDest1.setText("尚未設定");
        mTvDest1.setTextColor(0xFFFFFFFF);
        mTvDest2.setText("尚未設定");
        mTvDest2.setTextColor(0xFFFFFFFF);
    }

    // ==========================================
    // 配送流程核心
    // ==========================================
    private void startDeliverySequence() {
        Log.i(TAG, "=== 檢查通過，開始多點配送任務 ===");
        setButtonsEnabled(false);
        processStage(1);
    }

    private void processStage(int stage) {
        mCurrentStage = stage;
        Log.d(TAG, "執行階段: " + stage);

        switch (stage) {
            case 1: // 前往第一點
                if (mDestName1 != null) {
                    RobotApi.getInstance().startNavigation(0, mDestName1, 1.5, 600 * 1000, new NavListener(1));
                } else {
                    processStage(3);
                }
                break;

            case 2: // 第一點抵達
                handleArrival(1, mDestName1);
                break;

            case 3: // 前往第二點
                if (mDestName2 != null) {
                    RobotApi.getInstance().startNavigation(0, mDestName2, 1.5, 600 * 1000, new NavListener(2));
                } else {
                    processStage(5);
                }
                break;

            case 4: // 第二點抵達
                handleArrival(2, mDestName2);
                break;

            case 5: // 返回待機點
                Toast.makeText(getContext(), "任務完成，返回待機中...", Toast.LENGTH_LONG).show();
                RobotApi.getInstance().startNavigation(0, "待機點", 1.5, 600 * 1000, new NavListener(3));
                break;

            case 6: // 結束
                setButtonsEnabled(true);
                resetSelection();
                break;
        }
    }

    // ==========================================
    // 抵達處理 (含密碼驗證 & 蜂鳴器觸發)
    // ==========================================
    private void handleArrival(int stopIndex, String locationName) {
        // ★★★ 1. 抵達時，立即啟動蜂鳴器循環
        startBuzzerLoop(locationName);

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                showUnlockDialog(stopIndex, locationName);
            });
        }
    }

    private void showUnlockDialog(int stopIndex, String locationName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("已抵達：" + locationName);
        builder.setMessage("蜂鳴器已啟動通知。\n請輸入密碼以解鎖艙門：");
        builder.setCancelable(false);

        final EditText inputPass = new EditText(getContext());
        inputPass.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputPass.setGravity(android.view.Gravity.CENTER);
        builder.setView(inputPass);

        builder.setPositiveButton("解鎖", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String input = inputPass.getText().toString();
            if (PasswordManager.checkPassword(getContext(), input)) {

                // ★★★ 2. 密碼正確 -> 停止蜂鳴器
                stopBuzzerLoop();

                dialog.dismiss();
                performOpenDoorSequence(stopIndex, locationName);
            } else {
                Toast.makeText(getContext(), "密碼錯誤，請重試", Toast.LENGTH_SHORT).show();
                inputPass.setText("");
            }
        });
    }

    private void performOpenDoorSequence(int stopIndex, String locationName) {
        int openCmd = (stopIndex == 1) ? Definition.CAN_DOOR_DOOR1_DOOR2_OPEN : Definition.CAN_DOOR_DOOR3_DOOR4_OPEN;
        int closeCmd = (stopIndex == 1) ? Definition.CAN_DOOR_DOOR1_DOOR2_CLOSE : Definition.CAN_DOOR_DOOR3_DOOR4_CLOSE;

        Log.i(TAG, "密碼驗證通過，開啟艙門...");
        Toast.makeText(getContext(), "密碼正確，正在開門...", Toast.LENGTH_SHORT).show();
        controlElectricDoor(openCmd);

        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                builder.setTitle("艙門已開啟");
                String doorName = (stopIndex == 1) ? "上艙" : "下艙";
                builder.setMessage(doorName + "已開啟。\n取物後請關門。");
                builder.setCancelable(false);

                builder.setPositiveButton("已取物，關門", (dialog, which) -> {
                    Toast.makeText(getContext(), "正在關門...", Toast.LENGTH_SHORT).show();
                    controlElectricDoor(closeCmd);

                    mHandler.postDelayed(() -> {
                        if (stopIndex == 1) processStage(3);
                        else processStage(5);
                    }, 4000);
                });
                builder.show();
            });
        }
    }

    private void controlElectricDoor(final int doorCmd) {
        RobotApi.getInstance().startControlElectricDoor(0, doorCmd, new ActionListener() {
            @Override
            public void onResult(int result, String message, String extraData) {}
            @Override
            public void onError(int errorCode, String errorString, String extraData) throws RemoteException {
                if (errorCode == Definition.ERROR_ELECTRIC_DOOR_BLOCK ||
                        errorCode == Definition.ERROR_ELECTRIC_DOOR_UPPER_BLOCK ||
                        errorCode == Definition.ERROR_ELECTRIC_DOOR_LOWER_BLOCK) {
                    showToastOnUI("警告：艙門受阻，請檢查！");
                }
            }
            @Override
            public void onStatusUpdate(int status, String data, String extraData) throws RemoteException {}
        });
    }

    private void checkDoorsAndAction(Runnable actionTask) {
        RobotApi.getInstance().getElectricDoorStatus(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                boolean isSafe = false;
                if (result == 1 && !TextUtils.isEmpty(message)) {
                    try {
                        CanElectricDoorBean doorBean = GsonUtil.fromJson(message, CanElectricDoorBean.class);
                        if (doorBean != null &&
                                doorBean.getDoor1() == Definition.CAN_DOOR_STATUS_CLOSE &&
                                doorBean.getDoor2() == Definition.CAN_DOOR_STATUS_CLOSE &&
                                doorBean.getDoor3() == Definition.CAN_DOOR_STATUS_CLOSE &&
                                doorBean.getDoor4() == Definition.CAN_DOOR_STATUS_CLOSE) {
                            isSafe = true;
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                }

                if (isSafe) {
                    if (getActivity() != null) getActivity().runOnUiThread(actionTask);
                } else {
                    showDoorWarning();
                }
            }
        });
    }

    private void showDoorWarning() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            new AlertDialog.Builder(getContext())
                    .setTitle("無法出發")
                    .setMessage("艙門似乎未關緊，或夾到異物！\n請檢查後再試一次。")
                    .setPositiveButton("好", null)
                    .show();
        });
    }

    private void showToastOnUI(String msg) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show());
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            mBtnStart.setEnabled(enabled);
            mBtnReset.setEnabled(enabled);
            mBtnStart.setAlpha(enabled ? 1.0f : 0.5f);
        });
    }

    private class NavListener extends ActionListener {
        private int targetPhase;
        public NavListener(int phase) { this.targetPhase = phase; }
        @Override
        public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK) {
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    if (targetPhase == 1) processStage(2);
                    else if (targetPhase == 2) processStage(4);
                    else if (targetPhase == 3) processStage(6);
                });
            } else {
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "導航中斷", Toast.LENGTH_SHORT).show();
                    setButtonsEnabled(true);
                });
            }
        }
        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {}
        @Override
        public void onStatusUpdate(int status, String data) {}
    }
}