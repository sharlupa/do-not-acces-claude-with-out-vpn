package io.github.sharlupa.claudeguard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Узнаёт страну, из которой телефон выходит в интернет (Cloudflare /cdn-cgi/trace).
 * К Claude при этом не обращается. Вызывать не в главном потоке.
 */
final class CountryCheck {
    private CountryCheck() {}

    private static final String[] URLS = {
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://1.1.1.1/cdn-cgi/trace",
            "https://one.one.one.one/cdn-cgi/trace",
    };
    private static final int TIMEOUT_MS = 5000;

    static final class Result {
        final String country;
        final String ip;

        Result(String country, String ip) {
            this.country = country;
            this.ip = ip;
        }
    }

    /** null — проверить не удалось (нет сети, всё недоступно). */
    static Result check() {
        for (String url : URLS) {
            try {
                Result r = fetch(url);
                if (r != null) return r;
            } catch (IOException ignored) {
                // пробуем следующий адрес
            }
        }
        return null;
    }

    private static Result fetch(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setUseCaches(false);
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String loc = null;
            String ip = "";
            for (String line; (line = in.readLine()) != null; ) {
                if (line.startsWith("loc=")) loc = line.substring(4).trim();
                if (line.startsWith("ip=")) ip = line.substring(3).trim();
            }
            return loc != null && loc.matches("[A-Z0-9]{2}") ? new Result(loc, ip) : null;
        } finally {
            conn.disconnect();
        }
    }
}
