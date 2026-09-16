package com.inventory.service;

import com.inventory.model.StockItem;
import com.inventory.model.Warehouse;
import com.inventory.repository.StockItemRepository;
import com.inventory.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockService {

    private final StockItemRepository stockItemRepository;
    private final WarehouseRepository warehouseRepository;

    @Transactional(readOnly = true)
    public Page<StockItem> listItems(Pageable pageable) {
        return stockItemRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public StockItem getByProductId(String productId) {
        return stockItemRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found in inventory: " + productId));
    }

    @Transactional
    public StockItem upsertStock(String productId, Long warehouseId, int quantity) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("Warehouse not found: " + warehouseId));
        StockItem item = stockItemRepository.findByProductId(productId).orElse(null);
        if (item == null) {
            item = StockItem.builder()
                    .productId(productId)
                    .warehouse(warehouse)
                    .quantity(quantity)
                    .reservedQuantity(0)
                    .build();
        } else {
            item.setQuantity(item.getQuantity() + quantity);
            item.setWarehouse(warehouse);
        }
        return stockItemRepository.save(item);
    }

    @Transactional
    public void restock(String productId, int quantity) {
        StockItem item = stockItemRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found in inventory: " + productId));
        item.setQuantity(item.getQuantity() + quantity);
    }

    @Transactional
    public boolean reserveStock(String productId, int quantity) {
        StockItem item = stockItemRepository.findByProductIdForUpdate(productId)
                .orElse(null);
        if (item == null) {
            return false;
        }
        if (item.getAvailableQuantity() < quantity) {
            return false;
        }
        item.setReservedQuantity(item.getReservedQuantity() + quantity);
        return true;
    }

    @Transactional
    public void releaseStock(String productId, int quantity) {
        StockItem item = stockItemRepository.findByProductIdForUpdate(productId)
                .orElse(null);
        if (item == null) {
            return;
        }
        int released = Math.min(item.getReservedQuantity(), quantity);
        item.setReservedQuantity(item.getReservedQuantity() - released);
    }

    @Transactional
    public Warehouse createWarehouse(String name, String location) {
        return warehouseRepository.save(Warehouse.builder()
                .name(name)
                .location(location)
                .build());
    }

    @Transactional(readOnly = true)
    public Page<Warehouse> listWarehouses(Pageable pageable) {
        return warehouseRepository.findAll(pageable);
    }
}