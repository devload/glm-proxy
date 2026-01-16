package com.example.glmproxy.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.semconv.ResourceAttributes
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenTelemetryConfig {

    @Value("\${spring.application.name:glm-proxy}")
    private lateinit var applicationName: String

    @Value("\${management.otlp.tracing.endpoint:http://localhost:4318/v1/traces}")
    private lateinit var otlpEndpoint: String

    @Bean
    fun openTelemetry(): OpenTelemetry {
        // Resource 설정 (서비스 정보)
        val resource = Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.builder()
                        .put(ResourceAttributes.SERVICE_NAME, applicationName)
                        .put(ResourceAttributes.SERVICE_VERSION, "1.0.0")
                        .put(ResourceAttributes.DEPLOYMENT_ENVIRONMENT, "development")
                        .build()
                )
            )

        // OTLP HTTP Exporter 설정
        val spanExporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .build()

        // TracerProvider 설정
        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
            .setSampler(Sampler.alwaysOn()) // 모든 trace 수집
            .build()

        // OpenTelemetry SDK 구성
        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal()
    }
}
