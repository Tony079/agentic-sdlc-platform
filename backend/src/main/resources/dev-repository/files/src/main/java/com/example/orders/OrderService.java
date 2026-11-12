package com.example.orders;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository repository;
    private final RefundPolicy refundPolicy;

    public OrderService(OrderRepository repository, RefundPolicy refundPolicy) {
        this.repository = repository;
        this.refundPolicy = refundPolicy;
    }

    public List<Order> findAll() {
        return repository.findAll();
    }

    public Order find(Long id) {
        return repository.findById(id).orElseThrow();
    }

    /** Cancels an order that has not shipped yet. No reason is recorded and nobody is notified. */
    public Order cancel(Long id) {
        Order order = find(id);
        if ("SHIPPED".equals(order.getStatus()) || "DELIVERED".equals(order.getStatus())) {
            throw new IllegalStateException("A shipped order cannot be cancelled");
        }
        order.setStatus("CANCELLED");
        return repository.save(order);
    }

    /** Refunds a delivered order when the refund policy allows it. */
    public Order refund(Long id) {
        Order order = find(id);
        if (!refundPolicy.allows(order)) {
            throw new IllegalStateException("The refund window has passed");
        }
        order.setStatus("REFUNDED");
        return repository.save(order);
    }
}
