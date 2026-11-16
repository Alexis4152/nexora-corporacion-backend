package com.nexora.corp.shared.export;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TableExporterTest {

    @Test
    void neutralizaFormulasEnCsv() {
        assertThat(TableExporter.neutralize("=HYPERLINK(\"x\")")).startsWith("'=");
        assertThat(TableExporter.neutralize("+52")).isEqualTo("'+52");
        assertThat(TableExporter.neutralize("Normal")).isEqualTo("Normal");
        var t = new TableExporter.Table("T", null, List.of(new TableExporter.Column("a", "A", "text")), List.of(Map.of("a", "@SUM(A1)")), List.of());
        String csv = new String(new TableExporter().csv(t), StandardCharsets.UTF_8);
        assertThat(csv).contains("'@SUM(A1)");
    }
}

