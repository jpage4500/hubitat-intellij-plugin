package com.jpage4500.hubitat.utils;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class NetworkUtils {
    private static final Logger log = LoggerFactory.getLogger(NetworkUtils.class);

    public static String getRequest(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int status = conn.getResponseCode();
            log.debug("getRequest: " + urlStr + ", http:" + status);
            if (status == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            } else {
                log.error("getRequest: invalid response from hub: " + status);
            }
        } catch (Exception e) {
            log.error("getRequest: error connecting to hub: " + urlStr + ", " + e.getMessage());
        }
        return null;
    }

    public static Pair<Integer, String> postRequest(String urlStr, String text) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "text/plain; utf-8");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            // Send body
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = text.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int status = conn.getResponseCode();
            log.debug("postRequest: " + urlStr + ", http:" + status);
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line.trim());
                }
                return Pair.of(status, response.toString());
            }
        } catch (Exception e) {
            log.error("postRequest: error connecting to hub: " + urlStr + ", " + e.getMessage());
        }
        return null;
    }
}
