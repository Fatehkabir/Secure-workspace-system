package com.examworkspace.ui;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;

public final class WindowsTaskbarHelper {

    private static final int SW_HIDE = 0;
    private static final int SW_SHOW = 5;

    private WindowsTaskbarHelper() {}

    public static void hideTaskbar() {
        setTaskbarVisible(false);
    }

    public static void showTaskbar() {
        setTaskbarVisible(true);
    }

    private static void setTaskbarVisible(boolean visible) {
        try {
            int cmd = visible ? SW_SHOW : SW_HIDE;
            User32 user32 = User32.INSTANCE;

            // Primary taskbar
            HWND primary = user32.FindWindow("Shell_TrayWnd", null);
            if (primary != null) {
                user32.ShowWindow(primary, cmd);
            }

            // Secondary taskbars (multi-monitor setups)
            HWND secondary = user32.FindWindow("Shell_SecondaryTrayWnd", null);
            while (secondary != null) {
                user32.ShowWindow(secondary, cmd);
                secondary = user32.FindWindowEx(null, secondary, "Shell_SecondaryTrayWnd", null);
            }

            // Start button (on older Windows / some shell variants)
            HWND startBtn = user32.FindWindow("Button", null);
            if (startBtn != null) {
                user32.ShowWindow(startBtn, cmd);
            }
        } catch (Throwable t) {
            System.err.println("[WindowsTaskbarHelper] Could not update taskbar visibility: " + t.getMessage());
        }
    }
}