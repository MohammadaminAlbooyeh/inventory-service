package com.inventory.repository;

import com.inventory.model.StockItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockItemRepository extends JpaRepository<StockItem, Long> {

    @Query("select s from StockItem s join fetch s.warehouse where s.productId = :productId")
    Optional<StockItem> findByProductId(@Param("productId") String productId);

    @Override
    @Query("select s from StockItem s join fetch s.warehouse")
    List<StockItem> findAll();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItem s where s.productId = :productId")
    Optional<StockItem> findByProductIdForUpdate(@Param("productId") String productId);
}