package com.gathin.tvhome;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dadb.AdbKeyPair;
import dadb.AdbStream;
import dadb.Dadb;

/**
 * An ADB connection from the stick to its own adbd (Settings > My Fire TV > Developer options > ADB debugging),
 * so this app can run what a PC would: pm disable-user, pm grant, settings put. The shell user may do these; an app
 * may not. The first connection makes Fire OS ask to allow this app's key: pick "Always allow".
 * Blocking: call from a background thread.
 */
final class LocalAdb implements AutoCloseable {

    static final int PORT = 5555;
    /** Long enough to find the remote and accept the key prompt on the TV. */
    private static final int SOCKET_TIMEOUT_MS = 90_000;
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final String EXIT_MARK = "__CYF_EXIT:";

    static final class Result {
        final String output;
        final int exitCode;

        Result(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }
    }

    private final Dadb dadb;
    final String host;

    private LocalAdb(Dadb dadb, String host) {
        this.dadb = dadb;
        this.host = host;
    }

    /** Tries 127.0.0.1, then the stick's own Wi-Fi address; throws with every attempt's reason. */
    static LocalAdb connect(Context context) throws IOException {
        AdbKeyPair keys = keys(context);
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");
        String wifi = wifiAddress(context);
        if (!wifi.isEmpty()) hosts.add(wifi);
        StringBuilder reasons = new StringBuilder();
        for (String host : hosts) {
            Dadb candidate = null;
            try {
                candidate = Dadb.create(host, PORT, keys, CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS);
                LocalAdb adb = new LocalAdb(candidate, host);
                if (adb.shell("echo ok").output.trim().equals("ok")) return adb;
                reasons.append(host).append(": risposta inattesa\n");
            } catch (Exception e) {
                reasons.append(host).append(": ").append(e.getClass().getSimpleName())
                        .append(e.getMessage() == null ? "" : " " + e.getMessage()).append('\n');
            }
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Exception ignored) {
                }
            }
        }
        throw new IOException(reasons.toString().trim());
    }

    /** Runs command in a plain shell: (stdout+stderr, exit code). Plain "shell:" works on every adbd, shell v2 or not. */
    Result shell(String command) throws IOException {
        AdbStream stream = dadb.open("shell:" + command + " 2>&1; echo " + EXIT_MARK + "$?");
        String text;
        try {
            text = stream.getSource().readUtf8().replace("\r\n", "\n");
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
        int mark = text.lastIndexOf(EXIT_MARK);
        if (mark < 0) return new Result(text, -1);
        int code;
        try {
            code = Integer.parseInt(text.substring(mark + EXIT_MARK.length()).trim());
        } catch (NumberFormatException e) {
            code = -1;
        }
        return new Result(text.substring(0, mark), code);
    }

    @Override
    public void close() {
        try {
            dadb.close();
        } catch (Exception ignored) {
        }
    }

    /** This app's own ADB key, made once and kept in its private storage. */
    private static AdbKeyPair keys(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "adb");
        File privateKey = new File(dir, "adbkey");
        File publicKey = new File(dir, "adbkey.pub");
        if (!privateKey.isFile() || !publicKey.isFile()) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            AdbKeyPair.generate(privateKey, publicKey);
        }
        return AdbKeyPair.read(privateKey, publicKey);
    }

    /** The stick's own address on Wi-Fi ("" without one), for adbd builds that refuse loopback. */
    static String wifiAddress(Context context) {
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        if (wifi == null) return "";
        WifiInfo info = wifi.getConnectionInfo();
        int ip = info == null ? 0 : info.getIpAddress();
        if (ip == 0) return "";
        return String.format(Locale.ROOT, "%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }
}
