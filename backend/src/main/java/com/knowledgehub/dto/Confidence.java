package com.knowledgehub.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Confidence {
    @JsonProperty("high") HIGH,
    @JsonProperty("medium") MEDIUM,
    @JsonProperty("low") LOW
}
