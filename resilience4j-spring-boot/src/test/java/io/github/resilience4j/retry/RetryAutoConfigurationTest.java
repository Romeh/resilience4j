/*
 * Copyright 2019 Mahmoud Romeh
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
package io.github.resilience4j.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import io.github.resilience4j.circuitbreaker.IgnoredException;
import io.github.resilience4j.retry.autoconfigure.RetryProperties;
import io.github.resilience4j.retry.configure.RetryAspect;
import io.github.resilience4j.retry.monitoring.endpoint.RetryEndpointResponse;
import io.github.resilience4j.retry.monitoring.endpoint.RetryEventsEndpointResponse;
import io.github.resilience4j.service.test.RetryDummyService;
import io.github.resilience4j.service.test.TestApplication;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = TestApplication.class)
public class RetryAutoConfigurationTest {

	@Autowired
	RetryRegistry retryRegistry;


	@Autowired
	AsyncRetryRegistry asyncRetryRegistry;

	@Autowired
	RetryProperties retryProperties;

	@Autowired
	RetryAspect retryAspect;

	@Autowired
	RetryDummyService retryDummyService;

	@Autowired
	private TestRestTemplate restTemplate;

	/**
	 * The test verifies that a Retry instance is created and configured properly when the RetryDummyService is invoked and
	 * that the Retry logic is properly handled
	 */
	@Test
	public void testRetryAutoConfiguration() throws IOException {
		assertThat(retryRegistry).isNotNull();
		assertThat(retryProperties).isNotNull();
		try {
			retryDummyService.doSomething(true);
		} catch (IOException ex) {
			// Do nothing. The IOException is recorded by the retry as it is one of failure exceptions
		}
		// The invocation is recorded by the CircuitBreaker as a success.
		retryDummyService.doSomething(false);

		Retry retry = retryRegistry.retry(RetryDummyService.BACKEND_A);
		assertThat(retry).isNotNull();

		// expect retry is configured as defined in application.yml
		assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
		assertThat(retry.getName()).isEqualTo(RetryDummyService.BACKEND_A);
		assertThat(retry.getRetryConfig().getExceptionPredicate().test(new IOException())).isTrue();

		assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(0);
		assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
		assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(1);
		assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(0);

		// expect retry actuator endpoint contains both retries
		ResponseEntity<RetryEndpointResponse> retriesList = restTemplate.getForEntity("/retries", RetryEndpointResponse.class);
		assertThat(retriesList.getBody().getRetries()).hasSize(4).containsOnly("retryBackendA", "retryBackendB");

		// expect retry-event actuator endpoint recorded both events
		ResponseEntity<RetryEventsEndpointResponse> retryEventListA = restTemplate.getForEntity("/retries/events/retryBackendA", RetryEventsEndpointResponse.class);
		assertThat(retryEventListA.getBody().getRetryEvents()).hasSize(3);

		ResponseEntity<RetryEventsEndpointResponse> retryEventListB = restTemplate.getForEntity("/retries/events/retryBackendB", RetryEventsEndpointResponse.class);

		ResponseEntity<RetryEventsEndpointResponse> retryEventList = restTemplate.getForEntity("/retries/events", RetryEventsEndpointResponse.class);
		assertThat(retryEventList.getBody().getRetryEvents()).hasSize(3 + retryEventListB.getBody().getRetryEvents().size());

		assertThat(retry.getRetryConfig().getExceptionPredicate().test(new IOException())).isTrue();
		assertThat(retry.getRetryConfig().getExceptionPredicate().test(new IgnoredException())).isFalse();

		// expect aspect configured as defined in application.yml
		assertThat(retryAspect.getOrder()).isEqualTo(399);
	}


	/**
	 * The test verifies that a Async Retry instance is created and configured properly when the RetryDummyService is invoked and
	 * that the Async Retry logic is properly handled
	 */
	@Test
	public void testRetryAutoConfigurationAsync() throws Throwable {
		assertThat(asyncRetryRegistry).isNotNull();

		try {
			final CompletionStage<String> stringCompletionStage = retryDummyService.doSomethingAsync(true);
			String result = awaitResult(stringCompletionStage, 5);
			assertThat(result).isNull();

		} catch (IOException ex) {
			// Do nothing. The IOException is recorded by the retry as it is one of failure exceptions
			assertThat(ex.getMessage()).contains("Test Message");
		}
		// The invocation is recorded by the CircuitBreaker as a success.
		String resultSuccess = awaitResult(retryDummyService.doSomethingAsync(false), 5);
		assertThat(resultSuccess).isNotEmpty();
		AsyncRetry retry = asyncRetryRegistry.retry(RetryDummyService.BACKEND_B);
		assertThat(retry).isNotNull();

		// expect retry is configured as defined in application.yml
		assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
		assertThat(retry.getName()).isEqualTo(RetryDummyService.BACKEND_B);
		assertThat(retry.getRetryConfig().getExceptionPredicate().test(new IOException())).isTrue();

		assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isEqualTo(0);
		assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
		assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(1);
		assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(0);

		// expect retry actuator endpoint contains both retries
		ResponseEntity<RetryEndpointResponse> retriesList = restTemplate.getForEntity("/retries", RetryEndpointResponse.class);
		assertThat(retriesList.getBody().getRetries()).hasSize(4).containsOnly("retryBackendA", "retryBackendB");

		// expect retry-event actuator endpoint recorded both events
		ResponseEntity<RetryEventsEndpointResponse> retryEventListA = restTemplate.getForEntity("/retries/events/retryBackendA", RetryEventsEndpointResponse.class);

		ResponseEntity<RetryEventsEndpointResponse> retryEventListB = restTemplate.getForEntity("/retries/events/retryBackendB", RetryEventsEndpointResponse.class);
		assertThat(retryEventListB.getBody().getRetryEvents()).hasSize(3);

		ResponseEntity<RetryEventsEndpointResponse> retryEventList = restTemplate.getForEntity("/retries/events", RetryEventsEndpointResponse.class);
		assertThat(retryEventList.getBody().getRetryEvents()).hasSize(retryEventListA.getBody().getRetryEvents().size() + 3);

		assertThat(retry.getRetryConfig().getExceptionPredicate().test(new IOException())).isTrue();
		assertThat(retry.getRetryConfig().getExceptionPredicate().test(new IgnoredException())).isFalse();


		// expect aspect configured as defined in application.yml
		assertThat(retryAspect.getOrder()).isEqualTo(399);
	}

	private <T> T awaitResult(CompletionStage<T> completionStage, long timeoutSeconds) throws Throwable {
		try {
			return completionStage.toCompletableFuture().get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException | TimeoutException e) {
			throw new AssertionError(e);
		} catch (ExecutionException e) {
			throw e.getCause();
		}
	}

	private final static class HealthResponse {
		private Map<String, Object> details;

		public Map<String, Object> getDetails() {
			return details;
		}

		public void setDetails(Map<String, Object> details) {
			this.details = details;
		}
	}


}
