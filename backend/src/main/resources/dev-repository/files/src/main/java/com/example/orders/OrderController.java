package com.example.orders;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public List<Order> list() {
        return orders.findAll();
    }

    @GetMapping("/{id}")
    public Order get(@PathVariable Long id) {
        return orders.find(id);
    }

    @PostMapping("/{id}/cancel")
    public Order cancel(@PathVariable Long id) {
        return orders.cancel(id);
    }

    @PostMapping("/{id}/refund")
    public Order refund(@PathVariable Long id) {
        return orders.refund(id);
    }
}
