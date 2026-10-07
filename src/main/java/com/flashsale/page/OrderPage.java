package com.flashsale.page;

import java.util.concurrent.ThreadLocalRandom;

import com.flashsale.inventory.Admission;
import com.flashsale.inventory.Attempt;
import com.flashsale.inventory.OrderOutcome;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OrderPage {

	private final Admission admission;

	public OrderPage(Admission admission) {
		this.admission = admission;
	}

	@GetMapping("/")
	public String form() {
		return "order";
	}

	@PostMapping("/")
	public String order(@RequestParam String userId, @RequestParam int quantity, Model model) {
		String message = shopperOutcome(userId, quantity).message();
		model.addAttribute("userId", userId);
		model.addAttribute("quantity", quantity);
		model.addAttribute("outcome", message);
		model.addAttribute("tone", tone(message));
		return "order";
	}

	private static String tone(String message) {
		if (message == null) {
			return "bad";
		}
		return switch (message) {
			case "The order is in process" -> "go";
			case "Wait one hour before ordering again" -> "wait";
			case "The order is refused" -> "stop";
			case "The item has sold out" -> "empty";
			default -> "bad";
		};
	}

	private OrderOutcome shopperOutcome(String userId, int quantity) {
		while (true) {
			Attempt attempt = admission.admit(userId, quantity);
			if (attempt instanceof Attempt.Shopper shopper) {
				return shopper.outcome();
			}
			jitter();
		}
	}

	private static void jitter() {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(10, 50));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("order retry interrupted", ex);
		}
	}

}
