package com.flashsale.qualify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class AttemptApiTest {

	@Test
	void missingPendingDocumentStillReturnsTheScriptResult() {
		SaleScript script = mock(SaleScript.class);
		String result = "{\"answer\":\"qualified\",\"qualificationId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}";
		when(script.attempt("ada")).thenReturn(result);
		when(script.pending("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
				.thenThrow(new IllegalStateException("missing qualification document"));
		AttemptApi api = new AttemptApi(script, JsonMapper.builder().build(), new SimpleMeterRegistry());
		MockHttpServletRequest request = new MockHttpServletRequest();

		ResponseEntity<String> response = api.attempt("{\"userId\":\"ada\"}", request);

		assertEquals(200, response.getStatusCode().value());
		assertEquals(result, response.getBody());
		assertNull(request.getAttribute(AttemptApi.DOCUMENT));
	}

}
