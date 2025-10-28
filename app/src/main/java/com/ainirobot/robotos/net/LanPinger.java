package com.ainirobot.robotos.net;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import android.util.Log;

public class LanPinger {
    private static final String TAG = "LanPinger";

    public static boolean ping(String ip, int timeoutMs) {
        try {
            String cmd = String.format("ping -c 1 -W %d %s", Math.max(1, timeoutMs/1000), ip);
            Process p = Runtime.getRuntime().exec(cmd);
            int exit = p.waitFor();
            if (exit == 0) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) Log.d(TAG, line);
                } catch (Exception ignored) {}
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "system ping failed: " + e.getMessage());
        }

        try {
            return InetAddress.getByName(ip).isReachable(timeoutMs);
        } catch (Exception e) {
            Log.e(TAG, "isReachable failed: " + e.getMessage());
            return false;
        }
    }
}
