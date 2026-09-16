package com.inventory.service;

import com.inventory.model.StockItem;
import com.inventory.model.Warehouse;
import com.inventory.repository.StockItemRepository;
import com.inventory.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private StockItemRepository stockItemRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @InjectMocks
    private StockService stockService;

    @Test
    void listItemsReturnsAllStockItems() {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(2).build();
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(stockItemRepository.findAll(pageRequest)).thenReturn(new PageImpl<>(List.of(item)));

        Page<StockItem> items = stockService.listItems(pageRequest);

        assertThat(items.getContent()).hasSize(1);
        assertThat(items.getContent().get(0).getProductId()).isEqualTo("p1");
    }

    @Test
    void getByProductIdReturnsItem() {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(0).build();
        when(stockItemRepository.findByProductId("p1")).thenReturn(Optional.of(item));

        StockItem result = stockService.getByProductId("p1");

        assertThat(result.getProductId()).isEqualTo("p1");
        assertThat(result.getQuantity()).isEqualTo(10);
    }

    @Test
    void getByProductIdThrowsWhenNotFound() {
        when(stockItemRepository.findByProductId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockService.getByProductId("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    void upsertStockCreatesNewItem() {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(stockItemRepository.findByProductId("p1")).thenReturn(Optional.empty());
        StockItem saved = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(5).reservedQuantity(0).build();
        when(stockItemRepository.save(any(StockItem.class))).thenReturn(saved);

        StockItem result = stockService.upsertStock("p1", 1L, 5);

        assertThat(result.getProductId()).isEqualTo("p1");
        assertThat(result.getQuantity()).isEqualTo(5);
        verify(stockItemRepository).save(any(StockItem.class));
    }

    @Test
    void upsertStockIncrementsQuantityWhenExists() {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        StockItem existing = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(2).build();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(stockItemRepository.findByProductId("p1")).thenReturn(Optional.of(existing));
        when(stockItemRepository.save(existing)).thenReturn(existing);

        StockItem result = stockService.upsertStock("p1", 1L, 5);

        assertThat(result.getQuantity()).isEqualTo(15);
        verify(stockItemRepository).save(existing);
    }

    @Test
    void restockIncreasesQuantity() {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(2).build();
        when(stockItemRepository.findByProductIdForUpdate("p1")).thenReturn(Optional.of(item));

        stockService.restock("p1", 5);

        assertThat(item.getQuantity()).isEqualTo(15);
    }

    @Test
    void restockThrowsWhenProductNotFound() {
        when(stockItemRepository.findByProductIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockService.restock("missing", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    void reserveStockSucceedsWhenEnoughStock() {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(0).build();
        when(stockItemRepository.findByProductIdForUpdate("p1")).thenReturn(Optional.of(item));

        boolean result = stockService.reserveStock("p1", 5);

        assertThat(result).isTrue();
        assertThat(item.getReservedQuantity()).isEqualTo(5);
    }

    @Test
    void reserveStockFailsWhenInsufficientStock() {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(3).reservedQuantity(0).build();
        when(stockItemRepository.findByProductIdForUpdate("p1")).thenReturn(Optional.of(item));

        boolean result = stockService.reserveStock("p1", 5);

        assertThat(result).isFalse();
        assertThat(item.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void reserveStockFailsWhenProductNotFound() {
        when(stockItemRepository.findByProductIdForUpdate("missing")).thenReturn(Optional.empty());

        boolean result = stockService.reserveStock("missing", 5);

        assertThat(result).isFalse();
    }

    @Test
    void releaseStockDecreasesReservedQuantity() {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(5).build();
        when(stockItemRepository.findByProductIdForUpdate("p1")).thenReturn(Optional.of(item));

        stockService.releaseStock("p1", 3);

        assertThat(item.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    void releaseStockDoesNotGoBelowZero() {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(2).build();
        when(stockItemRepository.findByProductIdForUpdate("p1")).thenReturn(Optional.of(item));

        stockService.releaseStock("p1", 5);

        assertThat(item.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void releaseStockDoesNothingWhenProductNotFound() {
        when(stockItemRepository.findByProductIdForUpdate("missing")).thenReturn(Optional.empty());

        stockService.releaseStock("missing", 5);

        verify(stockItemRepository, never()).save(any());
    }

    @Test
    void createWarehouseSavesWarehouse() {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        when(warehouseRepository.save(any(Warehouse.class))).thenReturn(warehouse);

        Warehouse result = stockService.createWarehouse("Main", "Tehran");

        assertThat(result.getName()).isEqualTo("Main");
        assertThat(result.getLocation()).isEqualTo("Tehran");
    }

    @Test
    void listWarehousesReturnsAllWarehouses() {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        PageRequest pageRequest = PageRequest.of(0, 20);
        when(warehouseRepository.findAll(pageRequest)).thenReturn(new PageImpl<>(List.of(warehouse)));

        Page<Warehouse> warehouses = stockService.listWarehouses(pageRequest);

        assertThat(warehouses.getContent()).hasSize(1);
        assertThat(warehouses.getContent().get(0).getName()).isEqualTo("Main");
    }
}
