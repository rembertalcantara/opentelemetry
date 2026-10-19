package io.github.rembertalcantara.otel.inventory;

import io.github.rembertalcantara.otel.inventory.InventoryService.InsufficientStockException;
import io.github.rembertalcantara.otel.inventory.InventoryService.Reservation;
import io.github.rembertalcantara.otel.inventory.InventoryService.UnknownSkuException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    public Map<String, Integer> levels() {
        return inventory.levels();
    }

    @GetMapping("/{sku}")
    public Map<String, Object> level(@PathVariable String sku) {
        return Map.of("sku", sku, "level", inventory.level(sku));
    }

    @PostMapping("/{sku}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public Reservation reserve(@PathVariable String sku, @RequestParam int quantity) {
        return inventory.reserve(sku, quantity);
    }

    @ExceptionHandler(UnknownSkuException.class)
    ProblemDetail unknownSku(UnknownSkuException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficientStock(InsufficientStockException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
