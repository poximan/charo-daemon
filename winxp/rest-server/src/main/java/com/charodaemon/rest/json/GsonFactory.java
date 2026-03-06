package com.charodaemon.rest.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class GsonFactory {
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .create();

    private GsonFactory() {
    }

    public static Gson gson() {
        return GSON;
    }
}
