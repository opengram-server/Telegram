package org.telegram.messenger.customserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Парсер конфига серверов opengram. Тяну его с https://api.opengra.me/v1/config.
// Публичные ключи я тут сознательно НЕ разбираю: RSA-ключ уже вшит в нативную часть
// при сборке (Datacenter.cpp/Handshake.cpp), динамически его не меняю — только IP:port.
public class CustomDcConfig {

    // Версия конфига. По ней решаю, изменился ли конфиг и нужно ли переприменять.
    public int version;
    public List<DcEntry> datacenters = new ArrayList<>();

    public static class AddressEntry {
        public String ip;
        public int port;
        public boolean ipv6;
    }

    public static class DcEntry {
        public int id;
        public List<AddressEntry> addresses = new ArrayList<>();
    }

    // Разбираю JSON вида:
    // {"version":int,"datacenters":[{"id":int,"addresses":[{"ip":str,"port":int,"ipv6":bool}]}]}
    // Поле public_keys, если оно вдруг есть в ответе, я молча игнорирую.
    public static CustomDcConfig fromJson(JSONObject obj) throws Exception {
        CustomDcConfig config = new CustomDcConfig();
        config.version = obj.optInt("version", 1);

        JSONArray dcs = obj.optJSONArray("datacenters");
        if (dcs != null) {
            for (int i = 0; i < dcs.length(); i++) {
                JSONObject dc = dcs.getJSONObject(i);
                DcEntry dcEntry = new DcEntry();
                dcEntry.id = dc.getInt("id");
                JSONArray addrs = dc.optJSONArray("addresses");
                if (addrs != null) {
                    for (int j = 0; j < addrs.length(); j++) {
                        JSONObject addr = addrs.getJSONObject(j);
                        AddressEntry ae = new AddressEntry();
                        ae.ip = addr.getString("ip");
                        ae.port = addr.getInt("port");
                        ae.ipv6 = addr.optBoolean("ipv6", false);
                        dcEntry.addresses.add(ae);
                    }
                }
                config.datacenters.add(dcEntry);
            }
        }

        return config;
    }
}
