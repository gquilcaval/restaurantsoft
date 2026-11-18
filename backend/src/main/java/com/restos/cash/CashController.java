package com.restos.cash;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Solo responsables administrativos pueden abrir, mover efectivo o cerrar cajas. */
@RestController
@RequestMapping("/api/admin/cash")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class CashController {
    private final CashService cash;
    public CashController(CashService cash){this.cash=cash;}
    @GetMapping CashService.Overview overview(){return cash.overview();}
    @GetMapping("/shifts/{id}") CashService.ShiftDetail detail(@PathVariable UUID id){return cash.detail(id);}
    @PostMapping("/open") CashService.ShiftDetail open(@Valid @RequestBody OpenRequest r){return cash.open(r.id(),r.amount(),note(r.note()));}
    @PostMapping("/shifts/{id}/movements") CashService.ShiftDetail movement(@PathVariable UUID id,@Valid @RequestBody MovementRequest r){
        return cash.movement(id,r.id(),r.kind(),r.amount(),r.reason().trim());
    }
    @PostMapping("/shifts/{id}/close") CashService.ShiftDetail close(@PathVariable UUID id,@Valid @RequestBody CloseRequest r){return cash.close(id,r.counted(),note(r.note()));}
    private String note(String value){return value==null?"":value.trim();}
    record OpenRequest(@NotNull UUID id,@NotNull @DecimalMin("0") @Digits(integer=10,fraction=2) BigDecimal amount,@Size(max=300) String note){}
    record MovementRequest(@NotNull UUID id,@NotNull @Pattern(regexp="IN|OUT") String kind,
        @NotNull @DecimalMin("0.01") @Digits(integer=10,fraction=2) BigDecimal amount,@NotBlank @Size(max=300) String reason){}
    record CloseRequest(@NotNull @DecimalMin("0") @Digits(integer=10,fraction=2) BigDecimal counted,@Size(max=300) String note){}
}
