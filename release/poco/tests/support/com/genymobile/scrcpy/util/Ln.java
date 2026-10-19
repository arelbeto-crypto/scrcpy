package com.genymobile.scrcpy.util;

public final class Ln {
    public static void i(String message) { }
    public static void w(String message) { }
    public static void e(String message, Throwable error) { throw new AssertionError(message, error); }
}
