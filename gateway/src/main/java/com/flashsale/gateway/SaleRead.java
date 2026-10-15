package com.flashsale.gateway;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SaleRead {

	private final AttemptEdge edge;

	SaleRead(AttemptEdge edge) {
		this.edge = edge;
	}

	@GetMapping("/sale")
	Map<String, String> read() {
		return edge.read();
	}

}
