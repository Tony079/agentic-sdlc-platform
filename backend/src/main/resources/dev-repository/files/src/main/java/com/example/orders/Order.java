package com.example.orders;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A customer order. Status values: NEW, PAID, SHIPPED, DELIVERED, CANCELLED, REFUNDED. */
public class Order {

    private Long id;
    private String customerEmail;
    private BigDecimal total;
    private String status = "NEW";
    private LocalDate deliveredOn;

    public Long getId() {
        return id;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getDeliveredOn() {
        return deliveredOn;
    }
}
