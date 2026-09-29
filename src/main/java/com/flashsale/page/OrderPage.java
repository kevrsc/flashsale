package com.flashsale.page;

import com.flashsale.inventory.Orders;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class OrderPage {

	private final Orders orders;

	public OrderPage(Orders orders) {
		this.orders = orders;
	}

	@GetMapping("/")
	public String form() {
		return "order";
	}

	@PostMapping("/")
	public String order(@RequestParam String userId, @RequestParam int quantity, Model model) {
		model.addAttribute("outcome", orders.place(userId, quantity).message());
		return "order";
	}

}
