package com.restos.setup;

import com.restos.security.CurrentUser;
import com.restos.shared.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puesta en marcha de un restaurante nuevo: carta desde Excel (con vista previa antes de guardar),
 * mesas en lote y una lista de pendientes para quedar listo para atender.
 */
@Service
public class SetupService {
    private static final int MAX_ROWS = 1000;
    private final JdbcClient jdbc;
    private final CurrentUser currentUser;

    public SetupService(JdbcClient jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    /** Plantilla con columnas, ejemplos y una hoja de instrucciones. */
    public byte[] template() {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var bold = workbook.createCellStyle();
            var font = workbook.createFont(); font.setBold(true); bold.setFont(font);
            var sheet = workbook.createSheet("Carta");
            String[][] rows = {
                    {"Categoría", "Producto", "Descripción", "Precio", "Estación"},
                    {"Entradas", "Causa limeña", "Papa amarilla, pollo y palta", "18.00", "Cocina"},
                    {"Platos principales", "Lomo saltado", "Carne salteada, papas fritas y arroz", "35.00", "Cocina"},
                    {"Bebidas", "Chicha morada (jarra)", "1 litro", "12.00", "Barra"}};
            for (int r = 0; r < rows.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    var cell = row.createCell(c);
                    if (r > 0 && c == 3) cell.setCellValue(Double.parseDouble(rows[r][c])); else cell.setCellValue(rows[r][c]);
                    if (r == 0) cell.setCellStyle(bold);
                }
            }
            for (int c = 0; c < 5; c++) sheet.setColumnWidth(c, c == 2 ? 12000 : 6000);
            var help = workbook.createSheet("Instrucciones");
            String[] lines = {
                    "Cómo llenar la carta",
                    "1. Una fila por producto. Categoría, Producto y Precio son obligatorios.",
                    "2. El precio va en soles, con punto o coma decimal (ej. 35.50).",
                    "3. Estación es opcional (Cocina, Barra, Postres…); se crea si no existe.",
                    "4. Si un producto ya existe con el mismo nombre, se actualizan su categoría, descripción y precio.",
                    "5. Las fotos y opciones (término, extras) se agregan luego desde Menú.",
                    "6. Borra las filas de ejemplo antes de importar."};
            for (int i = 0; i < lines.length; i++) help.createRow(i).createCell(0).setCellValue(lines[i]);
            help.setColumnWidth(0, 30000);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar la plantilla", exception);
        }
    }

    /** Lee la carta; con {@code apply=false} solo valida y muestra qué se creará o actualizará. */
    @Transactional
    public ImportResult importMenu(InputStream input, boolean apply) {
        var scope = currentUser.require();
        var rows = read(input);
        var existingProducts = new HashMap<String, UUID>();
        jdbc.sql("SELECT id,name FROM menu_items WHERE organization_id=:org").param("org", scope.organizationId())
                .query((rs, row) -> existingProducts.put(key(rs.getString("name")), rs.getObject("id", UUID.class))).list();
        var existingCategories = new HashMap<String, UUID>();
        jdbc.sql("SELECT id,name FROM menu_categories WHERE organization_id=:org").param("org", scope.organizationId())
                .query((rs, row) -> existingCategories.put(key(rs.getString("name")), rs.getObject("id", UUID.class))).list();
        var seen = new HashMap<String, Integer>();
        var preview = new ArrayList<PreviewRow>();
        for (var row : rows) {
            String error = null;
            if (row.category().isBlank()) error = "Falta la categoría";
            else if (row.name().isBlank()) error = "Falta el nombre del producto";
            else if (row.name().length() > 120 || row.category().length() > 80) error = "Nombre demasiado largo";
            else if (row.price() == null) error = "Precio inválido";
            else if (row.price().signum() < 0) error = "El precio no puede ser negativo";
            else if (seen.containsKey(key(row.name()))) error = "Repetido en la fila " + seen.get(key(row.name()));
            if (error == null) seen.put(key(row.name()), row.line());
            var action = error != null ? "ERROR" : existingProducts.containsKey(key(row.name())) ? "UPDATE" : "CREATE";
            preview.add(new PreviewRow(row.line(), row.category(), row.name(), row.description(), row.price(), row.station(),
                    action, error));
        }
        var errors = preview.stream().filter(row -> row.action().equals("ERROR")).count();
        var newCategories = preview.stream().filter(row -> !row.action().equals("ERROR"))
                .map(row -> key(row.category())).distinct().filter(name -> !existingCategories.containsKey(name)).count();
        var result = new ImportResult(preview, preview.stream().filter(r -> r.action().equals("CREATE")).count(),
                preview.stream().filter(r -> r.action().equals("UPDATE")).count(), errors, newCategories, false);
        if (!apply) return result;
        if (errors > 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Corrige las " + errors + " filas con error antes de importar");
        if (preview.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "El archivo no tiene productos");

        var stations = new HashMap<String, UUID>();
        jdbc.sql("SELECT id,name FROM kitchen_stations WHERE organization_id=:org").param("org", scope.organizationId())
                .query((rs, r) -> stations.put(key(rs.getString("name")), rs.getObject("id", UUID.class))).list();
        var nextOrder = jdbc.sql("SELECT COALESCE(max(display_order)+1,0) FROM menu_categories WHERE organization_id=:org")
                .param("org", scope.organizationId()).query(Integer.class).single();
        for (var row : preview) {
            UUID stationId = null;
            if (!row.station().isBlank()) {
                stationId = stations.computeIfAbsent(key(row.station()), name -> {
                    var id = UUID.randomUUID();
                    jdbc.sql("INSERT INTO kitchen_stations(id,organization_id,name,display_order) VALUES (:id,:org,:name,:order)")
                            .param("id", id).param("org", scope.organizationId()).param("name", row.station().trim())
                            .param("order", stations.size()).update();
                    return id;
                });
            }
            var categoryKey = key(row.category());
            if (!existingCategories.containsKey(categoryKey)) {
                var id = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO menu_categories(id,organization_id,name,display_order,active,station_id)
                        VALUES (:id,:org,:name,:order,TRUE,:station)
                        """).param("id", id).param("org", scope.organizationId()).param("name", row.category().trim())
                        .param("order", nextOrder++).param("station", stationId).update();
                existingCategories.put(categoryKey, id);
            } else if (stationId != null) {
                jdbc.sql("UPDATE menu_categories SET station_id=:station WHERE id=:id AND station_id IS NULL")
                        .param("station", stationId).param("id", existingCategories.get(categoryKey)).update();
            }
            var categoryId = existingCategories.get(categoryKey);
            var description = row.description().isBlank() ? null : row.description().trim();
            if (row.action().equals("UPDATE")) {
                jdbc.sql("""
                        UPDATE menu_items SET category_id=:category,description=COALESCE(:description,description),price=:price,
                          updated_at=NOW() WHERE id=:id
                        """).param("category", categoryId).param("description", description).param("price", row.price())
                        .param("id", existingProducts.get(key(row.name()))).update();
            } else {
                jdbc.sql("""
                        INSERT INTO menu_items(id,organization_id,category_id,name,description,price,active)
                        VALUES (:id,:org,:category,:name,:description,:price,TRUE)
                        """).param("id", UUID.randomUUID()).param("org", scope.organizationId()).param("category", categoryId)
                        .param("name", row.name().trim()).param("description", description).param("price", row.price()).update();
            }
        }
        return new ImportResult(preview, result.toCreate(), result.toUpdate(), 0, newCategories, true);
    }

    /** Crea mesas numeradas correlativamente; omite nombres que ya existen en la sucursal. */
    @Transactional
    public TablesResult createTables(String prefix, int from, int count, int capacity, UUID zoneId) {
        var scope = currentUser.require();
        if (zoneId != null) {
            var zone = jdbc.sql("SELECT count(*) FROM dining_zones WHERE id=:id AND branch_id=:branch AND active")
                    .param("id", zoneId).param("branch", scope.branchId()).query(Integer.class).single();
            if (zone == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "Zona inválida");
        }
        var existing = new java.util.HashSet<String>();
        jdbc.sql("SELECT lower(label) FROM dining_tables WHERE branch_id=:branch").param("branch", scope.branchId())
                .query(String.class).list().forEach(existing::add);
        var created = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        var cleanPrefix = prefix == null ? "" : prefix.trim();
        for (int number = from; number < from + count; number++) {
            var label = (cleanPrefix.isEmpty() ? "" : cleanPrefix + " ") + String.format("%02d", number);
            if (label.length() > 30 || existing.contains(label.toLowerCase())) { skipped.add(label); continue; }
            jdbc.sql("""
                    INSERT INTO dining_tables(organization_id,branch_id,label,capacity,zone_id)
                    VALUES (:org,:branch,:label,:capacity,:zone)
                    """).param("org", scope.organizationId()).param("branch", scope.branchId()).param("label", label)
                    .param("capacity", capacity).param("zone", zoneId).update();
            created.add(label);
        }
        return new TablesResult(created, skipped);
    }

    /** Lista de pendientes para considerar al local listo para abrir. */
    public SetupStatus status() {
        var scope = currentUser.require();
        return jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM menu_categories WHERE organization_id=:org AND active) categories,
                          (SELECT count(*) FROM menu_items WHERE organization_id=:org AND active) products,
                          (SELECT count(*) FROM menu_items WHERE organization_id=:org AND active AND image_url IS NOT NULL) with_photo,
                          (SELECT count(*) FROM dining_tables WHERE branch_id=:branch AND active) tables,
                          (SELECT count(*) FROM app_users WHERE branch_id=:branch AND active AND role IN ('WAITER','KITCHEN','BRANCH_MANAGER')) staff,
                          (SELECT count(*) FROM app_users WHERE branch_id=:branch AND active AND pin_hash IS NOT NULL) with_pin,
                          (SELECT count(*) FROM staff_devices WHERE branch_id=:branch AND revoked_at IS NULL) devices,
                          (SELECT logo_url IS NOT NULL FROM organizations WHERE id=:org) has_logo,
                          (SELECT count(*) FROM ingredients WHERE organization_id=:org AND active) ingredients,
                          (SELECT count(DISTINCT menu_item_id) FROM recipe_lines r JOIN menu_items m ON m.id=r.menu_item_id
                             WHERE m.organization_id=:org) recipes,
                          (SELECT count(*) FROM cash_shifts WHERE branch_id=:branch) cash_shifts
                        """).param("org", scope.organizationId()).param("branch", scope.branchId())
                .query((rs, row) -> new SetupStatus(rs.getInt("categories"), rs.getInt("products"), rs.getInt("with_photo"),
                        rs.getInt("tables"), rs.getInt("staff"), rs.getInt("with_pin"), rs.getInt("devices"),
                        rs.getBoolean("has_logo"), rs.getInt("ingredients"), rs.getInt("recipes"), rs.getInt("cash_shifts")))
                .single();
    }

    private List<SourceRow> read(InputStream input) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            var sheet = workbook.getSheetAt(0);
            var formatter = new DataFormatter(java.util.Locale.US);
            var header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw new ApiException(HttpStatus.BAD_REQUEST, "La hoja está vacía");
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (var cell : header) columns.put(key(formatter.formatCellValue(cell)), cell.getColumnIndex());
            var category = column(columns, "categoria");
            var product = column(columns, "producto", "nombre", "plato");
            var price = column(columns, "precio");
            if (category == null || product == null || price == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "La primera fila debe tener las columnas Categoría, Producto y Precio. Descarga la plantilla");
            var description = column(columns, "descripcion");
            var station = column(columns, "estacion");
            var result = new ArrayList<SourceRow>();
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                var name = text(row, product, formatter);
                var cat = text(row, category, formatter);
                var priceText = text(row, price, formatter);
                if (name.isBlank() && cat.isBlank() && priceText.isBlank()) continue;
                if (result.size() >= MAX_ROWS) throw new ApiException(HttpStatus.BAD_REQUEST, "Máximo " + MAX_ROWS + " productos por archivo");
                result.add(new SourceRow(r + 1, cat, name, text(row, description, formatter), parsePrice(row.getCell(price), priceText),
                        text(row, station, formatter)));
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ApiException api) throw api;
            throw new ApiException(HttpStatus.BAD_REQUEST, "No se pudo leer el archivo. Usa un Excel .xlsx basado en la plantilla");
        }
    }

    private BigDecimal parsePrice(Cell cell, String text) {
        try {
            if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            }
            var clean = text.replace("S/", "").replace("s/", "").replace(" ", "").replace(",", ".");
            return clean.isBlank() ? null : new BigDecimal(clean).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String text(Row row, Integer column, DataFormatter formatter) {
        if (column == null) return "";
        var cell = row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static Integer column(Map<String, Integer> columns, String... names) {
        for (var name : names) if (columns.containsKey(name)) return columns.get(name);
        return null;
    }

    /** Compara sin mayúsculas ni tildes para que «Categoría» y «categoria» sean lo mismo. */
    static String key(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .trim().toLowerCase();
    }

    private record SourceRow(int line, String category, String name, String description, BigDecimal price, String station) {}
    public record PreviewRow(int line, String category, String name, String description, BigDecimal price, String station,
                             String action, String error) {}
    public record ImportResult(List<PreviewRow> rows, long toCreate, long toUpdate, long errors, long newCategories,
                               boolean applied) {}
    public record TablesResult(List<String> created, List<String> skipped) {}
    public record SetupStatus(int categories, int products, int productsWithPhoto, int tables, int staff, int staffWithPin,
                              int devices, boolean hasLogo, int ingredients, int recipes, int cashShifts) {}
}
