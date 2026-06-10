package com.ailux.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ModelInfo {

    private String id;
    private String provider;
    private String name;

    @JsonProperty("is_default")
    private boolean isDefault;

    public ModelInfo(String id, String provider, String name, boolean isDefault) {
        this.id = id;
        this.provider = provider;
        this.name = name;
        this.isDefault = isDefault;
    }

    public String getId() { return id; }
    public String getProvider() { return provider; }
    public String getName() { return name; }
    public boolean isDefault() { return isDefault; }
}
