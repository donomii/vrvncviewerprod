package com.praeceptamachinae.vrvncviewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

class UrlStore {
    private static final String PREFS_NAME = "vrvncviewer";
    private static final String KEY_URLS = "saved_urls";

    List<String> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_URLS, "[]");
        List<String> urls = new ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                urls.add(arr.getString(i));
            }
        } catch (JSONException e) {
            urls.clear();
        }
        return urls;
    }

    void save(Context context, List<String> urls) {
        JSONArray arr = new JSONArray();
        for (String url : urls) {
            arr.put(url);
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_URLS, arr.toString()).apply();
    }

    void add(Context context, String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        List<String> urls = load(context);
        urls.remove(url);
        urls.add(0, url);
        save(context, urls);
    }
}
