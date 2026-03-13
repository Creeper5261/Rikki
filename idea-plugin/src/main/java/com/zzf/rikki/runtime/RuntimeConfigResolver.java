package com.zzf.rikki.runtime;

import java.util.Map;

public interface RuntimeConfigResolver {
    RuntimeAgentConfig resolve(Map<String, ?> rawConfig);
}
