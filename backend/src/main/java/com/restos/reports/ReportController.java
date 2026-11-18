package com.restos.reports;

import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasAnyRole('ORGANIZATION_ADMIN','BRANCH_MANAGER')")
public class ReportController {
    private final ReportService reports;
    private final BranchComparisonService branches;
    private final ExcelReportService excel;
    private static final org.springframework.http.MediaType XLSX=
            org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    public ReportController(ReportService reports,BranchComparisonService branches,ExcelReportService excel){
        this.reports=reports;this.branches=branches;this.excel=excel;
    }
    @GetMapping("/sales/export.xlsx")
    ResponseEntity<byte[]> exportExcel(@RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(defaultValue="ALL") String method,@RequestParam(defaultValue="false") boolean unassigned){
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"reporte-"+from+"-"+to+".xlsx\"")
                .body(excel.sales(from,to,method,unassigned));
    }
    /** Comparativo entre sucursales: solo el dueño ve todas. */
    @GetMapping("/branches") @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    BranchComparisonService.Comparison branches(@RequestParam LocalDate from,@RequestParam LocalDate to){return branches.compare(from,to);}
    @GetMapping("/branches/export.xlsx") @PreAuthorize("hasRole('ORGANIZATION_ADMIN')")
    ResponseEntity<byte[]> branchesExcel(@RequestParam LocalDate from,@RequestParam LocalDate to){
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"sucursales-"+from+"-"+to+".xlsx\"")
                .body(excel.branches(from,to));
    }
    @GetMapping("/sales") ReportService.Report summary(@RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(defaultValue="ALL") String method,@RequestParam(defaultValue="false") boolean unassigned,
            @RequestParam(defaultValue="0") int page){return reports.summary(from,to,method,unassigned,page);}
    @GetMapping(value="/sales/export",produces="text/csv;charset=UTF-8")
    ResponseEntity<String> export(@RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam(defaultValue="ALL") String method,@RequestParam(defaultValue="false") boolean unassigned){
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"cobros-"+from+"-"+to+".csv\"")
                .body(reports.export(from,to,method,unassigned));
    }
}
