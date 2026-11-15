package com.mfu.rbms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "items")
public class Item {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "unit", length = 32, nullable = false)
    private String unit;

    @Column(name = "amount", precision = 12, scale = 3, nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "min_stock", precision = 12, scale = 3, nullable = false)
    private BigDecimal minStock = BigDecimal.ZERO;

    @Column(name = "avg_cost", precision = 12, scale = 4, nullable = false)
    private BigDecimal avgCost = BigDecimal.ZERO;

    @Column(name = "last_alert_date")
    private LocalDate lastAlertDate;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getMinStock() {
        return minStock;
    }

    public void setMinStock(BigDecimal minStock) {
        this.minStock = minStock;
    }

    public BigDecimal getAvgCost() {
        return avgCost;
    }

    public void setAvgCost(BigDecimal avgCost) {
        this.avgCost = avgCost;
    }

    public LocalDate getLastAlertDate() {
        return lastAlertDate;
    }

    public void setLastAlertDate(LocalDate lastAlertDate) {
        this.lastAlertDate = lastAlertDate;
    }
}
