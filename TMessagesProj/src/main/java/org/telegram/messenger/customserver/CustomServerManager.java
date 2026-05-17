package org.telegram.messenger.customserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Менеджер динамических адресов DC.
//
// Зачем он мне: чтобы при смене IP сервера не пересобирать клиент. Тяну адреса
// серверов с https://api.opengra.me/v1/config и применяю их ДО первого MTProto-коннекта
// через уже готовый публичный API ConnectionsManager.applyDatacenterAddress(...).
//
// Важно: RSA-ключ opengram уже вшит при сборке (нативные плейсхолдеры в
// Datacenter.cpp/Handshake.cpp). Здесь я ключ НЕ трогаю — только IP:port.
//
// Порядок fallback'ов при недоступности конфиг-сервера:
//   свежий конфиг с сервера -> кэш в SharedPreferences -> хардкод bootstrap.
public class CustomServerManager {

    private static final String TAG = "CustomServerManager";

    // Периодическое обновление после успешной загрузки конфига.
    private static final int REFRESH_INTERVAL_SEC = 300;
    // Частый повтор, пока начальная загрузка не удалась.
    private static final int RETRY_INTERVAL_SEC = 15;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    // База конфиг-сервера и путь. Полный URL = CONFIG_BASE_URL + CONFIG_PATH.
    private static final String CONFIG_BASE_URL = "https://api.opengra.me";
    private static final String CONFIG_PATH = "/v1/config";

    private static final String PREFS_NAME = "custom_server_cache";
    private static final String PREFS_KEY_CONFIG = "cached_config_v1";

    // Хардкод bootstrap — тот же адрес, что в нативном initDatacenters()
    // (ConnectionsManager.cpp). Это последний рубеж, если нет ни сети, ни кэша.
    private static final String BOOTSTRAP_CONFIG_JSON = "{" +
        "\"version\":1," +
        "\"datacenters\":[" +
            "{\"id\":1,\"addresses\":[{\"ip\":\"51.250.119.114\",\"port\":4430}]}," +
            "{\"id\":2,\"addresses\":[{\"ip\":\"51.250.119.114\",\"port\":4430}]}," +
            "{\"id\":3,\"addresses\":[{\"ip\":\"51.250.119.114\",\"port\":4430}]}," +
            "{\"id\":4,\"addresses\":[{\"ip\":\"51.250.119.114\",\"port\":4430}]}," +
            "{\"id\":5,\"addresses\":[{\"ip\":\"51.250.119.114\",\"port\":4430}]}" +
        "]" +
    "}";

    public interface Callback {
        void onSuccess(CustomDcConfig config);
        void onError(Exception e);
    }

    private static volatile CustomServerManager instance;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CustomServerManager");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // refreshTask — периодическое обновление после успешной загрузки.
    private volatile ScheduledFuture<?> refreshTask;
    // retryTask — частые повторы, пока начальная загрузка не удалась.
    private volatile ScheduledFuture<?> retryTask;

    private volatile CustomDcConfig lastConfig;
    // true = конфиг хотя бы раз успешно загружен с сервера в этой сессии.
    private final AtomicBoolean configLoaded = new AtomicBoolean(false);

    private CustomServerManager() {
    }

    public static CustomServerManager getInstance() {
        if (instance == null) {
            synchronized (CustomServerManager.class) {
                if (instance == null) {
                    instance = new CustomServerManager();
                }
            }
        }
        return instance;
    }

    // ============================================================
    // Публичное API
    // ============================================================

    // Асинхронный старт (не блокирует). Сначала применяю bootstrap для мгновенного
    // соединения, затем фетчу свежий конфиг в фоне.
    public void start() {
        Log.d(TAG, "Запуск менеджера динамических адресов DC");
        configLoaded.set(false);
        applyBootstrapConfig();
        executor.execute(this::initConfig);
    }

    // Синхронный старт — блокирую вызывающий поток максимум на timeoutMs мс.
    // Вызываю это при инициализации приложения ПЕРЕД первым ConnectionsManager,
    // чтобы первый MTProto-коннект пошёл уже на актуальные адреса с конфиг-сервера.
    //
    // Сетевые таймауты беру укороченными (timeoutMs/2), чтобы суммарно уложиться
    // в бюджет и не словить ANR. Если не успел — применяю bootstrap как страховку.
    public void startSync(long timeoutMs) {
        Log.d(TAG, "Синхронный старт менеджера DC (timeout=" + timeoutMs + "ms)");
        configLoaded.set(false);
        int netTimeout = (int) Math.max(500, timeoutMs / 2);
        Future<?> future = executor.submit(() -> {
            boolean fetched = fetchConfigSync(CONFIG_BASE_URL, netTimeout, netTimeout);
            if (fetched) {
                onConfigLoadedSuccessfully();
            } else {
                applyCachedConfig();
                scheduleRetry();
            }
        });
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Не уложился в бюджет — применяю bootstrap, чтобы коннект всё равно пошёл.
            Log.w(TAG, "Sync-старт: таймаут (" + timeoutMs + "ms), применяю bootstrap");
            applyBootstrapConfig();
        }
    }

    public void stop() {
        cancelRetry();
        cancelRefresh();
        configLoaded.set(false);
        Log.d(TAG, "Менеджер динамических адресов DC остановлен");
    }

    public void restart() {
        stop();
        start();
    }

    public void fetchAndApply(Callback callback) {
        executor.execute(() -> {
            boolean ok = fetchConfigSync(CONFIG_BASE_URL);
            if (ok) {
                if (callback != null) mainHandler.post(() -> callback.onSuccess(lastConfig));
            } else {
                if (callback != null) mainHandler.post(() -> callback.onError(new Exception("Fetch failed")));
            }
        });
    }

    public CustomDcConfig getLastConfig() {
        return lastConfig;
    }

    // ============================================================
    // Внутренняя логика инициализации
    // ============================================================

    // Крутится в потоке executor при асинхронном старте.
    // 1. Пробую получить конфиг с сервера.
    // 2. При неудаче — применяю кэш (или bootstrap) и запускаю retry.
    // 3. После успеха — запускаю периодический refresh каждые 300с.
    private void initConfig() {
        boolean fetched = fetchConfigSync(CONFIG_BASE_URL);
        if (fetched) {
            onConfigLoadedSuccessfully();
        } else {
            applyCachedConfig();
            scheduleRetry();
        }
    }

    // Вызываю после успешной загрузки конфига (первичной или повторной).
    private void onConfigLoadedSuccessfully() {
        configLoaded.set(true);
        cancelRetry();
        scheduleRefresh();
    }

    // ============================================================
    // Retry и Refresh
    // ============================================================

    // Короткий retry каждые 15с — пока конфиг не получен с сервера.
    // Как только получен — отменяю retry и запускаю обычный refresh.
    private void scheduleRetry() {
        if (retryTask != null && !retryTask.isDone()) return;
        Log.d(TAG, "Повтор загрузки конфига через " + RETRY_INTERVAL_SEC + "с");
        retryTask = executor.scheduleAtFixedRate(() -> {
            if (configLoaded.get()) {
                cancelRetry();
                return;
            }
            boolean ok = fetchConfigSync(CONFIG_BASE_URL);
            if (ok) {
                onConfigLoadedSuccessfully();
            }
        }, RETRY_INTERVAL_SEC, RETRY_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    // Периодическое обновление каждые 300с после успешной загрузки.
    private void scheduleRefresh() {
        if (refreshTask != null && !refreshTask.isDone()) return;
        refreshTask = executor.scheduleAtFixedRate(
                () -> fetchAndApply(null),
                REFRESH_INTERVAL_SEC,
                REFRESH_INTERVAL_SEC,
                TimeUnit.SECONDS
        );
    }

    private void cancelRetry() {
        if (retryTask != null) {
            retryTask.cancel(false);
            retryTask = null;
        }
    }

    private void cancelRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }

    // ============================================================
    // Загрузка конфига
    // ============================================================

    // Синхронный HTTP-запрос конфига. Вызываю только из потока executor.
    private boolean fetchConfigSync(String base) {
        return fetchConfigSync(base, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private boolean fetchConfigSync(String base, int connectMs, int readMs) {
        try {
            String configUrl = base.endsWith("/")
                    ? base.substring(0, base.length() - 1) + CONFIG_PATH
                    : base + CONFIG_PATH;
            Log.d(TAG, "Запрос конфига: " + configUrl);
            URL url = new URL(configUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            String rawJson = sb.toString();
            CustomDcConfig config = CustomDcConfig.fromJson(new JSONObject(rawJson));
            // Если версия не изменилась — повторно адреса не применяю.
            if (lastConfig != null && config.version == lastConfig.version) {
                Log.d(TAG, "Конфиг v" + config.version + " актуален, переприменение не требуется");
                return true;
            }
            lastConfig = config;
            saveConfigToCache(rawJson);
            applyConfig(config);
            Log.d(TAG, "Конфиг v" + config.version + " получен: " + config.datacenters.size() + " DC");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Не удалось получить конфиг: " + e.getMessage(), e);
            return false;
        }
    }

    // Применяю кэшированный конфиг (если есть), иначе хардкод bootstrap.
    private void applyCachedConfig() {
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(PREFS_KEY_CONFIG, null);
            if (json == null) {
                Log.d(TAG, "Кэш отсутствует — применяю bootstrap");
                applyBootstrapConfig();
                return;
            }
            Log.d(TAG, "Применяю кэшированный конфиг");
            CustomDcConfig config = CustomDcConfig.fromJson(new JSONObject(json));
            lastConfig = config;
            applyConfig(config);
            Log.d(TAG, "Кэш v" + config.version + " применён (" + config.datacenters.size() + " DC)");
        } catch (Exception e) {
            Log.w(TAG, "Не смог применить кэш, ухожу в bootstrap", e);
            applyBootstrapConfig();
        }
    }

    // Применяю хардкод bootstrap. Это адреса по умолчанию, совпадают с нативным
    // initDatacenters(). Дальше retry/refresh подтянут актуальные адреса с сервера.
    private void applyBootstrapConfig() {
        try {
            CustomDcConfig bootstrap = CustomDcConfig.fromJson(new JSONObject(BOOTSTRAP_CONFIG_JSON));
            applyConfig(bootstrap);
            Log.d(TAG, "Bootstrap-конфиг применён (v" + bootstrap.version + ")");
        } catch (Exception e) {
            Log.w(TAG, "applyBootstrapConfig упал", e);
        }
    }

    // ============================================================
    // Применение конфига к ConnectionsManager
    // ============================================================

    // Раскатываю адреса по всем DC. Для каждого DC беру ПЕРВЫЙ не-ipv6 адрес и
    // зову готовый публичный API applyDatacenterAddress(dcId, ip, port) для
    // аккаунта 0 и всех активированных. Ключи тут не трогаю — они вшиты нативно.
    // applyDatacenterAddress сам выставит нативный customServerMode = true, чтобы
    // help.getConfig потом не перезатёр эти адреса.
    private void applyConfig(CustomDcConfig config) {
        for (CustomDcConfig.DcEntry dc : config.datacenters) {
            for (CustomDcConfig.AddressEntry addr : dc.addresses) {
                if (!addr.ipv6) {
                    ConnectionsManager.getInstance(0).applyDatacenterAddress(dc.id, addr.ip, addr.port);
                    int extraAccounts = 0;
                    for (int account = 1; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                        if (UserConfig.getInstance(account).isClientActivated()) {
                            ConnectionsManager.getInstance(account).applyDatacenterAddress(dc.id, addr.ip, addr.port);
                            extraAccounts++;
                        }
                    }
                    Log.d(TAG, "DC" + dc.id + " -> " + addr.ip + ":" + addr.port +
                           " (аккаунтов: " + (1 + extraAccounts) + ")");
                    break;
                }
            }
        }

        // Слегка отложенно дёргаю checkConnection, чтобы переподключиться на
        // свежие адреса. applyDatacenterAddress асинхронный, поэтому даю ему фору.
        mainHandler.postDelayed(() -> {
            int active = 0;
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (account == 0 || UserConfig.getInstance(account).isClientActivated()) {
                    ConnectionsManager.getInstance(account).checkConnection();
                    active++;
                }
            }
            Log.d(TAG, "Переподключение инициировано (" + active + " акк.)");
        }, 300);
    }

    // ============================================================
    // Кэш
    // ============================================================

    private void saveConfigToCache(String rawJson) {
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREFS_KEY_CONFIG, rawJson)
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "Не смог сохранить конфиг в кэш", e);
        }
    }
}
