package com.inventory.controller;

import com.inventory.model.Reservation;
import com.inventory.model.StockItem;
import com.inventory.model.Warehouse;
import com.inventory.service.ReservationService;
import com.inventory.service.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
@Validated
public class InventoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final StockService stockService;
    private final ReservationService reservationService;

    @GetMapping("/items")
    public PageResponse<StockItem> listItems(@RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) int size) {
        return PageResponse.of(stockService.listItems(pageRequest(page, size, "productId")));
    }

    @GetMapping("/items/{productId}")
    public StockItem getItem(@PathVariable String productId) {
        return stockService.getByProductId(productId);
    }

    @PostMapping("/items")
    public ResponseEntity<StockItem> upsertItem(@Valid @RequestBody UpsertStockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(stockService.upsertStock(request.productId, request.warehouseId, request.quantity));
    }

    @PostMapping("/items/{productId}/restock")
    public ResponseEntity<StockItem> restock(@PathVariable String productId,
                                             @RequestBody Map<String, Integer> body) {
        int quantity = body.getOrDefault("quantity", 0);
        stockService.restock(productId, quantity);
        return ResponseEntity.ok(stockService.getByProductId(productId));
    }

    @GetMapping("/warehouses")
    public PageResponse<Warehouse> listWarehouses(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) int size) {
        return PageResponse.of(stockService.listWarehouses(pageRequest(page, size, "name")));
    }

    private static PageRequest pageRequest(int page, int size, String sortBy) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by(sortBy));
    }

    @PostMapping("/warehouses")
    public ResponseEntity<Warehouse> createWarehouse(@Valid @RequestBody CreateWarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(stockService.createWarehouse(request.name, request.location));
    }

    @PostMapping("/reservations")
    public ResponseEntity<?> reserve(@Valid @RequestBody ReserveRequest request) {
        Optional<Reservation> reservation = reservationService
                .createReservation(request.orderId, request.productId, request.quantity);
        return reservation
                .<ResponseEntity<?>>map(r -> ResponseEntity.status(HttpStatus.CREATED).body(r))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Insufficient stock")));
    }

    @PostMapping("/reservations/{reservationCode}/confirm")
    public ResponseEntity<Reservation> confirm(@PathVariable String reservationCode) {
        return ResponseEntity.ok(reservationService.confirmReservation(reservationCode));
    }

    @PostMapping("/reservations/{reservationCode}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String reservationCode) {
        reservationService.cancelReservation(reservationCode);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/orders/{orderId}/reservations")
    public List<Reservation> listByOrder(@PathVariable String orderId) {
        return reservationService.listByOrder(orderId);
    }

    public record UpsertStockRequest(@NotBlank String productId,
                                     @NotNull Long warehouseId,
                                     @Min(0) int quantity) {
    }

    public record CreateWarehouseRequest(@NotBlank String name,
                                         @NotBlank String location) {
    }

    public record ReserveRequest(@NotBlank String orderId,
                                 @NotBlank String productId,
                                 @Min(1) int quantity) {
    }
}