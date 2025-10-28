package com.ainirobot.robotos.nav;

public class NavState {
    private static String lastTarget;

    public static void setLastTarget(String t) {
        lastTarget = t;
    }

    public static String getLastTarget() {
        return lastTarget;
    }
}
