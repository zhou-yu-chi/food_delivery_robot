package com.ainirobot.robotos.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import android.speech.tts.TextToSpeech; // Android 原生語音
import java.util.Locale;                // 設定語言用

import androidx.fragment.app.Fragment;

import com.ainirobot.coreservice.bean.CanElectricDoorBean;
import com.ainirobot.coreservice.client.Definition;
import com.ainirobot.coreservice.client.RobotApi;
import com.ainirobot.coreservice.client.listener.ActionListener;
import com.ainirobot.coreservice.client.listener.CommandListener;
import com.ainirobot.robotos.R;
import com.ainirobot.robotos.maputils.GsonUtil;

import java.net.InetAddress;
import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import android.media.ToneGenerator;
import android.media.AudioManager;

public class MultiDeliveryFragment extends BaseFragment {

    private static final String TAG = "MultiDeliveryFragment";
    private java.util.ArrayList<String> mReturnList = new java.util.ArrayList<>();
    private static final String BUZZER_IP_1 = "192.168.162.101";
    private static final String BUZZER_IP_2 = "192.168.162.102";
    private Runnable mCurrentBeepTask = null;

    private TextView mTvDest1, mTvDest2;
    private Button mBtnFan, mBtnWashroom;
    private TextToSpeech mTTS;
    private Button mBtnReset, mBtnStart;
    private Button mBtnOpenDoor1, mBtnOpenDoor2;
    private Button mBtnMoreFunctions;

    private String mDestName1 = null;
    private String mDestName2 = null;

    private int mCurrentStage = 0;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    // 定義特殊標記：代表「兩個門都要開」
    private static final int STOP_INDEX_BOTH = 3;

    public static Fragment newInstance() {
        return new MultiDeliveryFragment();
    }

    @Override
    public View onCreateView(Context context) {
        View root = mInflater.inflate(R.layout.fragment_multi_delivery, null, false);

        // ★★★ 2. 初始化語音引擎 ★★★
        mTTS = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    // 設定語言為中文
                    int result = mTTS.setLanguage(Locale.CHINESE); // 或 Locale.TRADITIONAL_CHINESE
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "TTS: 不支援繁體中文或未安裝語音包");
                    }
                } else {
                    Log.e(TAG, "TTS: 初始化失敗");
                }
            }
        });

        bindViews(root);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        hideBackView();
        // 1. 註冊即時監聽 (保持不變，這是為了監聽畫面開啟後的變化)
        RobotApi.getInstance().registerStatusListener(Definition.STATUS_CAN_ELECTRIC_DOOR_CTRL, mDoorStatusListener);

        // ★★★ 2. 修改這裡：主動查詢當前狀態，並立即更新 UI ★★★
        RobotApi.getInstance().getElectricDoorStatus(0, new CommandListener() {
            @Override
            public void onResult(int result, String message, String extraData) {
                // 如果查詢成功 (result == 1) 且有資料
                if (result == 1 && !TextUtils.isEmpty(message)) {
                    try {
                        // 解析 JSON
                        CanElectricDoorBean doorBean = GsonUtil.fromJson(message, CanElectricDoorBean.class);

                        // 切回主執行緒更新按鈕外觀
                        if (getActivity() != null && doorBean != null) {
                            getActivity().runOnUiThread(() -> {
                                updateButtonState(doorBean); // 重用我們寫好的更新邏輯
                            });
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        RobotApi.getInstance().unregisterStatusListener(mDoorStatusListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopBuzzerLoop();
        RobotApi.getInstance().stopNavigation(0, true); // 記得這裡是 stopNavigation


        // ★★★ 3. 釋放語音資源 ★★★
        if (mTTS != null) {
            mTTS.stop();
            mTTS.shutdown();
        }
    }

    private void bindViews(View root) {
        mBtnMoreFunctions = root.findViewById(R.id.btn_more_functions);
        mTvDest1 = root.findViewById(R.id.tv_dest_door_1);
        mTvDest2 = root.findViewById(R.id.tv_dest_door_2);
        mBtnOpenDoor1 = root.findViewById(R.id.btn_open_door_1);
        mBtnOpenDoor2 = root.findViewById(R.id.btn_open_door_2);
        mBtnFan = root.findViewById(R.id.btn_loc_fan);
        mBtnWashroom = root.findViewById(R.id.btn_loc_washroom);
        mBtnReset = root.findViewById(R.id.btn_reset);
        mBtnStart = root.findViewById(R.id.btn_start_delivery);
        mBtnMoreFunctions = root.findViewById(R.id.btn_more_functions);

        View.OnClickListener locListener = v -> {
            Button btn = (Button) v;
            String locationName = btn.getText().toString();
            selectLocation(locationName);
        };
        mBtnFan.setOnClickListener(locListener);
        mBtnWashroom.setOnClickListener(locListener);


        mBtnReset.setOnClickListener(v -> resetSelection());

        mBtnStart.setOnClickListener(v -> {
            if (mDestName1 == null && mDestName2 == null) {
                Toast.makeText(getContext(), "請至少選擇一個目的地", Toast.LENGTH_SHORT).show();
                return;
            }
            attemptAutoCloseAndStart();
        });
        mBtnMoreFunctions.setOnClickListener(v -> {
            switchFragment(MainFragment.newInstance());
        });

    }

    // ==========================================
    // 狀態監聽與按鈕更新
    // ==========================================
    private com.ainirobot.coreservice.client.StatusListener mDoorStatusListener = new com.ainirobot.coreservice.client.StatusListener() {
        @Override
        public void onStatusUpdate(String type, String data) {
            if (TextUtils.equals(type, Definition.STATUS_CAN_ELECTRIC_DOOR_CTRL)) {
                CanElectricDoorBean doorBean = GsonUtil.fromJson(data, CanElectricDoorBean.class);
                if (getActivity() != null && doorBean != null) {
                    getActivity().runOnUiThread(() -> {
                        updateButtonState(doorBean);
                    });
                }
            }
        }
    };

    private void updateButtonState(CanElectricDoorBean bean) {
        // 如果正在配送中 (按鈕被鎖定)，就不要讓狀態監聽器去改變按鈕的 Enabled 狀態
        // 這樣可以防止 "機器人移動中 -> 門狀態更新 -> 按鈕突然變回可按" 的 Bug
        if (!mBtnStart.isEnabled()) {
            // 配送中：強制保持灰色與禁用，只更新文字讓使用者知道門現在怎樣
            updateButtonTextOnly(mBtnOpenDoor1, bean.getDoor1());
            updateButtonTextOnly(mBtnOpenDoor2, bean.getDoor3());
            return;
        }

        // --- 正常待機模式 (按鈕可操作) ---
        updateSingleButtonNormal(mBtnOpenDoor1, bean.getDoor1(), Definition.CAN_DOOR_DOOR1_DOOR2_OPEN, Definition.CAN_DOOR_DOOR1_DOOR2_CLOSE);
        updateSingleButtonNormal(mBtnOpenDoor2, bean.getDoor3(), Definition.CAN_DOOR_DOOR3_DOOR4_OPEN, Definition.CAN_DOOR_DOOR3_DOOR4_CLOSE);
    }

    // 輔助方法：只更新文字 (配送中模式)
    private void updateButtonTextOnly(Button btn, int status) {
        btn.setEnabled(false); // 強制禁用
        btn.setBackgroundTintList(ColorStateList.valueOf(0xFF888888)); // 灰色
        if (status == Definition.CAN_DOOR_STATUS_RUNNING) btn.setText("運作中..");
        else if (status == Definition.CAN_DOOR_STATUS_OPEN) btn.setText("已開啟");
        else btn.setText("已關閉");
    }

    // 輔助方法：正常更新按鈕 (待機模式)
    private void updateSingleButtonNormal(Button btn, int status, int openCmd, int closeCmd) {
        if (status == Definition.CAN_DOOR_STATUS_RUNNING) {
            btn.setText("運作中..");
            btn.setEnabled(false);
            btn.setBackgroundTintList(ColorStateList.valueOf(0xFF888888));
        } else if (status == Definition.CAN_DOOR_STATUS_OPEN) {
            btn.setText("關門");
            btn.setEnabled(true);
            btn.setBackgroundTintList(ColorStateList.valueOf(0xFFFF5252)); // 紅色
            btn.setOnClickListener(v -> controlElectricDoor(closeCmd));
        } else {
            btn.setText("開門");
            btn.setEnabled(true);
            btn.setBackgroundTintList(ColorStateList.valueOf(0xFFFF9800)); // 橘色
            btn.setOnClickListener(v -> controlElectricDoor(openCmd));
        }
    }

    // ==========================================
    // 配送流程核心 (修改了同地點邏輯)
    // ==========================================
    private void startDeliverySequence() {
        Log.i(TAG, "=== 檢查通過，開始配送 ===");

        setButtonsEnabled(false); // ★★★ 鎖住所有按鈕 ★★★
        processStage(1);
    }

    private void processStage(int stage) {
        mCurrentStage = stage;
        Log.d(TAG, "執行階段: " + stage);
        switch (stage) {
            case 1: // 前往第一站
                if (mDestName1 != null) {
                    RobotApi.getInstance().startNavigation(0, mDestName1, 1.5, 600 * 1000, new NavListener(1));
                } else {
                    processStage(3);
                }
                break;

            case 2: // 第一站抵達
                // ★★★ 關鍵修改：判斷是否跟第二站地點相同 ★★★
                if (mDestName1 != null && mDestName1.equals(mDestName2)) {
                    Log.i(TAG, "偵測到同地點配送，合併開門動作");
                    // 傳入 STOP_INDEX_BOTH (3)，代表兩個門都要開
                    handleArrival(STOP_INDEX_BOTH, mDestName1);
                } else {
                    handleArrival(1, mDestName1);
                }
                break;

            case 3: // 前往第二站
                if (mDestName2 != null) {
                    RobotApi.getInstance().startNavigation(0, mDestName2, 1.5, 600 * 1000, new NavListener(2));
                } else {
                    processStage(5);
                }
                break;

            case 4: // 第二站抵達
                handleArrival(2, mDestName2);
                break;

            case 5: // 回待機點
                Toast.makeText(getContext(), "任務完成，返回待機中...", Toast.LENGTH_LONG).show();
                RobotApi.getInstance().startNavigation(0, "待機點", 1.5, 600 * 1000, new NavListener(3));
                break;

            case 6: // 結束
                setButtonsEnabled(true); // ★★★ 解鎖按鈕 ★★★
                if (!mReturnList.isEmpty()) {
                    showReturnSummaryDialog();
                    speak("我回來了，等待下一次任務。");
                } else {
                    // 如果沒有退回物品，直接重置
                    resetSelection();
                    speak("我回來了，等待下一次任務。");
                }
                break;
        }

    }
    private void showReturnSummaryDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("以下站點有物品未領取，請檢查艙門：\n\n");
        for (String record : mReturnList) {
            sb.append("● ").append(record).append("\n");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("⚠️ 任務結束 - 物品檢查");
        builder.setMessage(sb.toString());
        builder.setCancelable(false);
        builder.setPositiveButton("我知道了", (dialog, which) -> {
            // 使用者確認後，才重置介面
            resetSelection();

            // 貼心功能：如果怕使用者忘記開門，這裡可以自動把門全開，或者讓使用者自己按
            // controlElectricDoor(Definition.CAN_DOOR_ALL_OPEN); // 視需求決定是否要自動開
        });
        builder.show();
    }


    // ==========================================
    // 抵達與開門 (修改了雙開邏輯)
    // ==========================================
    private void handleArrival(int stopIndex, String locationName) {
        // 1. 啟動蜂鳴器 (維持原樣)
        startBuzzerLoop(locationName);



        // 3. 顯示密碼視窗 (維持原樣)
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> showUnlockDialog(stopIndex, locationName));
        }
    }

    private void showUnlockDialog(int stopIndex, String locationName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("已抵達：" + locationName);
        builder.setMessage("請輸入密碼以解鎖：");
        builder.setCancelable(false);
        final EditText inputPass = new EditText(getContext());
        inputPass.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputPass.setGravity(android.view.Gravity.CENTER);
        builder.setView(inputPass);
        builder.setPositiveButton("解鎖", null);
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (PasswordManager.checkPassword(getContext(), inputPass.getText().toString())) {
                stopBuzzerLoop();
                dialog.dismiss();
                performOpenDoorSequence(stopIndex, locationName);
            } else {
                Toast.makeText(getContext(), "密碼錯誤", Toast.LENGTH_SHORT).show();
                inputPass.setText("");
            }
        });
    }

    private void performOpenDoorSequence(int stopIndex, String locationName) {
        // 1. 根據站點決定要開哪扇門 (這是原本的正常流程)
        int openCmd;
        String doorMsg;

        if (stopIndex == STOP_INDEX_BOTH) {
            openCmd = Definition.CAN_DOOR_ALL_OPEN;
            doorMsg = "【上艙】與【下艙】";
        } else if (stopIndex == 1) {
            openCmd = Definition.CAN_DOOR_DOOR1_DOOR2_OPEN;
            doorMsg = "【上艙】";
        } else {
            openCmd = Definition.CAN_DOOR_DOOR3_DOOR4_OPEN;
            doorMsg = "【下艙】";
        }

        Log.i(TAG, "密碼驗證通過，開啟艙門...");
        Toast.makeText(getContext(), "密碼正確，正在開門...", Toast.LENGTH_SHORT).show();

        // 執行開門
        controlElectricDoor(openCmd);

        // 2. 呼叫確認視窗 (抽離出來的新方法)
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                showPickupConfirmationDialog(stopIndex, doorMsg, locationName);
            });
        }
    }
    private void executeCloseAndDepart(int stopIndex) {
        Toast.makeText(getContext(), "正在關門...", Toast.LENGTH_SHORT).show();

        // 無論之前開了幾個門，離開時一律發送「全關」指令
        int closeAllCmd = Definition.CAN_DOOR_ALL_CLOSE;
        controlElectricDoor(closeAllCmd);

        // 啟動安全檢查迴圈
        startDoorCheckLoop(closeAllCmd, stopIndex);
    }
    private void showPickupConfirmationDialog(int stopIndex, String currentDoorMsg, String locationName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("艙門已開啟");
        // 修改提示文字
        builder.setMessage(currentDoorMsg + "已開啟。\n\n● 若物品無誤：請取物後點擊【已取物，關門】。\n● 若需其他協助：請點擊【其他選項】。");
        builder.setCancelable(false);

        // --- 按鈕 1: 正常流程 ---
        builder.setPositiveButton("已取物，關門", (dialog, which) -> {
            // 改呼叫剛剛抽離出來的方法
            executeCloseAndDepart(stopIndex);
        });

        // --- 按鈕 2: 改成 "其他選項" ---
        builder.setNeutralButton("⚙️ 其他選項", (dialog, which) -> {
            // 點擊後跳出子選單
            showOtherOptionsDialog(stopIndex, currentDoorMsg, locationName);
        });

        builder.show();
    }
    private void showOtherOptionsDialog(int stopIndex, String doorMsg, String locationName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("特殊狀況處理");
        // 1. 上方加入解釋文字
        builder.setMessage("請選擇此物品的後續處理方式：\n\n" +
                "● ⚠️ 物品有誤：\n   輸入管理員密碼，全開艙門開啟。\n\n" +
                "● ↩️ 送回待機點：\n   將物品送回待機點，記錄後跳回上一頁，請您再按【已取物，關門】繼續行程。");
        builder.setCancelable(false); // 強制選擇，不能點旁邊關閉

        // 2. 左邊按鈕：物品有誤
        builder.setNeutralButton("⚠️ 物品有誤", (dialog, which) -> {
            showErrorUnlockDialog(stopIndex, locationName);
        });

        // 3. 右邊按鈕：送回待機點 (修改邏輯)
        builder.setPositiveButton("↩️ 送回待機點", (dialog, which) -> {
            // (1) 記錄起來
            String record = "地點：" + locationName + " (" + doorMsg + ")";
            mReturnList.add(record);

            // (2) 提示使用者
            Toast.makeText(getContext(), "已記錄「退回」，請點擊【已取物，關門】繼續。", Toast.LENGTH_LONG).show();

            // (3) ★★★ 關鍵修改：跳回原本的確認視窗，而不是直接出發 ★★★
            showPickupConfirmationDialog(stopIndex, doorMsg, locationName);
        });

        // 4. 移除原本的 "取消" 按鈕，只留上面兩個選項並排
        builder.show();
    }
    private void showErrorUnlockDialog(int stopIndex, String locationName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("⚠️ 管理員驗證");
        builder.setMessage("您即將開啟所有艙門以檢查物品。\n請輸入密碼：");
        builder.setCancelable(false);

        final EditText inputPass = new EditText(getContext());
        inputPass.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        inputPass.setGravity(android.view.Gravity.CENTER);
        builder.setView(inputPass);

        builder.setPositiveButton("驗證並全開", null);

        // --- 這裡就是原本報錯的地方 ---
        builder.setNegativeButton("取消", (dialog, which) -> {
            // 計算門的名稱
            String doorName = (stopIndex == 1) ? "【上艙】" : (stopIndex == 2 ? "【下艙】" : "【上艙】與【下艙】");

            // ★★★ 修正：現在這裡傳入 3 個參數了 (原本只有 2 個導致報錯)
            showPickupConfirmationDialog(stopIndex, doorName, locationName);
        });

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String input = inputPass.getText().toString();
            if (PasswordManager.checkPassword(getContext(), input)) {
                dialog.dismiss();

                // 1. 密碼正確 -> 開啟所有門
                Toast.makeText(getContext(), "驗證通過，正在開啟所有艙門...", Toast.LENGTH_SHORT).show();
                controlElectricDoor(Definition.CAN_DOOR_ALL_OPEN);

                // 2. 顯示更新後的確認視窗
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        // 這裡也補上 locationName
                        showPickupConfirmationDialog(stopIndex, "【所有艙門】", locationName);
                    });
                }
            } else {
                Toast.makeText(getContext(), "密碼錯誤", Toast.LENGTH_SHORT).show();
                inputPass.setText("");
            }
        });
    }

    // ==========================================
    // 安全檢查 (修改後續流程判斷)
    // ==========================================
    private void startDoorCheckLoop(int closeCmd, int stopIndex) {
        mHandler.postDelayed(() -> {
            RobotApi.getInstance().getElectricDoorStatus(0, new CommandListener() {
                @Override
                public void onResult(int result, String message, String extraData) {
                    boolean isClosed = false;
                    if (result == 1 && !TextUtils.isEmpty(message)) {
                        CanElectricDoorBean doorBean = GsonUtil.fromJson(message, CanElectricDoorBean.class);
                        if (doorBean != null &&
                                doorBean.getDoor1() == Definition.CAN_DOOR_STATUS_CLOSE &&
                                doorBean.getDoor2() == Definition.CAN_DOOR_STATUS_CLOSE &&
                                doorBean.getDoor3() == Definition.CAN_DOOR_STATUS_CLOSE &&
                                doorBean.getDoor4() == Definition.CAN_DOOR_STATUS_CLOSE) {
                            isClosed = true;
                        }
                    }

                    if (isClosed) {
                        Log.i(TAG, "安全檢查通過");
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "艙門已確認關閉，出發！", Toast.LENGTH_SHORT).show();

                                // ★★★ 邏輯判斷：下一步去哪？ ★★★
                                if (stopIndex == STOP_INDEX_BOTH) {
                                    // 如果是雙開模式，代表兩單都送完了，直接回待機點
                                    processStage(5);
                                } else if (stopIndex == 1) {
                                    processStage(3); // 去第二站
                                } else {
                                    processStage(5); // 去待機點
                                }
                            });
                        }
                    } else {
                        Log.w(TAG, "門未關閉，重試中...");
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                showRetryDialog();
                                controlElectricDoor(closeCmd);
                                startDoorCheckLoop(closeCmd, stopIndex);
                            });
                        }
                    }
                }
            });
        }, 5000);
    }

    // ... (其他輔助方法保持不變：controlElectricDoor, checkDoorsAndAction, showRetryDialog, Modbus, selectLocation 等) ...
    // 請複製上一個版本的這些方法，或直接保留您原本檔案中的這些部分，它們不需要修改。

    // 為了方便您複製，以下提供 setButtonsEnabled 的新版本

    // ★★★ 修改：鎖定/解鎖所有按鈕 ★★★
    private void setButtonsEnabled(boolean enabled) {
        if (getActivity() != null) getActivity().runOnUiThread(() -> {
            mBtnStart.setEnabled(enabled);
            mBtnReset.setEnabled(enabled);
            mBtnFan.setEnabled(enabled);
            mBtnWashroom.setEnabled(enabled);
            mBtnOpenDoor1.setEnabled(enabled);
            mBtnOpenDoor2.setEnabled(enabled);

            // ★★★ 新增：鎖定更多功能按鈕 (配送中不能亂跑)
            mBtnMoreFunctions.setEnabled(enabled);

            float alpha = enabled ? 1.0f : 0.5f;
            mBtnStart.setAlpha(alpha);
            mBtnFan.setAlpha(alpha);
            mBtnWashroom.setAlpha(alpha);
            mBtnMoreFunctions.setAlpha(alpha);
        });
    }

    // ... (Modbus, resetSelection, attemptAutoCloseAndStart 等保持原樣) ...

    private void attemptAutoCloseAndStart() {
        Toast.makeText(getContext(), "準備出發：正在自動關閉艙門...", Toast.LENGTH_SHORT).show();
        controlElectricDoor(Definition.CAN_DOOR_ALL_CLOSE);
        mHandler.postDelayed(() -> {
            checkDoorsAndAction(() -> startDeliverySequence());
        }, 3000);
    }

    private void selectLocation(String locName) {
        if (mDestName1 == null) {
            mDestName1 = locName;
            mTvDest1.setText(mDestName1);
            mTvDest1.setTextColor(0xFF00FF00);
        } else if (mDestName2 == null) {
            mDestName2 = locName;
            mTvDest2.setText(mDestName2);
            mTvDest2.setTextColor(0xFF00FF00);
        } else {
            Toast.makeText(getContext(), "艙位已滿", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetSelection() {
        stopBuzzerLoop();
        mDestName1 = null;
        mDestName2 = null;
        mTvDest1.setText("尚未設定");
        mTvDest1.setTextColor(0xFFFFFFFF);
        mTvDest2.setText("尚未設定");
        mTvDest2.setTextColor(0xFFFFFFFF);
        mReturnList.clear();
        setButtonsEnabled(true);
    }

    private void showRetryDialog() {
        if (getActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("⚠️ 安全警告");
        builder.setMessage("艙門未關閉，重新嘗試關門中...");
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();
        dialog.show();
        new Handler(Looper.getMainLooper()).postDelayed(dialog::dismiss, 2000);
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
                    CanElectricDoorBean doorBean = GsonUtil.fromJson(message, CanElectricDoorBean.class);
                    if (doorBean != null &&
                            doorBean.getDoor1() == Definition.CAN_DOOR_STATUS_CLOSE &&
                            doorBean.getDoor2() == Definition.CAN_DOOR_STATUS_CLOSE &&
                            doorBean.getDoor3() == Definition.CAN_DOOR_STATUS_CLOSE &&
                            doorBean.getDoor4() == Definition.CAN_DOOR_STATUS_CLOSE) {
                        isSafe = true;
                    }
                }
                if (isSafe && getActivity() != null) getActivity().runOnUiThread(actionTask);
                else showDoorWarning();
            }
        });
    }

    private void showDoorWarning() {
        if (getActivity() != null) getActivity().runOnUiThread(() -> {
            new AlertDialog.Builder(getContext()).setTitle("無法出發").setMessage("艙門未關緊！").setPositiveButton("好", null).show();
        });
    }

    private void showToastOnUI(String msg) {
        if (getActivity() != null) getActivity().runOnUiThread(() -> Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show());
    }

    // Modbus 相關方法 (保持原樣)
    private void startBuzzerLoop(String locationName) {
        String targetIp = null;
        if (locationName.contains("風機")) targetIp = BUZZER_IP_1;
        else if (locationName.contains("洗手間")) targetIp = BUZZER_IP_2;

        if (targetIp == null) return;
        stopBuzzerLoop();
        final String finalIp = targetIp;

        mCurrentBeepTask = new Runnable() {
            @Override
            public void run() {
                executeOneShotBeep(finalIp);
                mHandler.postDelayed(this, 30 * 1000);
            }
        };
        mHandler.post(mCurrentBeepTask);
    }

    private void stopBuzzerLoop() {
        if (mCurrentBeepTask != null) {
            mHandler.removeCallbacks(mCurrentBeepTask);
            mCurrentBeepTask = null;
        }
    }

    private void executeOneShotBeep(String targetIp) {
        new Thread(() -> {
            if (!sendModbusCommand(targetIp, true)) return;
            try { Thread.sleep(6000); } catch (InterruptedException e) { e.printStackTrace(); }
            sendModbusCommand(targetIp, false);
        }).start();
    }

    private boolean sendModbusCommand(String ip, boolean turnOn) {
        TCPMasterConnection conn = null;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            conn = new TCPMasterConnection(addr);
            conn.setPort(Modbus.DEFAULT_PORT);
            conn.setTimeout(3000);
            conn.connect();
            ModbusTCPTransaction tx = new ModbusTCPTransaction(conn);
            if (turnOn) {
                WriteSingleRegisterRequest reqArm = new WriteSingleRegisterRequest(0, new SimpleRegister(1));
                reqArm.setUnitID(1);
                tx.setRequest(reqArm);
                tx.execute();
            }
            WriteSingleRegisterRequest reqSwitch = new WriteSingleRegisterRequest(1, new SimpleRegister(turnOn ? 1 : 0));
            reqSwitch.setUnitID(1);
            tx.setRequest(reqSwitch);
            tx.execute();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null && conn.isConnected()) conn.close();
        }
    }

    private class NavListener extends ActionListener {
        private int targetPhase;
        public NavListener(int phase) { this.targetPhase = phase; }
        @Override
        public void onResult(int status, String response) throws RemoteException {
            if (status == Definition.RESULT_OK && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (targetPhase == 1) processStage(2);
                    else if (targetPhase == 2) processStage(4);
                    else if (targetPhase == 3) processStage(6);
                });
            }
        }
        @Override
        public void onError(int errorCode, String errorString) throws RemoteException {}
        @Override
        public void onStatusUpdate(int status, String data) {}
    }
    // ==========================================
    // 新增：讓機器人說話 (TTS)
    // ==========================================
    private void speak(String text) {
        // 方法 B: 不說話，直接發出 "嗶--" 的長音 (持續 1 秒)
        try {
            ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3000);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}