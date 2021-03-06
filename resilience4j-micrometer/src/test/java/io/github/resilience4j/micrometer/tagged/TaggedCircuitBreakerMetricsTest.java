/*
 * Copyright 2019 Yevhenii Voievodin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.micrometer.tagged;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.resilience4j.micrometer.tagged.MetricsTestHelper.findGaugeByKindAndNameTags;
import static io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics.MetricNames.DEFAULT_CIRCUIT_BREAKER_BUFFERED_CALLS;
import static io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics.MetricNames.DEFAULT_CIRCUIT_BREAKER_CALLS_METRIC_NAME;
import static io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics.MetricNames.DEFAULT_CIRCUIT_BREAKER_MAX_BUFFERED_CALLS;
import static io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics.MetricNames.DEFAULT_CIRCUIT_BREAKER_STATE_METRIC_NAME;
import static org.assertj.core.api.Assertions.assertThat;

public class TaggedCircuitBreakerMetricsTest {

    private MeterRegistry meterRegistry;
    private CircuitBreaker circuitBreaker;

    @Before
    public void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

        circuitBreaker = circuitBreakerRegistry.circuitBreaker("backendA");
        // record some basic stats
        circuitBreaker.onError(0, new RuntimeException("oops"));
        circuitBreaker.onSuccess(0);

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);
    }

    @Test
    public void notPermittedCallsGaugeReportsCorrespondingValue() {
        Collection<Gauge> gauges = meterRegistry.get(DEFAULT_CIRCUIT_BREAKER_CALLS_METRIC_NAME).gauges();

        Optional<Gauge> notPermitted = findGaugeByKindAndNameTags(gauges, "not_permitted", circuitBreaker.getName());
        assertThat(notPermitted).isPresent();
        assertThat(notPermitted.get().value()).isEqualTo(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls());
    }

    @Test
    public void failedCallsGaugeReportsCorrespondingValue() {
        Collection<Gauge> gauges = meterRegistry.get(DEFAULT_CIRCUIT_BREAKER_CALLS_METRIC_NAME).gauges();

        Optional<Gauge> failed = findGaugeByKindAndNameTags(gauges, "failed", circuitBreaker.getName());
        assertThat(failed).isPresent();
        assertThat(failed.get().value()).isEqualTo((circuitBreaker.getMetrics().getNumberOfFailedCalls()));
    }

    @Test
    public void successfulCallsGaugeReportsCorrespondingValue() {
        Collection<Gauge> gauges = meterRegistry.get(DEFAULT_CIRCUIT_BREAKER_CALLS_METRIC_NAME).gauges();

        Optional<Gauge> successful = findGaugeByKindAndNameTags(gauges, "successful", circuitBreaker.getName());
        assertThat(successful).isPresent();
        assertThat(successful.get().value()).isEqualTo((circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()));
    }

    @Test
    public void bufferedCallsGaugeReportsCorrespondingValue() {
        Gauge bufferedCalls = meterRegistry.get(DEFAULT_CIRCUIT_BREAKER_BUFFERED_CALLS).gauge();

        assertThat(bufferedCalls).isNotNull();
        assertThat(bufferedCalls.value()).isEqualTo((circuitBreaker.getMetrics().getNumberOfBufferedCalls()));
    }

    @Test
    public void maxBufferedCallsGaugeReportsCorrespondingValue() {
        Gauge maxBuffered = meterRegistry.get(DEFAULT_CIRCUIT_BREAKER_MAX_BUFFERED_CALLS).gauge();

        assertThat(maxBuffered).isNotNull();
        assertThat(maxBuffered.value()).isEqualTo((circuitBreaker.getMetrics().getMaxNumberOfBufferedCalls()));
        assertThat(maxBuffered.getId().getTag(TagNames.NAME)).isEqualTo(circuitBreaker.getName());
    }

    @Test
    public void stateGaugeReportsCorrespondingValue() {
        Gauge state = meterRegistry.get(DEFAULT_CIRCUIT_BREAKER_STATE_METRIC_NAME).gauge();

        assertThat(state.value()).isEqualTo(circuitBreaker.getState().getOrder());
        assertThat(state.getId().getTag(TagNames.NAME)).isEqualTo(circuitBreaker.getName());
    }

    @Test
    public void metricsAreRegisteredWithCustomName() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        circuitBreakerRegistry.circuitBreaker("backendA");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(
                TaggedCircuitBreakerMetrics.MetricNames.custom()
                        .callsMetricName("custom_calls")
                        .stateMetricName("custom_state")
                        .bufferedCallsMetricName("custom_buffered_calls")
                        .maxBufferedCallsMetricName("custom_max_buffered_calls")
                        .build(),
                circuitBreakerRegistry
        ).bindTo(meterRegistry);

        Set<String> metricNames = meterRegistry.getMeters()
                .stream()
                .map(Meter::getId)
                .map(Meter.Id::getName)
                .collect(Collectors.toSet());

        assertThat(metricNames).hasSameElementsAs(Arrays.asList(
                "custom_calls",
                "custom_state",
                "custom_buffered_calls",
                "custom_max_buffered_calls"
        ));
    }

}
