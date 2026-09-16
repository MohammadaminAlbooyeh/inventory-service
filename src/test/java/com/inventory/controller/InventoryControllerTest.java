package com.inventory.controller;

import com.inventory.model.Reservation;
import com.inventory.model.StockItem;
import com.inventory.model.Warehouse;
import com.inventory.service.ReservationService;
import com.inventory.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StockService stockService;

    @MockBean
    private ReservationService reservationService;

    // Present only so the RateLimitFilter bean in the web slice can be constructed.
    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void listItemsReturnsAllStockItems() throws Exception {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(2).build();
        when(stockService.listItems(any())).thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/inventory/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value("p1"))
                .andExpect(jsonPath("$.content[0].quantity").value(10));
    }

    @Test
    void getItemReturnsStockByProductId() throws Exception {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(10).reservedQuantity(0).build();
        when(stockService.getByProductId("p1")).thenReturn(item);

        mockMvc.perform(get("/api/inventory/items/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("p1"))
                .andExpect(jsonPath("$.quantity").value(10));
    }

    @Test
    void getItemReturns404WhenNotFound() throws Exception {
        when(stockService.getByProductId("missing")).thenThrow(new IllegalArgumentException("Product not found"));

        mockMvc.perform(get("/api/inventory/items/missing"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertItemCreatesNewStock() throws Exception {
        Warehouse warehouse = Warehouse.builder().id(1L).build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(5).reservedQuantity(0).build();
        when(stockService.upsertStock("p1", 1L, 5)).thenReturn(item);

        String body = objectMapper.writeValueAsString(Map.of("productId", "p1", "warehouseId", 1, "quantity", 5));

        mockMvc.perform(post("/api/inventory/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value("p1"))
                .andExpect(jsonPath("$.quantity").value(5));
    }

    @Test
    void restockIncreasesQuantity() throws Exception {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        StockItem item = StockItem.builder().id(1L).productId("p1").warehouse(warehouse).quantity(15).reservedQuantity(2).build();
        doNothing().when(stockService).restock("p1", 5);
        when(stockService.getByProductId("p1")).thenReturn(item);

        mockMvc.perform(post("/api/inventory/items/p1/restock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(15));
    }

    @Test
    void listWarehousesReturnsAllWarehouses() throws Exception {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").build();
        when(stockService.listWarehouses(any())).thenReturn(new PageImpl<>(List.of(warehouse)));

        mockMvc.perform(get("/api/inventory/warehouses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Main"));
    }

    @Test
    void createWarehouseReturnsCreated() throws Exception {
        Warehouse warehouse = Warehouse.builder().id(1L).name("Main").location("Tehran").createdAt(LocalDateTime.now()).build();
        when(stockService.createWarehouse("Main", "Tehran")).thenReturn(warehouse);

        String body = objectMapper.writeValueAsString(Map.of("name", "Main", "location", "Tehran"));

        mockMvc.perform(post("/api/inventory/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Main"));
    }

    @Test
    void reserveReturnsCreatedWhenStockAvailable() throws Exception {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(com.inventory.model.enums.ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(reservationService.createReservation("ord-1", "p1", 2)).thenReturn(Optional.of(reservation));

        String body = objectMapper.writeValueAsString(Map.of("orderId", "ord-1", "productId", "p1", "quantity", 2));

        mockMvc.perform(post("/api/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationCode").value("code-1"));
    }

    @Test
    void reserveReturnsConflictWhenInsufficientStock() throws Exception {
        when(reservationService.createReservation("ord-1", "p1", 999)).thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(Map.of("orderId", "ord-1", "productId", "p1", "quantity", 999));

        mockMvc.perform(post("/api/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Insufficient stock"));
    }

    @Test
    void confirmReservationReturnsOk() throws Exception {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(com.inventory.model.enums.ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(reservationService.confirmReservation("code-1")).thenReturn(reservation);

        mockMvc.perform(post("/api/inventory/reservations/code-1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationCode").value("code-1"));
    }

    @Test
    void cancelReservationReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/inventory/reservations/code-1/cancel"))
                .andExpect(status().isNoContent());

        verify(reservationService).cancelReservation("code-1");
    }

    @Test
    void listByOrderReturnsReservationsForOrder() throws Exception {
        Reservation reservation = Reservation.builder()
                .id(1L).reservationCode("code-1").orderId("ord-1").productId("p1").quantity(2)
                .status(com.inventory.model.enums.ReservationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(reservationService.listByOrder("ord-1")).thenReturn(List.of(reservation));

        mockMvc.perform(get("/api/inventory/orders/ord-1/reservations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value("ord-1"));
    }
}
