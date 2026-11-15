package com.mfu.rbms.repository;

import com.mfu.rbms.model.ItemSupplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ItemSupplierRepository extends JpaRepository<ItemSupplier, Long> {
    @Query("SELECT link FROM ItemSupplier link " +
            "JOIN FETCH link.item item JOIN FETCH link.supplier supplier " +
            "ORDER BY item.id, link.price, supplier.name")
    List<ItemSupplier> findAllWithItemAndSupplier();
}
