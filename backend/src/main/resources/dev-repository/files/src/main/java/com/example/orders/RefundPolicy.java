package com.example.orders;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

@Component
public class RefundPolicy {

    static final int REFUND_WINDOW_DAYS = 14;

    public boolean allows(Order order) {
        return "DELIVERED".equals(order.getStatus()) && order.getDeliveredOn() != null
                && !order.getDeliveredOn().plusDays(REFUND_WINDOW_DAYS).isBefore(LocalDate.now());
    }
}
