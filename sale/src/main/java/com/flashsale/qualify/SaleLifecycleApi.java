package com.flashsale.qualify;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SaleLifecycleApi {

	private final SaleScript script;
	private final CloseReport closeReport;

	SaleLifecycleApi(SaleScript script, CloseReport closeReport) {
		this.script = script;
		this.closeReport = closeReport;
	}

	@PostMapping("/sales/load")
	ResponseEntity<Void> load(@RequestBody LoadRequest request) {
		if (request.itemId() == null || request.itemId().isBlank()
				|| request.allotment() == null || request.allotment() < 0) {
			return ResponseEntity.badRequest().build();
		}
		if (!script.load(request.itemId(), request.allotment())) {
			return ResponseEntity.status(409).build();
		}
		return ResponseEntity.ok().build();
	}

	@PostMapping("/sales/start")
	@SuppressWarnings("unused")
	ResponseEntity<Void> start(@RequestBody java.util.Map<String, Object> body) {
		if (!script.start()) {
			return ResponseEntity.status(409).build();
		}
		return ResponseEntity.ok().build();
	}

	@PostMapping(value = "/sales/close", produces = "application/json")
	@SuppressWarnings("unused")
	ResponseEntity<String> close(@RequestBody java.util.Map<String, Object> body) {
		String report = script.close();
		closeReport.publish(report);
		return ResponseEntity.ok(report);
	}

	record LoadRequest(String itemId, Integer allotment) {
	}

}
