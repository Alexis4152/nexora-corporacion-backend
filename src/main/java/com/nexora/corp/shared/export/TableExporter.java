package com.nexora.corp.shared.export;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.nexora.corp.shared.error.BusinessException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exporta una tabla tal como se ve en pantalla (mismos filtros y permisos, porque recibe las mismas filas).
 * CSV con BOM UTF-8, Excel en streaming y PDF horizontal. Las celdas que empiezan con {@code = + - @} se neutralizan
 * para que una hoja de cálculo nunca las ejecute como fórmula (CSV injection).
 */
@Component
public class TableExporter {

    public record Column(String key, String label, String format) {
    }

    public record Table(String title, String subtitle, List<Column> columns, List<Map<String, Object>> rows, List<String> notes) {
    }

    public record ExportFile(String filename, String contentType, byte[] content) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Color INK = new Color(15, 23, 42);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color HEAD = new Color(238, 242, 255);
    private static final Color LINE = new Color(226, 232, 240);

    public ExportFile export(Table t, String format, String baseName) {
        String safe = baseName.replaceAll("[^A-Za-z0-9_-]", "-");
        try {
            return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
                case "csv" -> new ExportFile(safe + ".csv", "text/csv; charset=UTF-8", csv(t));
                case "xlsx" -> new ExportFile(safe + ".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(t));
                case "pdf" -> new ExportFile(safe + ".pdf", "application/pdf", pdf(t));
                default -> throw new BusinessException("Formato no válido. Usa csv, xlsx o pdf");
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el archivo", e);
        }
    }

    byte[] csv(Table t) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", t.columns().stream().map(c -> cell(c.label())).toList())).append("\r\n");
        for (Map<String, Object> row : t.rows()) {
            sb.append(String.join(",", t.columns().stream().map(c -> {
                Object v = row.get(c.key());
                if (v == null) {
                    return "";
                }
                if (v instanceof BigDecimal b) {
                    return b.toPlainString();
                }
                if (v instanceof Number) {
                    return v.toString();
                }
                if (v instanceof LocalDate d) {
                    return d.toString();
                }
                return cell(String.valueOf(v));
            }).toList())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(String s) {
        String safe = neutralize(s);
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
                ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    public static String neutralize(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    byte[] xlsx(Table t) throws Exception {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = wb.createCellStyle();
            var bold = wb.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.LAVENDER.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0.0\"%\""));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
            CellStyle title = wb.createCellStyle();
            title.setFont(bold);
            Sheet sheet = wb.createSheet("Datos");
            int r = 0;
            Row tr = sheet.createRow(r++);
            tr.createCell(0).setCellValue(neutralize(t.title()));
            tr.getCell(0).setCellStyle(title);
            if (t.subtitle() != null) {
                sheet.createRow(r++).createCell(0).setCellValue(neutralize(t.subtitle()));
            }
            r++;
            Row h = sheet.createRow(r++);
            for (int c = 0; c < t.columns().size(); c++) {
                Cell cell = h.createCell(c);
                cell.setCellValue(t.columns().get(c).label());
                cell.setCellStyle(header);
                sheet.setColumnWidth(c, Math.min(50, Math.max(12, t.columns().get(c).label().length() + 6)) * 256);
            }
            sheet.createFreezePane(0, r);
            for (Map<String, Object> row : t.rows()) {
                Row x = sheet.createRow(r++);
                for (int c = 0; c < t.columns().size(); c++) {
                    Column col = t.columns().get(c);
                    Object v = row.get(col.key());
                    Cell cell = x.createCell(c);
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                        if ("money".equals(col.format())) {
                            cell.setCellStyle(money);
                        } else if ("percent".equals(col.format())) {
                            cell.setCellStyle(pct);
                        }
                    } else if (v instanceof LocalDate d) {
                        cell.setCellValue(d);
                        cell.setCellStyle(date);
                    } else {
                        cell.setCellValue(neutralize(String.valueOf(v)));
                    }
                }
            }
            for (String note : t.notes() == null ? List.<String>of() : t.notes()) {
                sheet.createRow(++r).createCell(0).setCellValue(neutralize(note));
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        }
    }

    byte[] pdf(Table t) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph(t.title(), new Font(Font.HELVETICA, 15, Font.BOLD, INK)));
        if (t.subtitle() != null) {
            doc.add(new Paragraph(t.subtitle(), new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED)));
        }
        doc.add(new Paragraph(" "));
        PdfPTable table = new PdfPTable(t.columns().size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        Font hf = new Font(Font.HELVETICA, 7.5f, Font.BOLD, INK);
        Font bf = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, INK);
        for (Column c : t.columns()) {
            PdfPCell cell = new PdfPCell(new Phrase(c.label(), hf));
            cell.setBackgroundColor(HEAD);
            cell.setBorderColor(LINE);
            cell.setPadding(4);
            table.addCell(cell);
        }
        NumberFormat mf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"));
        for (Map<String, Object> row : t.rows().size() > 5000 ? t.rows().subList(0, 5000) : t.rows()) {
            for (Column c : t.columns()) {
                Object v = row.get(c.key());
                String text = v == null ? "" : "money".equals(c.format()) && v instanceof Number n ? mf.format(n)
                        : "percent".equals(c.format()) && v instanceof Number n ? n + " %"
                        : v instanceof LocalDate d ? d.format(DAY) : String.valueOf(v);
                PdfPCell cell = new PdfPCell(new Phrase(text, bf));
                cell.setBorderColor(LINE);
                cell.setPadding(3.5f);
                if (v instanceof Number) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        doc.add(table);
        for (String note : t.notes() == null ? List.<String>of() : t.notes()) {
            doc.add(new Paragraph(note, new Font(Font.HELVETICA, 7.5f, Font.ITALIC, MUTED)));
        }
        doc.close();
        return out.toByteArray();
    }
}

