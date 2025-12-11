package com.ainirobot.robotos.fragment;

import android.content.Context;
import android.content.SharedPreferences;

public class PasswordManager {
    private static final String PREF_NAME = "RobotPasswordConfig";
    private static final String KEY_PASSWORD = "saved_password";
    private static final String DEFAULT_PASSWORD = "123456"; // 預設密碼

    // 檢查密碼是否正確
    public static boolean checkPassword(Context context, String input) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_PASSWORD, DEFAULT_PASSWORD);
        return saved.equals(input);
    }

    // 修改密碼
    public static boolean changePassword(Context context, String oldPass, String newPass) {
        if (checkPassword(context, oldPass)) {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_PASSWORD, newPass).apply();
            return true;
        }
        return false;
    }
}