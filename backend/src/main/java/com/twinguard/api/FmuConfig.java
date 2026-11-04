package com.twinguard.api;

import com.twinguard.fmu.FmuRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FmuConfig {

    @Bean
    public FmuRegistry fmuRegistry() {
        return new FmuRegistry(FmuRegistry.defaultFolder());
    }
}
