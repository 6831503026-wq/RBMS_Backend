package com.mfu.rbms.repository;

import com.mfu.rbms.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {
}
