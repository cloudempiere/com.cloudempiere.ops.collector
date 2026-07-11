/******************************************************************************
 * Product: CloudEmpiere OTel Agent Extension — SQL fan-out                     *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                        *
 * This program is free software; you can redistribute it and/or modify it      *
 * under the terms version 2 of the GNU General Public License as published     *
 * by the Free Software Foundation.                                             *
 *****************************************************************************/
package com.cloudempiere.ops.otel.ext;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

/**
 * Contributes {@link SqlFanoutSpanProcessor} to the agent's tracer provider at JVM boot.
 *
 * <p>Discovered via {@code META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.
 * AutoConfigurationCustomizerProvider} when this jar is on the agent extension path
 * ({@code -Dotel.javaagent.extensions=…}). This is the only supported way to add a
 * {@code SpanProcessor} to the agent — the {@code SdkTracerProvider} is sealed after boot,
 * so the OSGi collector bundle cannot register it at runtime (ADR-001 §11.5, Option A).
 *
 * <p>Gated by {@code cloudempiere.sqlfanout.enabled} (default {@code true}) so it can be
 * switched off per tier via {@code -Dcloudempiere.sqlfanout.enabled=false} without pulling
 * the extension jar.
 *
 * @author CloudEmpiere
 */
public final class SqlFanoutExtension implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((tracerProviderBuilder, config) -> {
            boolean enabled = config.getBoolean("cloudempiere.sqlfanout.enabled", true);
            if (enabled) {
                tracerProviderBuilder.addSpanProcessor(new SqlFanoutSpanProcessor());
            }
            return tracerProviderBuilder;
        });
    }

    /**
     * Run before the batch/export processors so our attributes are set on the root before it
     * is handed to exporters. Lower value = earlier (OTel orders providers ascending).
     */
    @Override
    public int order() {
        return -100;
    }
}
