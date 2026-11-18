package com.restos.attendance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Excel de asistencia para la planilla: hoja «Resumen» (una fila por persona) y «Detalle» (una fila por persona y día). */
@Service
public class AttendanceExcel {
    private static final Locale ES = Locale.forLanguageTag("es-PE");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", ES);
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES);
    private final ZoneId zone;

    public AttendanceExcel(@Value("${restos.business-zone:America/Lima}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    public byte[] build(AttendanceService.Report report) {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var styles = new Styles(book);
            summary(book.createSheet("Resumen"), report, styles);
            detail(book.createSheet("Detalle"), report, styles);
            book.write(out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("No se pudo generar el Excel de asistencia", error);
        }
    }

    private void summary(Sheet sheet, AttendanceService.Report report, Styles styles) {
        var title = sheet.createRow(0).createCell(0);
        title.setCellValue("Asistencia · " + report.branchName());
        title.setCellStyle(styles.title);
        var evaluatedTo = report.to().isAfter(report.today()) ? report.today() : report.to();
        sheet.createRow(1).createCell(0).setCellValue("Del " + report.from().format(LONG_DATE) + " al " + report.to().format(LONG_DATE)
                + (evaluatedTo.equals(report.to()) ? "" : " (evaluado hasta el " + evaluatedTo.format(LONG_DATE) + ")")
                + " · Tolerancia de tardanza: " + report.toleranceMinutes() + " min");
        String[] headers = {"Persona", "DNI", "Cargo", "Días programados", "Días trabajados", "Horas trabajadas",
                "Tardanzas", "Minutos de tardanza", "Faltas", "Sin salida", "Por revisar"};
        header(sheet.createRow(3), headers, styles);
        int index = 4;
        for (var person : report.people()) {
            var row = sheet.createRow(index++);
            row.createCell(0).setCellValue(person.fullName());
            text(row, 1, person.documentNumber());
            text(row, 2, person.jobTitle());
            row.createCell(3).setCellValue(person.scheduledDays());
            row.createCell(4).setCellValue(person.daysWorked());
            hours(row, 5, person.workedMinutes(), styles);
            row.createCell(6).setCellValue(person.lateCount());
            row.createCell(7).setCellValue(person.lateMinutes());
            row.createCell(8).setCellValue(person.absences());
            row.createCell(9).setCellValue(person.noOut());
            row.createCell(10).setCellValue(person.toReview());
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 10));
        widths(sheet, 8000, 3200, 5000, 3800, 3800, 3800, 3200, 4200, 2800, 3000, 3200);
        sheet.createFreezePane(1, 4);
    }

    private void detail(Sheet sheet, AttendanceService.Report report, Styles styles) {
        String[] headers = {"Fecha", "Día", "Persona", "DNI", "Horario", "Entrada", "Salida", "Horas trabajadas", "Tardanza (min)", "Estado"};
        header(sheet.createRow(0), headers, styles);
        int index = 1;
        for (var person : report.people()) {
            for (var day : person.days()) {
                var row = sheet.createRow(index++);
                var date = row.createCell(0);
                date.setCellValue(day.date());
                date.setCellStyle(styles.date);
                row.createCell(1).setCellValue(capitalize(day.date().getDayOfWeek().getDisplayName(TextStyle.FULL, ES)));
                row.createCell(2).setCellValue(person.fullName());
                text(row, 3, person.documentNumber());
                text(row, 4, day.scheduledStart() == null ? (day.verdict().equals("REST") ? "Descanso" : "") : time(day.scheduledStart()) + " – " + time(day.scheduledEnd()));
                text(row, 5, day.firstIn() == null ? null : time(day.firstIn()));
                text(row, 6, day.lastOut() == null ? null : time(day.lastOut()));
                if (day.workedMinutes() > 0) hours(row, 7, day.workedMinutes(), styles);
                if (day.lateMinutes() > 0) row.createCell(8).setCellValue(day.lateMinutes());
                text(row, 9, status(day));
            }
        }
        widths(sheet, 3200, 3200, 8000, 3200, 6800, 3400, 3400, 4000, 3600, 4200);
        sheet.createFreezePane(0, 1);
        if (index > 1) sheet.setAutoFilter(new CellRangeAddress(0, index - 1, 0, headers.length - 1));
    }

    /** Estado del día en palabras, igual que en la pantalla. */
    static String status(AttendanceService.ReportDay day) {
        if (day.needsReview()) return "Por revisar";
        return switch (day.verdict()) {
            case "ABSENT" -> "Falta";
            case "NOT_YET" -> "No llega";
            case "PENDING" -> "Aún no es su hora";
            case "LATE" -> day.state().equals("NO_OUT") ? "Tarde · sin salida" : "Tarde";
            case "ON_TIME" -> day.state().equals("NO_OUT") ? "Sin salida" : "A tiempo";
            case "REST" -> day.state().equals("ABSENT") ? "Descanso" : "Trabajó en su descanso";
            default -> day.state().equals("ABSENT") ? "Sin horario" : day.state().equals("NO_OUT") ? "Sin salida" : "Asistió";
        };
    }

    private String time(Instant instant) { return instant.atZone(zone).format(TIME); }

    private static void header(Row row, String[] headers, Styles styles) {
        for (int i = 0; i < headers.length; i++) {
            var cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }
    }

    private static void text(Row row, int column, String value) {
        if (value != null && !value.isBlank()) row.createCell(column).setCellValue(value);
    }

    /** Horas en número decimal (7,5 = 7 h 30 min) para que se puedan sumar en Excel. */
    private static void hours(Row row, int column, int minutes, Styles styles) {
        var cell = row.createCell(column);
        cell.setCellValue(Math.round(minutes / 60.0 * 100) / 100.0);
        cell.setCellStyle(styles.hours);
    }

    private static void widths(Sheet sheet, int... widths) {
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i]);
    }

    private static String capitalize(String value) { return value.substring(0, 1).toUpperCase(ES) + value.substring(1); }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle date;
        final CellStyle hours;

        Styles(XSSFWorkbook book) {
            var titleFont = book.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            title = book.createCellStyle();
            title.setFont(titleFont);
            var bold = book.createFont();
            bold.setBold(true);
            header = book.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setWrapText(true);
            var format = book.createDataFormat();
            date = book.createCellStyle();
            date.setDataFormat(format.getFormat("dd/mm/yyyy"));
            hours = book.createCellStyle();
            hours.setDataFormat(format.getFormat("0.00"));
        }
    }
}
