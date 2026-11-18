package com.restos.reports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/** Arma los reportes en Excel (.xlsx) con hojas separadas y montos como números para poder sumarlos. */
@Service
public class ExcelReportService {
    private final ReportService reports;
    private final BranchComparisonService branches;

    public ExcelReportService(ReportService reports, BranchComparisonService branches) {
        this.reports = reports;
        this.branches = branches;
    }

    public byte[] sales(LocalDate from, LocalDate to, String method, boolean unassigned) {
        var report = reports.summary(from, to, "ALL", false, 0);
        var ledger = reports.ledgerForExport(from, to, method, unassigned);
        var zone = reports.zone();
        return build(book -> {
            var summary = sheet(book, "Resumen", "Concepto", "Valor");
            row(book, summary, "Período", from + " al " + to);
            row(book, summary, "Total cobrado (S/)", report.totals().collected());
            row(book, summary, "Pagos registrados", report.totals().payments());
            row(book, summary, "Atenciones cobradas", report.totals().sessions());
            row(book, summary, "Propinas (S/)", report.totals().tips());
            row(book, summary, "Anulaciones (S/)", report.voids().amount());
            row(book, summary, "Descuentos (S/)", report.discounts().amount());
            row(book, summary, "Costo de insumos (S/)", report.inventoryCost());
            row(book, summary, "Margen bruto estimado (S/)", report.totals().collected().subtract(report.inventoryCost()));
            row(book, summary, "Cobrado sin caja (S/)", report.totals().unassigned());
            var days = sheet(book, "Por día", "Fecha", "Cobrado (S/)");
            report.days().forEach(day -> row(book, days, day.day().toString(), day.amount()));
            var zones = sheet(book, "Por zona", "Zona", "Atenciones", "Cobrado (S/)");
            report.zones().forEach(item -> row(book, zones, item.name(), item.sessions(), item.amount()));
            var methods = sheet(book, "Medios de pago", "Método", "Pagos", "Monto (S/)");
            report.methods().forEach(item -> row(book, methods, methodLabel(item.method()), item.count(), item.amount()));
            var products = sheet(book, "Platos más pedidos", "Plato", "Unidades", "Consumo (S/)");
            report.products().forEach(item -> row(book, products, item.name(), item.quantity(), item.amount()));
            var payments = sheet(book, "Cobros", "Fecha", "Mesa / pedido", "Método", "Monto (S/)", "Propina (S/)", "Referencia",
                    "Registrado por", "Caja");
            ledger.forEach(item -> row(book, payments, item.paidAt().atZone(zone).toLocalDateTime().toString().replace('T', ' '),
                    item.tableLabel(), methodLabel(item.method()), item.amount(), item.tip(),
                    item.reference() == null ? "" : item.reference(), item.registeredBy(), item.shiftId() == null ? "Sin caja" : "Asignada"));
        });
    }

    public byte[] branches(LocalDate from, LocalDate to) {
        var comparison = branches.compare(from, to);
        return build(book -> {
            var sheet = sheet(book, "Sucursales", "Sucursal", "Cobrado (S/)", "Pagos", "Atenciones", "Ticket promedio (S/)",
                    "Propinas (S/)", "Pedidos llevar/delivery", "Anulaciones (S/)", "Descuentos (S/)", "Costo insumos (S/)",
                    "Margen bruto (S/)", "Plato más pedido");
            comparison.branches().forEach(item -> row(book, sheet, item.name(), item.collected(), item.payments(), item.sessions(),
                    item.averageTicket(), item.tips(), item.counterOrders(), item.voids(), item.discounts(), item.inventoryCost(),
                    item.grossMargin(), item.topProduct() == null ? "" : item.topProduct()));
            row(book, sheet, "TOTAL", comparison.total());
        });
    }

    private interface Filler { void fill(Writer book); }

    /** Estado de un libro en construcción; se crea uno por descarga para no compartir estilos entre peticiones. */
    private static final class Writer {
        private final XSSFWorkbook book;
        private final CellStyle money;
        private final CellStyle bold;

        private Writer(XSSFWorkbook book) {
            this.book = book;
            this.money = book.createCellStyle();
            this.money.setDataFormat(book.createDataFormat().getFormat("#,##0.00"));
            this.bold = book.createCellStyle();
            var font = book.createFont(); font.setBold(true); this.bold.setFont(font);
        }
    }

    private byte[] build(Filler filler) {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            filler.fill(new Writer(book));
            for (int i = 0; i < book.getNumberOfSheets(); i++) {
                var sheet = book.getSheetAt(i);
                var columns = sheet.getRow(0) == null ? 0 : sheet.getRow(0).getLastCellNum();
                for (int c = 0; c < columns; c++) sheet.setColumnWidth(c, 5600);
                sheet.createFreezePane(0, 1);
            }
            book.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar el Excel", exception);
        }
    }

    private Sheet sheet(Writer writer, String name, String... headers) {
        var sheet = writer.book.createSheet(name);
        var row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) { var cell = row.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(writer.bold); }
        return sheet;
    }

    private void row(Writer writer, Sheet sheet, Object... values) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            var value = values[i];
            if (value instanceof BigDecimal number) { cell.setCellValue(number.doubleValue()); cell.setCellStyle(writer.money); }
            else if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            // Se guarda como texto literal: un valor que empiece con «=» nunca se ejecuta como fórmula.
            else cell.setCellValue(value == null ? "" : value.toString());
        }
    }

    private static String methodLabel(String method) {
        return switch (method) { case "CASH" -> "Efectivo"; case "CARD" -> "Tarjeta"; case "YAPE" -> "Yape";
            case "PLIN" -> "Plin"; default -> method; };
    }
}
